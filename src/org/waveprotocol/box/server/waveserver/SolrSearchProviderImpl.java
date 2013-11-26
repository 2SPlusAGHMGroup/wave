/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.waveprotocol.box.server.waveserver;

import com.google.common.collect.HashMultimap;
import com.google.common.collect.Maps;
import com.google.common.collect.Multimap;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.inject.Inject;
import com.google.inject.name.Named;
import com.google.wave.api.SearchResult;

import org.apache.commons.httpclient.HttpClient;
import org.apache.commons.httpclient.URI;
import org.apache.commons.httpclient.methods.GetMethod;
import org.apache.http.HttpStatus;
import org.waveprotocol.box.server.CoreSettings;
import org.waveprotocol.wave.model.id.WaveId;
import org.waveprotocol.wave.model.id.WaveletId;
import org.waveprotocol.wave.model.id.WaveletName;
import org.waveprotocol.wave.model.wave.ParticipantId;
import org.waveprotocol.wave.model.wave.ParticipantIdUtil;
import org.waveprotocol.wave.model.wave.data.WaveViewData;
import org.waveprotocol.wave.model.wave.data.impl.WaveViewDataImpl;
import org.waveprotocol.wave.util.logging.Log;

import java.io.IOException;
import java.io.InputStreamReader;
import java.util.Collection;
import java.util.Map;

/**
 * Search provider that offers full text search
 * 
 * @author Frank R. <renfeng.cn@gmail.com>
 */
public class SolrSearchProviderImpl implements SearchProvider {

  private static final Log LOG = Log.get(SolrSearchProviderImpl.class);

  /*
   * TODO make it configurable
   */
  public static final String SOLR_BASE_URL = "http://localhost:8983/solr";

  /*-
   * http://wiki.apache.org/solr/CommonQueryParameters#q
   */
  public static final String Q = "waveId_s:[* TO *]" //
      + " AND waveletId_s:[* TO *]" //
      + " AND docName_s:[* TO *]" //
      + " AND lmt_l:[* TO *]" //
      + " AND with_txt:[* TO *]" //
      + " AND text_t:[* TO *]" //
      + " AND in_ss:[* TO *]";

  /*-
   * XXX will it be better to replace lucene with edismax?
   * mm (Minimum 'Should' Match)
   * http://wiki.apache.org/solr/ExtendedDisMax#mm_.28Minimum_.27Should.27_Match.29
   * 
   * XXX not at the moment! q.op=AND is ignored by !edismax, see
   * 
   * ExtendedDismaxQParser (edismax) does not obey q.op for queries with operators
   * https://issues.apache.org/jira/browse/SOLR-3741
   * 
   * ExtendedDismaxQParser (edismax) does not obey q.op for parenthesized sub-queries
   * https://issues.apache.org/jira/browse/SOLR-3740
   * 
   */
  public static final String FILTER_QUERY_PREFIX = "{!lucene q.op=AND df=text_t}with_txt:";

  private final WaveDigester digester;
  private final WaveMap waveMap;

  /*
   * TODO find out what to do with it
   */
  @SuppressWarnings("unused")
  private final ParticipantId sharedDomainParticipantId;

  /*
   * XXX remove this since
   */
  // private final PerUserWaveViewProvider waveViewProvider;

  public static String buildUserQuery(String query) {
    return query.replaceAll("\\bin:", "in_ss:").replaceAll("\\bwith:", "with_txt:");
  }

  @Inject
  public SolrSearchProviderImpl(@Named(CoreSettings.WAVE_SERVER_DOMAIN) final String waveDomain,
      WaveDigester digester, final WaveMap waveMap) {
    this.digester = digester;
    this.waveMap = waveMap;
    // this.waveViewProvider = userWaveViewProvider;
    sharedDomainParticipantId = ParticipantIdUtil.makeUnsafeSharedDomainParticipantId(waveDomain);
  }

