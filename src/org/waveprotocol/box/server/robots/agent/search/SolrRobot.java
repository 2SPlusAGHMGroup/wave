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

package org.waveprotocol.box.server.robots.agent.search;

import com.google.common.util.concurrent.ListenableFutureTask;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.inject.Inject;
import com.google.inject.Injector;
import com.google.inject.Singleton;
import com.google.inject.name.Named;
import com.google.wave.api.Annotation;
import com.google.wave.api.Annotations;
import com.google.wave.api.Blip;
import com.google.wave.api.BlipContentRefs;
import com.google.wave.api.Range;
import com.google.wave.api.SearchResult.Digest;
import com.google.wave.api.Wavelet;
import com.google.wave.api.event.DocumentChangedEvent;
import com.google.wave.api.impl.DocumentModifyAction.BundledAnnotation;

import org.apache.commons.httpclient.HttpClient;
import org.apache.commons.httpclient.URI;
import org.apache.commons.httpclient.methods.GetMethod;
import org.apache.http.HttpStatus;
import org.waveprotocol.box.server.CoreSettings;
import org.waveprotocol.box.server.robots.agent.AbstractBaseRobotAgent;
import org.waveprotocol.box.server.waveserver.SolrSearchProviderImpl;
import org.waveprotocol.box.server.waveserver.WaveDigester;
import org.waveprotocol.box.server.waveserver.WaveMap;
import org.waveprotocol.wave.model.conversation.AnnotationConstants;
import org.waveprotocol.wave.model.id.WaveId;
import org.waveprotocol.wave.model.id.WaveletId;
import org.waveprotocol.wave.model.wave.ParticipantId;
import org.waveprotocol.wave.model.wave.ParticipantIdUtil;
import org.waveprotocol.wave.util.logging.Log;

import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/**
 * Robot that offers full text search
 * 
 * @author Frank R. <renfeng.cn@gmail.com>
 */
@SuppressWarnings("serial")
@Singleton
public class SolrRobot extends AbstractBaseRobotAgent {

  /*
   * (regression alert) The logging class is different from that being used by
   * other built-in robots.
   */
  // private static final Logger LOG =
  // Logger.getLogger(SolrRobot.class.getName());
  private static final Log LOG = Log.get(SolrRobot.class);

  // TODO (Yuri Z.): Inject executor.
  private static final Executor executor = Executors.newSingleThreadExecutor();

  /*-
   * http://wiki.apache.org/solr/HighlightingParameters#hl.simple.pre.2Fhl.simple.post
   */
  private static final String PRE_TAG = "<em>";
  private static final String POST_TAG = "</em>";
  private static final int PRE_TAG_LENGTH = PRE_TAG.length();
  private static final int POST_TAG_LENGTH = POST_TAG.length();

  private static final String HIGHLIGHT_BACKGROUND_COLOR = "rgb(255,255,0)";
  private static final String ERROR_BACKGROUND_COLOR = "rgb(255,0,0)";
  private static final String ERROR_COLOR = "rgb(255,255,255)";

  /*
   * the last three seconds (3000 ms)
   */
  private static final int BLINKY_THRESHOLD = 3000;

  /*-
   * XXX (Frank R.) it prevents build failure due to encoding of the unicode
   * character 0x2014 (&mdash; as html entity)
   *
   * TODO find if there is a constant defined in gwt client, and replace this
   */
  private static final String MDASH = new String(Character.toChars(0x2014));

  public static final String ROBOT_URI = AGENT_PREFIX_URI + "/search/solr";

  private final ParticipantId sharedDomainParticipantId;
  private final WaveDigester digester;
  private final WaveMap waveMap;

  @Inject
  public SolrRobot(Injector injector, @Named(CoreSettings.WAVE_SERVER_DOMAIN) String waveDomain,
      WaveDigester digester, WaveMap waveMap) {
    super(injector);
    this.digester = digester;
    this.waveMap = waveMap;
    sharedDomainParticipantId = ParticipantIdUtil.makeUnsafeSharedDomainParticipantId(waveDomain);
  }

  @Override
  public String getRobotUri() {
    return ROBOT_URI;
  }

  @Override
  public String getRobotId() {
    return "solr-bot";
  }

  @Override
  protected String getRobotName() {
    return "Solr-Bot";
  }

