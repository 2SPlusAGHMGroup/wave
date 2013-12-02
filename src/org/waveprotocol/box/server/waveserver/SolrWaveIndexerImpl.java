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

import com.google.common.base.Preconditions;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ListenableFutureTask;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.google.inject.Inject;
import com.google.inject.Singleton;

import org.apache.commons.httpclient.HttpClient;
import org.apache.commons.httpclient.methods.PostMethod;
import org.apache.commons.httpclient.methods.RequestEntity;
import org.apache.commons.httpclient.methods.StringRequestEntity;
import org.apache.http.HttpStatus;
import org.waveprotocol.box.common.DeltaSequence;
import org.waveprotocol.box.common.DocumentConstants;
import org.waveprotocol.wave.model.document.operation.AnnotationBoundaryMap;
import org.waveprotocol.wave.model.document.operation.Attributes;
import org.waveprotocol.wave.model.document.operation.AttributesUpdate;
import org.waveprotocol.wave.model.document.operation.DocOp;
import org.waveprotocol.wave.model.document.operation.DocOpCursor;
import org.waveprotocol.wave.model.document.operation.impl.InitializationCursorAdapter;
import org.waveprotocol.wave.model.id.WaveletName;
import org.waveprotocol.wave.model.version.HashedVersion;
import org.waveprotocol.wave.model.wave.ParticipantId;
import org.waveprotocol.wave.model.wave.data.ReadableBlipData;
import org.waveprotocol.wave.model.wave.data.ReadableWaveletData;

import java.io.IOException;
import java.util.concurrent.Callable;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * @author Frank R. <renfeng.cn@gmail.com>
 */