  @Override
  public SearchResult search(final ParticipantId user, String query, int startAt, int numResults) {
    LOG.fine("Search query '" + query + "' from user: " + user + " [" + startAt + ", "
        + (startAt + numResults - 1) + "]");

    Multimap<WaveId, WaveletId> currentUserWavesView = HashMultimap.create();

    if (numResults > 0) {

      String fq = FILTER_QUERY_PREFIX + user.getAddress();
      if (query.length() > 0) {
        fq += " AND (" + buildUserQuery(query) + ")";
      }

      int start = startAt;
      int rows = Math.max(numResults, 10);

      GetMethod getMethod = new GetMethod();
      try {
        while (true) {
          getMethod.setURI(new URI(SOLR_BASE_URL + "/select?wt=json" + "&start=" + start + "&rows="
              + rows + "&q=" + Q + "&fq=" + fq, false));

          HttpClient httpClient = new HttpClient();
          int statusCode = httpClient.executeMethod(getMethod);
          if (statusCode != HttpStatus.SC_OK) {
            LOG.warning("Failed to execute query: " + query);
            return digester.generateSearchResult(user, query, null);
          }

          JsonObject json =
              new JsonParser().parse(new InputStreamReader(getMethod.getResponseBodyAsStream()))
                  .getAsJsonObject();
          JsonObject responseJson = json.getAsJsonObject("response");
          JsonArray docsJson = responseJson.getAsJsonArray("docs");
          if (docsJson.size() == 0) {
            break;
          }

          for (int i = 0; i < docsJson.size(); i++) {
            JsonObject docJson = docsJson.get(i).getAsJsonObject();
            WaveId waveId =
                WaveId.deserialise(docJson.getAsJsonPrimitive("waveId_s").getAsString());
            WaveletId waveletId =
                WaveletId.deserialise(docJson.getAsJsonPrimitive("waveletId_s").getAsString());
            currentUserWavesView.put(waveId, waveletId);
            if (currentUserWavesView.size() >= numResults) {
              break;
            }
          }

          if (currentUserWavesView.size() >= numResults) {
            break;
          }

          if (docsJson.size() < rows) {
            break;
          }

          start += rows;
        }

      } catch (IOException e) {
        LOG.warning("Failed to execute query: " + query);
        return digester.generateSearchResult(user, query, null);
      } finally {
        getMethod.releaseConnection();
      }
    }

    Map<WaveId, WaveViewData> results = filterWavesViewBySearchCriteria(currentUserWavesView);
    if (LOG.isFineLoggable()) {
      for (Map.Entry<WaveId, WaveViewData> e : results.entrySet()) {
        LOG.fine("filtered results contains: " + e.getKey());
      }
    }

    Collection<WaveViewData> searchResult = results.values();
    LOG.info("Search response to '" + query + "': " + searchResult.size() + " results, user: "
        + user);
    return digester.generateSearchResult(user, query, searchResult);
  }

  private Map<WaveId, WaveViewData> filterWavesViewBySearchCriteria(
      Multimap<WaveId, WaveletId> currentUserWavesView) {
    // Must use a map with stable ordering, since indices are meaningful.
    Map<WaveId, WaveViewData> results = Maps.newLinkedHashMap();

    // Loop over the user waves view.
    for (WaveId waveId : currentUserWavesView.keySet()) {
      Collection<WaveletId> waveletIds = currentUserWavesView.get(waveId);
      WaveViewData view = null; // Copy of the wave built up for search hits.
      for (WaveletId waveletId : waveletIds) {
        WaveletContainer waveletContainer = null;
        WaveletName waveletname = WaveletName.of(waveId, waveletId);

        // TODO (alown): Find some way to use isLocalWavelet to do this
        // properly!
        try {
          if (LOG.isFineLoggable()) {
            LOG.fine("Trying as a remote wavelet");
          }
          waveletContainer = waveMap.getRemoteWavelet(waveletname);
        } catch (WaveletStateException e) {
          LOG.severe(String.format("Failed to get remote wavelet %s", waveletname.toString()), e);
        } catch (NullPointerException e) {
          // This is a fairly normal case of it being a local-only wave.
          // Yet this only seems to appear in the test suite.
          // Continuing is completely harmless here.
          LOG.info(
              String.format("%s is definitely not a remote wavelet. (Null key)",
                  waveletname.toString()), e);
        }

        if (waveletContainer == null) {
          try {
            if (LOG.isFineLoggable()) {
              LOG.fine("Trying as a local wavelet");
            }
            waveletContainer = waveMap.getLocalWavelet(waveletname);
          } catch (WaveletStateException e) {
            LOG.severe(String.format("Failed to get local wavelet %s", waveletname.toString()), e);
          }
        }

        // TODO (Yuri Z.) This loop collects all the wavelets that match the
        // query, so the view is determined by the query. Instead we should
        // look at the user's wave view and determine if the view matches the
        // query.
        try {
          if (waveletContainer == null) {
            LOG.fine("----doesn't match: " + waveletContainer);
            continue;
          }
          if (view == null) {
            view = WaveViewDataImpl.create(waveId);
          }
          // Just keep adding all the relevant wavelets in this wave.
          view.addWavelet(waveletContainer.copyWaveletData());
        } catch (WaveletStateException e) {
          LOG.warning("Failed to access wavelet " + waveletContainer.getWaveletName(), e);
        }
      }
      if (view != null) {
        results.put(waveId, view);
      }
    }
    return results;
  }
}