  @Override
  protected String getRobotProfilePageUrl() {
    return SolrSearchProviderImpl.SOLR_BASE_URL;
  }

  @Override
  protected String getRobotAvatarUrl() {
    return SolrSearchProviderImpl.SOLR_BASE_URL + "/img/solr.png";
  }

  @Override
  public void onDocumentChanged(DocumentChangedEvent event) {

    /*
     * the creator shall be aware of the query
     */
    Wavelet wavelet = event.getWavelet();
    String creator = wavelet.getCreator();
    boolean searchAllowed = wavelet.getParticipants().contains(creator);

    Blip blip = event.getBlip();

    /*-
     * looks for the line the user just edited. i.e.
     * 1. ends with a new line
     * 2. was changed just now
     */
    long now = new Date().getTime();
    ArrayList<String> activeBlinkyBits = new ArrayList<String>();
    Annotations annotations = blip.getAnnotations();
    Iterator<Annotation> annotationIterator = annotations.iterator();
    while (annotationIterator.hasNext()) {
      Annotation annotation = annotationIterator.next();
      String annotationKey = annotation.getName();

      /*-
       * for "user/d/" and "user/e/", see
       * 8.4.3.2.  User
       * http://wave-protocol.googlecode.com/hg/spec/conversation/convspec.html#anchor28
       */
      if (annotationKey.startsWith(AnnotationConstants.USER_DATA)) {
        /*-
         * The value of the annotation is a comma separated list of
         * (userid, timestamp [,ime composition state])
         * The timestamp is the last time the cursor was updated.
         */
        String[] values = annotation.getValue().split(",");
        double timestamp = Double.parseDouble(values[1]);
        if (now - timestamp <= BLINKY_THRESHOLD) {
          activeBlinkyBits.add(annotationKey.replaceFirst(AnnotationConstants.USER_DATA,
              AnnotationConstants.USER_END));
        }
      }
    }
    String content = blip.getContent();
    for (String annotationKey : activeBlinkyBits) {
      List<Annotation> list = annotations.get(annotationKey);
      for (Annotation a : list) {
        /*
         * The first point in the range of the annotation is the cursor location
         * for the users session.
         */
        Range range = a.getRange();
        int start = range.getStart();
        String query = content.substring(0, start);

        /*
         * XXX (Frank R.) (experimental) takes only the last line as a query
         * string. To enable query on any line, comment out the last statement
         * of "break;", and the condition of "start == range.getEnd()"
         */
        if (query.endsWith("\n") && start == range.getEnd()) {
          /*
           * (regression alert) the commented code does work as expected
           */
          // int endOfPreviousLine = blip.getContent().lastIndexOf("\n", start
          // -
          // 1);

          /*
           * trims the last new line character
           */
          query = query.substring(0, query.length() - 1);

          /*-
           * trims previous lines
           */
          int endOfPreviousLine = query.lastIndexOf("\n");
          if (endOfPreviousLine != -1) {
            query = query.substring(endOfPreviousLine + 1);
          }

          /*
           * XXX (Frank R.) (experimental) ignores all (empty) query
           */
          if (query.length() > 0) {
            Blip outputBlip = blip.insertInlineBlip(start - 1);
            if (searchAllowed) {
              /*
               * TODO (Frank R.) async output of search results
               */
              // searchAsync(message, creator, outputBlip);
              search(query, creator, outputBlip);
            } else {
              appendError(outputBlip,
                  "Search is allowed only when the creator of the wave is currently a participant.");
            }
          }

          /*
           * XXX (Frank R.) (experimental) for query at last line only
           */
          break;
        }
      }
    }

    return;
  }

  private void appendError(Blip outputBlip, String message) {

    /*
     * white text on red background
     */
    if (message.length() > 0) {
      int startIndex = outputBlip.length();
      BlipContentRefs.all(outputBlip).insertAfter(
          BundledAnnotation.listOf(Annotation.BACKGROUND_COLOR, ERROR_BACKGROUND_COLOR,
              Annotation.COLOR, ERROR_COLOR), message);
      int endIndex = outputBlip.length();
      BlipContentRefs.range(outputBlip, startIndex, endIndex).clearAnnotation(Annotation.WAVE_LINK);
    }

    return;
  }