@Singleton
public class SolrWaveIndexerImpl extends AbstractWaveIndexer implements WaveBus.Subscriber,
    PerUserWaveViewBus.Listener {

  private static final Logger LOG = Logger.getLogger(SolrWaveIndexerImpl.class.getName());

  // TODO (Yuri Z.): Inject executor.
  private static final Executor executor = Executors.newSingleThreadExecutor();

  // private final PerUserWaveViewBus.Listener listener;
  private final ReadableWaveletDataProvider waveletDataProvider;

  /**
   * Concatenates all of the text of the specified docops into a single String.
   * 
   * @param documentops the document operations to concatenate.
   * @return A String containing the characters from the operations.
   */
  public static String readText(ReadableBlipData doc) {

    final StringBuilder resultBuilder = new StringBuilder();

    DocOp docOp = doc.getContent().asOperation();
    docOp.apply(InitializationCursorAdapter.adapt(new DocOpCursor() {
      @Override
      public void characters(String s) {
        resultBuilder.append(s);
      }

      @Override
      public void annotationBoundary(AnnotationBoundaryMap map) {
      }

      @Override
      public void elementStart(String type, Attributes attrs) {
        if (type.equals(DocumentConstants.LINE)) {
          resultBuilder.append("\n");
        }
      }

      @Override
      public void elementEnd() {
      }

      @Override
      public void retain(int itemCount) {
      }

      @Override
      public void deleteCharacters(String chars) {
      }

      @Override
      public void deleteElementStart(String type, Attributes attrs) {
      }

      @Override
      public void deleteElementEnd() {
      }

      @Override
      public void replaceAttributes(Attributes oldAttrs, Attributes newAttrs) {
      }

      @Override
      public void updateAttributes(AttributesUpdate attrUpdate) {
      }
    }));

    return resultBuilder.toString();
  }

  @Inject
  public SolrWaveIndexerImpl(WaveMap waveMap, WaveletProvider waveletProvider,
      ReadableWaveletDataProvider waveletDataProvider,
      WaveletNotificationDispatcher notificationDispatcher) {
    super(waveMap, waveletProvider);
    this.waveletDataProvider = waveletDataProvider;
    notificationDispatcher.subscribe(this);
  }

  @Override
  public ListenableFuture<Void> onParticipantAdded(final WaveletName waveletName,
      ParticipantId participant) {
    /*
     * XXX ignored. See waveletCommitted(WaveletName, HashedVersion)
     */
    return null;
  }

  @Override
  public ListenableFuture<Void> onParticipantRemoved(final WaveletName waveletName,
      ParticipantId participant) {
    /*
     * XXX ignored. See waveletCommitted(WaveletName, HashedVersion)
     */
    return null;
  }

  @Override
  public ListenableFuture<Void> onWaveInit(final WaveletName waveletName) {

    ListenableFutureTask<Void> task = new ListenableFutureTask<Void>(new Callable<Void>() {

      @Override
      public Void call() throws Exception {
        ReadableWaveletData waveletData;
        try {
          waveletData = waveletDataProvider.getReadableWaveletData(waveletName);
          updateIndex(waveletData);
        } catch (WaveServerException e) {
          LOG.log(Level.SEVERE, "Failed to initialize index for " + waveletName, e);
          throw e;
        }
        return null;
      }
    });
    executor.execute(task);
    return task;
  }

  @Override
  protected void processWavelet(WaveletName waveletName) {
    onWaveInit(waveletName);
  }

  @Override
  protected void postIndexHook() {
    try {
      getWaveMap().unloadAllWavelets();
    } catch (WaveletStateException e) {
      throw new IndexException("Problem encountered while cleaning up", e);
    }
  }

  private void updateIndex(ReadableWaveletData wavelet) throws IndexException {

    Preconditions.checkNotNull(wavelet);

    /*
     * update solr index
     */

    PostMethod postMethod =
        new PostMethod(SolrSearchProviderImpl.SOLR_BASE_URL + "/update/json?commit=true");
    // postMethod.setRequestHeader("Content-Type", "application/json");
    try {
      JsonArray docsJson = new JsonArray();

      String waveId = wavelet.getWaveId().serialise();
      String waveletId = wavelet.getWaveletId().serialise();
      String modified = Long.toString(wavelet.getLastModifiedTime());

      for (String docName : wavelet.getDocumentIds()) {
        ReadableBlipData document = wavelet.getDocument(docName);

        /*
         * copied the method and disable replacing new lines with whitespaces
         */
        // String text = Snippets.collateTextForWavelet(wavelet);
        String text = readText(document);

        /*
         * XXX i shouldn't skip empty wave/blips
         */
        // if (text.length() == 0) {
        // }

        JsonArray participantsJson = new JsonArray();
        for (ParticipantId participant : wavelet.getParticipants()) {
          String participantAddress = participant.toString();
          participantsJson.add(new JsonPrimitive(participantAddress));
        }

        JsonObject docJson = new JsonObject();
        docJson.addProperty("id", waveId + "/~/conv+root/" + docName);
        docJson.addProperty("waveId_s", waveId);
        docJson.addProperty("waveletId_s", waveletId);
        docJson.addProperty("docName_s", docName);
        docJson.addProperty("lmt_l", modified);
        docJson.add("with_txt", participantsJson);
        docJson.addProperty("text_t", text);
        docJson.addProperty("in_ss", "inbox");

        docsJson.add(docJson);
      }

      RequestEntity requestEntity =
          new StringRequestEntity(docsJson.toString(), "application/json", "UTF-8");
      postMethod.setRequestEntity(requestEntity);

      HttpClient httpClient = new HttpClient();
      int statusCode = httpClient.executeMethod(postMethod);
      if (statusCode != HttpStatus.SC_OK) {
        throw new IndexException(waveId);
      }

      // LOG.fine(postMethod.getResponseBodyAsString());

    } catch (IOException e) {
      throw new IndexException(String.valueOf(wavelet.getWaveletId()), e);
    } finally {
      postMethod.releaseConnection();
    }

    return;
  }

  @Override
  public void waveletUpdate(final ReadableWaveletData wavelet, DeltaSequence deltas) {
    /*-
     * commented out for optimization, see waveletCommitted(WaveletName, HashedVersion)
     */
    // updateIndex(wavelet);
  }

  @Override
  public void waveletCommitted(final WaveletName waveletName, final HashedVersion version) {

    Preconditions.checkNotNull(waveletName);

    /*
     * XXX don't update index here (on current thread) to prevent lock
     */
    ListenableFutureTask<Void> task = new ListenableFutureTask<Void>(new Callable<Void>() {

      @Override
      public Void call() throws Exception {
        ReadableWaveletData waveletData;
        try {
          waveletData = waveletDataProvider.getReadableWaveletData(waveletName);
          System.out.println("commit " + version + " " + waveletData.getVersion());
          if (waveletData.getVersion() == version.getVersion()) {
            updateIndex(waveletData);
          }
        } catch (WaveServerException e) {
          LOG.log(Level.SEVERE, "Failed to update index for " + waveletName, e);
          throw e;
        }
        return null;
      }
    });
    executor.execute(task);

    return;
  }
}