  @SuppressWarnings("unused")
  private void searchAsync(final String query, final String creator, final Blip outputBlip) {

    /*
     * FIXME (Frank R.) the blip doesn't get updated
     */
    ListenableFutureTask<Void> task = new ListenableFutureTask<Void>(new Callable<Void>() {

      @Override
      public Void call() throws Exception {
        search(query, creator, outputBlip);
        return null;
      }
    });
    executor.execute(task);

    return;
  }

  private void search(String query, String creator, Blip outputBlip) {

    // Maybe should be changed in case other folders in addition to 'inbox' are
    // added.
    final boolean isAllQuery = !SolrSearchProviderImpl.isAllQuery(query);

    /*
     * for filtering current wave from the search result
     */
    String outputWaveId = outputBlip.getWaveId().serialise();

    /*
     * (regression alert) com.google.wave.api.Blip.appendMarkup(String) doesn't
     * work as expected
     */
    // StringBuilder messageBuilder = new StringBuilder();
    // messageBuilder.append("hello <a href='/'>wave</a>");

    String errorMessage = "Failed to execute query: " + query;

    int start = 0;
    int rows = SolrSearchProviderImpl.ROWS;
    int count = 0;

    /*-
     * "fq" stands for Filter Query. see
     * http://wiki.apache.org/solr/CommonQueryParameters#fq
     */
    String fq =
        SolrSearchProviderImpl.buildFilterQuery(query, isAllQuery, creator,
            sharedDomainParticipantId);

    GetMethod getMethod = new GetMethod();
    try {
      while (true) {
        /*-
         * http://wiki.apache.org/solr/HighlightingParameters
         */
        getMethod.setURI(new URI(SolrSearchProviderImpl.SOLR_BASE_URL + "/select?wt=json"
            + "&hl=true&hl.fl=" + SolrSearchProviderImpl.TEXT + "&hl.q="
            + SolrSearchProviderImpl.buildUserQuery(query) + "&start=" + start + "&rows=" + rows
            + "&q=" + SolrSearchProviderImpl.Q + "&fq=" + fq, false));

        HttpClient httpClient = new HttpClient();
        int statusCode = httpClient.executeMethod(getMethod);
        if (statusCode != HttpStatus.SC_OK) {
          LOG.warning(errorMessage);
          appendError(outputBlip, errorMessage);
          return;
        }

        JsonObject json =
            new JsonParser().parse(new InputStreamReader(getMethod.getResponseBodyAsStream()))
                .getAsJsonObject();
        JsonObject responseJson = json.getAsJsonObject("response");
        JsonArray docsJson = responseJson.getAsJsonArray("docs");
        if (docsJson.size() == 0) {
          break;
        }

        JsonObject highlighting = json.getAsJsonObject("highlighting");

        Iterator<JsonElement> docJsonIterator = docsJson.iterator();
        while (docJsonIterator.hasNext()) {
          JsonObject docJson = docJsonIterator.next().getAsJsonObject();

          /*
           * TODO (Frank R.) c.f.
           * org.waveprotocol.box.server.waveserver.SimpleSearchProviderImpl
           * .isWaveletMatchesCriteria(ReadableWaveletData, ParticipantId,
           * ParticipantId, List<ParticipantId>, List<ParticipantId>, boolean)
           */

          String id = docJson.getAsJsonPrimitive(SolrSearchProviderImpl.ID).getAsString();
          if (id.startsWith(outputWaveId)) {
            continue;
          }
          count++;

          WaveId waveId =
              WaveId.deserialise(docJson.getAsJsonPrimitive(SolrSearchProviderImpl.WAVE_ID)
                  .getAsString());
          WaveletId waveletId =
              WaveletId.deserialise(docJson.getAsJsonPrimitive(SolrSearchProviderImpl.WAVELET_ID)
                  .getAsString());

          JsonObject snippetJson = highlighting.getAsJsonObject(id);
          JsonArray textsJson = snippetJson.getAsJsonArray(SolrSearchProviderImpl.TEXT);
          Iterator<JsonElement> textJsonIterator = textsJson.iterator();
          while (textJsonIterator.hasNext()) {
            JsonElement textJson = textJsonIterator.next();
            String snippet = textJson.getAsString().trim();

            appendNormal(outputBlip, "\n" + count + " ");

            /*-
             * XXX (Frank R.) mimics how gwt web client concatenates wave title and snippet
             *
             * (regression alert) don't need to index wave title. see
             * org.waveprotocol.box.server.waveserver.WaveDigester.generateSearchResult(ParticipantId, String, Collection<WaveViewData>)
             */
            // String linkText = "wave://" + id;
            Digest digest =
                digester.build(ParticipantId.of(creator), SolrSearchProviderImpl.buildWaveViewData(
                    waveId, Arrays.asList(waveletId), SolrSearchProviderImpl.matchesFunction,
                    waveMap));
            String linkText = digest.getTitle() + " " + MDASH + " " + digest.getSnippet();

            appendWaveLink(outputBlip, id, linkText);

            appendNormal(outputBlip, "\n");

            /*
             * locate the first highlighted text
             */
            int preTag = snippet.indexOf(PRE_TAG);
            if (preTag != -1) {
              appendNormal(outputBlip, snippet.substring(0, preTag));
              int postTag = snippet.indexOf(POST_TAG, preTag + PRE_TAG_LENGTH);
              if (postTag != -1) {
                appendHighlighted(outputBlip, snippet.substring(preTag + PRE_TAG_LENGTH, postTag));

                /*
                 * locate the remaining highlighted texts
                 */
                int lastTag = postTag + POST_TAG_LENGTH;
                while (true) {
                  preTag = snippet.indexOf(PRE_TAG, lastTag);
                  if (preTag != -1) {
                    appendNormal(outputBlip, snippet.substring(lastTag, preTag));
                    postTag = snippet.indexOf(POST_TAG, preTag + PRE_TAG_LENGTH);
                    if (postTag != -1) {
                      appendHighlighted(outputBlip,
                          snippet.substring(preTag + PRE_TAG_LENGTH, postTag));
                      lastTag = postTag + POST_TAG_LENGTH;
                    } else {
                      /*
                       * Highlight everything remaining just in case there is a
                       * closing (post) tag missing
                       */
                      appendHighlighted(outputBlip, snippet.substring(preTag + PRE_TAG_LENGTH));
                      break;
                    }
                  } else {
                    appendNormal(outputBlip, snippet.substring(lastTag));
                    break;
                  }
                }
              } else {
                /*
                 * Highlight everything remaining just in case there is a
                 * closing (post) tag missing
                 */
                appendHighlighted(outputBlip, snippet.substring(preTag + PRE_TAG_LENGTH));
              }
            } else {
              /*
               * The snippet should at least contains one highlight. Just in
               * case there is none, let's print everything as normal text.
               */
              appendNormal(outputBlip, snippet);
            }

            appendNormal(outputBlip, "\n");

          }
        }

        /*
         * there won't be any more results - stop querying next page of results
         */
        if (docsJson.size() < rows) {
          break;
        }

        start += rows;
      }

      /*
       * Note: numFound isn't accurate because the search result will include
       * blips in current wave.
       */
      BlipContentRefs.range(outputBlip, 0, 1).insert("Found: " + count);

    } catch (Exception e) {
      LOG.warning(errorMessage, e);
      appendError(outputBlip, errorMessage);
      return;
    } finally {
      getMethod.releaseConnection();
    }

    return;
  }

  private void appendHighlighted(Blip outputBlip, String text) {
    BlipContentRefs.all(outputBlip).insertAfter(
        BundledAnnotation.listOf(Annotation.BACKGROUND_COLOR, HIGHLIGHT_BACKGROUND_COLOR), text);
  }

  private void appendWaveLink(Blip outputBlip, String waveId, String text) {
    BlipContentRefs.all(outputBlip).insertAfter(
        BundledAnnotation.listOf(Annotation.WAVE_LINK, waveId), text);
  }

  private void appendNormal(Blip outputBlip, String text) {

    if (text.length() > 0) {
      int startIndex = outputBlip.length();
      outputBlip.append(text);
      int endIndex = outputBlip.length();
      BlipContentRefs.range(outputBlip, startIndex, endIndex)
          .clearAnnotation(Annotation.BACKGROUND_COLOR).clearAnnotation(Annotation.WAVE_LINK);
    }

    return;
  }
}
