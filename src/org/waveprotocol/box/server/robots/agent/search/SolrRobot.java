package org.waveprotocol.box.server.robots.agent.search;


import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.inject.Inject;
import com.google.inject.Injector;
import com.google.inject.Singleton;
import com.google.wave.api.Annotation;
import com.google.wave.api.Annotations;
import com.google.wave.api.Blip;
import com.google.wave.api.BlipContentRefs;
import com.google.wave.api.Range;
import com.google.wave.api.Wavelet;
import com.google.wave.api.event.AnnotatedTextChangedEvent;
import com.google.wave.api.event.BlipContributorsChangedEvent;
import com.google.wave.api.event.BlipSubmittedEvent;
import com.google.wave.api.event.DocumentChangedEvent;
import com.google.wave.api.event.FormButtonClickedEvent;
import com.google.wave.api.event.GadgetStateChangedEvent;
import com.google.wave.api.event.OperationErrorEvent;
import com.google.wave.api.event.WaveletBlipCreatedEvent;
import com.google.wave.api.event.WaveletBlipRemovedEvent;
import com.google.wave.api.event.WaveletCreatedEvent;
import com.google.wave.api.event.WaveletFetchedEvent;
import com.google.wave.api.event.WaveletParticipantsChangedEvent;
import com.google.wave.api.event.WaveletSelfAddedEvent;
import com.google.wave.api.event.WaveletSelfRemovedEvent;
import com.google.wave.api.event.WaveletTagsChangedEvent;
import com.google.wave.api.event.WaveletTitleChangedEvent;

import org.apache.commons.httpclient.HttpClient;
import org.apache.commons.httpclient.URI;
import org.apache.commons.httpclient.methods.GetMethod;
import org.apache.http.HttpStatus;
import org.waveprotocol.box.server.robots.agent.AbstractBaseRobotAgent;
import org.waveprotocol.box.server.waveserver.SolrSearchProviderImpl;
import org.waveprotocol.wave.util.logging.Log;

import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Date;
import java.util.Iterator;
import java.util.List;

/**
 * Robot that offers full text search
 * 
 * @author Frank R. <renfeng.cn@gmail.com>
 */
@SuppressWarnings("serial")
@Singleton
public class SolrRobot extends AbstractBaseRobotAgent {

  /*-
   * http://wiki.apache.org/solr/HighlightingParameters#hl.simple.pre.2Fhl.simple.post
   */
  private static final String PRE_TAG = "<em>";
  private static final String POST_TAG = "</em>";
  private static final int PRE_TAG_LENGTH = PRE_TAG.length();
  private static final int POST_TAG_LENGTH = POST_TAG.length();

  // private static final Logger LOG =
  // Logger.getLogger(SolrRobot.class.getName());
  private static final Log LOG = Log.get(SolrRobot.class);

  public static final String ROBOT_URI = AGENT_PREFIX_URI + "/search/solr";

  // private final PerUserWaveViewProvider waveViewProvider;
  // private final WaveMap waveMap;

  @Inject
  public SolrRobot(Injector injector) {
    super(injector);
    // this.waveViewProvider = userWaveViewProvider;
    // this.waveMap = waveMap;
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
  public void onBlipSubmitted(BlipSubmittedEvent event) {
    event.getWavelet().reply("\nBlip submitted!");
  }

  @Override
  public void onWaveletSelfAdded(WaveletSelfAddedEvent event) {
    event.getWavelet().reply("\nHello!");
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
  public void onAnnotatedTextChanged(AnnotatedTextChangedEvent event) {
    // TODO Auto-generated method stub
    super.onAnnotatedTextChanged(event);
  }

  @Override
  public void onBlipContributorsChanged(BlipContributorsChangedEvent event) {
    // TODO Auto-generated method stub
    super.onBlipContributorsChanged(event);
  }

  @Override
  public void onDocumentChanged(DocumentChangedEvent event) {

    Blip blip = event.getBlip();
    // String modifiedBy = event.getModifiedBy();

    /*-
     * http://wave-protocol.googlecode.com/hg/spec/conversation/convspec.html
     */
    long now = new Date().getTime();
    ArrayList<String> activeBlinkyBits = new ArrayList<String>();
    Annotations annotations = blip.getAnnotations();
    Iterator<Annotation> annotationIterator = annotations.iterator();
    while (annotationIterator.hasNext()) {
      Annotation annotation = annotationIterator.next();
      String annotationKey = annotation.getName();
      if (annotationKey.startsWith("user/d/")) {
        String[] values = annotation.getValue().split(",");
        long timestamp = Long.parseLong(values[1]);
        if (now - timestamp <= 3000) {
          activeBlinkyBits.add(annotationKey.replaceFirst("user/d/", "user/e/"));
        }
      }
    }
    String content = blip.getContent();
    for (String annotationKey : activeBlinkyBits) {
      List<Annotation> list = annotations.get(annotationKey);
      for (Annotation a : list) {
        Range range = a.getRange();
        int start = range.getStart();
        String message = content.substring(0, start);
        /*
         * removed "start == range.getEnd()" to allow query at any line (even if
         * it's not the last line)
         */
        if (message.endsWith("\n") && start == range.getEnd()) {
          Blip outputBlip = blip.insertInlineBlip(start - 1);

          message = message.substring(0, message.length() - 1);

          /*
           * XXX the commented code does work as expected
           */
          // int endOfPreviousLine = blip.getContent().lastIndexOf("\n", start -
          // 1);
          int endOfPreviousLine = message.lastIndexOf("\n");
          if (endOfPreviousLine != -1) {
            message = message.substring(endOfPreviousLine + 1);
          }

          // /*
          // * XXX only listens to the creator
          // */
          // if (modifiedBy.equals(wavelet.getCreator())) {
          Wavelet wavelet = event.getWavelet();
          /*
           * XXX the creator will be aware of the query
           */
          String creator = wavelet.getCreator();
          if (wavelet.getParticipants().contains(creator)) {
            search(message, creator, outputBlip);
          } else {
            String robotMessage =
                "Search is allowed only when the creator of the wave is currently a participant.";
            outputBlip.append(robotMessage);
          }

          /*
           * XXX if only the last line is accepted, we can quit the loop
           */
          // break;
        }
      }
    }

    return;
  }

  @Override
  public void onFormButtonClicked(FormButtonClickedEvent event) {
    // TODO Auto-generated method stub
    super.onFormButtonClicked(event);
  }

  @Override
  public void onGadgetStateChanged(GadgetStateChangedEvent event) {
    // TODO Auto-generated method stub
    super.onGadgetStateChanged(event);
  }

  @Override
  public void onWaveletBlipCreated(WaveletBlipCreatedEvent event) {
    // TODO Auto-generated method stub
    super.onWaveletBlipCreated(event);
  }

  @Override
  public void onWaveletBlipRemoved(WaveletBlipRemovedEvent event) {
    // TODO Auto-generated method stub
    super.onWaveletBlipRemoved(event);
  }

  @Override
  public void onWaveletCreated(WaveletCreatedEvent event) {
    // TODO Auto-generated method stub
    super.onWaveletCreated(event);
  }

  @Override
  public void onWaveletFetched(WaveletFetchedEvent event) {
    // TODO Auto-generated method stub
    super.onWaveletFetched(event);
  }

  @Override
  public void onWaveletParticipantsChanged(WaveletParticipantsChangedEvent event) {
    // TODO Auto-generated method stub
    super.onWaveletParticipantsChanged(event);
  }

  @Override
  public void onWaveletSelfRemoved(WaveletSelfRemovedEvent event) {
    event.getWavelet().reply("\nGoodbye!");
  }

  @Override
  public void onWaveletTagsChanged(WaveletTagsChangedEvent event) {
    // TODO Auto-generated method stub
    super.onWaveletTagsChanged(event);
  }

  @Override
  public void onWaveletTitleChanged(WaveletTitleChangedEvent event) {
    // TODO Auto-generated method stub
    super.onWaveletTitleChanged(event);
  }

  @Override
  public void onOperationError(OperationErrorEvent event) {
    // TODO Auto-generated method stub
    super.onOperationError(event);
  }

  private void search(String query, String creator, Blip outputBlip) {

    if (query.length() <= 0) {
      /*
       * ignore empty query
       */
      return;
    }

    String outputWaveId = outputBlip.getWaveId().serialise();

    // StringBuilder messageBuilder = new StringBuilder();
    // messageBuilder.append("hello <a href='/'>wave</a>");

    /*
     * XXX will it be better to replace lucene with edismax?
     */
    String userQuery = SolrSearchProviderImpl.buildUserQuery(query);
    String fq = SolrSearchProviderImpl.FILTER_QUERY_PREFIX + creator + " AND (" + userQuery + ")";

    int start = 0;
    int rows = 10;
    int count = 0;

    GetMethod getMethod = new GetMethod();
    try {
      while (true) {
        /*-
         * http://wiki.apache.org/solr/HighlightingParameters
         */
        getMethod.setURI(new URI(SolrSearchProviderImpl.SOLR_BASE_URL + "/select?wt=json"
            + "&hl=true&hl.fl=text_t&hl.q=" + userQuery + "&start=" + start + "&rows=" + rows
            + "&q=" + SolrSearchProviderImpl.Q + "&fq=" + fq, false));

        HttpClient httpClient = new HttpClient();
        int statusCode = httpClient.executeMethod(getMethod);
        if (statusCode != HttpStatus.SC_OK) {
          LOG.warning("Failed to execute query: " + query);
          // return "Failed to execute query: " + query;
          outputBlip.append("Failed to execute query: " + query);
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

        /*
         * TODO insert at the beginning the number of waves found
         */
        // outputBlip.append("Found: " +
        // responseJson.getAsJsonPrimitive("numFound") + "\n");

        Iterator<JsonElement> docJsonIterator = docsJson.iterator();
        while (docJsonIterator.hasNext()) {
          JsonObject docJson = docJsonIterator.next().getAsJsonObject();
          String id = docJson.getAsJsonPrimitive("id").getAsString();
          if (id.startsWith(outputWaveId)) {
            continue;
          }

          count++;

          JsonObject snippetJson = highlighting.getAsJsonObject(id);
          JsonArray textsJson = snippetJson.getAsJsonArray("text_t");
          Iterator<JsonElement> textJsonIterator = textsJson.iterator();
          while (textJsonIterator.hasNext()) {
            JsonElement textJson = textJsonIterator.next();
            String snippet = textJson.getAsString().trim();
            // String result =
            // "<p><a href='/#" + id + "'>wave://" + id + "</a><br/>" + snippet
            // + "</p>";
            outputBlip.append("\n" + count + " ");

            int linkStartIndex = outputBlip.getContent().length();
            outputBlip.append("wave://" + id);
            int linkEndIndex = outputBlip.getContent().length();
            outputBlip.append("\n");
            BlipContentRefs.range(outputBlip, linkStartIndex, linkEndIndex).annotate("link/wave",
                id);

            int lastTag = -1;
            int highlightStartIndex = -1;
            int highlightEndIndex = -1;
            while (true) {
              int preTag = snippet.indexOf(PRE_TAG, lastTag);
              if (preTag != -1) {
                int postTag = snippet.indexOf(POST_TAG, preTag + PRE_TAG_LENGTH);
                if (lastTag != -1) {
                  outputBlip.append(snippet.substring(lastTag, preTag));
                  BlipContentRefs.range(outputBlip, highlightStartIndex, highlightEndIndex)
                      .annotate("style/backgroundColor", "rgb(255,255,0)");
                } else {
                  outputBlip.append(snippet.substring(0, preTag));
                }
                highlightStartIndex = outputBlip.getContent().length();
                outputBlip.append(snippet.substring(preTag + PRE_TAG_LENGTH, postTag));
                highlightEndIndex = outputBlip.getContent().length();
                lastTag = postTag + POST_TAG_LENGTH;
              } else {
                if (lastTag != -1) {
                  outputBlip.append(snippet.substring(lastTag));
                  BlipContentRefs.range(outputBlip, highlightStartIndex, highlightEndIndex)
                      .annotate("style/backgroundColor", "rgb(255,255,0)");
                } else {
                  outputBlip.append(snippet);
                }
                break;
              }

            }

            outputBlip.append("\n");

          }
        }

        if (docsJson.size() < rows) {
          break;
        }

        start += rows;
      }

      BlipContentRefs.range(outputBlip, 0, 1).insert("Found: " + count);

    } catch (IOException e) {
      LOG.warning("Failed to execute query: " + query);
      // return "Failed to execute query: " + query;
      outputBlip.append("Failed to execute query: " + query);
      return;
    } finally {
      getMethod.releaseConnection();
    }

    // return messageBuilder.toString();
    return;
  }

  // private String update(String modifiedBy) {
  //
  // String robotMessage = null;
  //
  // try {
  // Multimap<WaveId, WaveletId> currentUserWavesView =
  // waveViewProvider.retrievePerUserWaveView(ParticipantId.of(modifiedBy));
  // // Loop over the user waves view.
  // for (WaveId waveId : currentUserWavesView.keySet()) {
  // WaveViewData view = null;
  // for (WaveletId waveletId : currentUserWavesView.get(waveId)) {
  // WaveletContainer waveletContainer = null;
  // WaveletName waveletname = WaveletName.of(waveId, waveletId);
  //
  // // TODO (alown): Find some way to use isLocalWavelet to do this
  // // properly!
  // try {
  // if (LOG.isFineLoggable()) {
  // LOG.fine("Trying as a remote wavelet");
  // }
  // waveletContainer = waveMap.getRemoteWavelet(waveletname);
  // } catch (WaveletStateException e) {
  // LOG.severe(String.format("Failed to get remote wavelet %s",
  // waveletname.toString()), e);
  // } catch (NullPointerException e) {
  // // This is a fairly normal case of it being a local-only wave.
  // // Yet this only seems to appear in the test suite.
  // // Continuing is completely harmless here.
  // LOG.info(
  // String.format("%s is definitely not a remote wavelet. (Null key)",
  // waveletname.toString()), e);
  // }
  //
  // if (waveletContainer == null) {
  // try {
  // if (LOG.isFineLoggable()) {
  // LOG.fine("Trying as a local wavelet");
  // }
  // waveletContainer = waveMap.getLocalWavelet(waveletname);
  // } catch (WaveletStateException e) {
  // LOG.severe(String.format("Failed to get local wavelet %s",
  // waveletname.toString()), e);
  // }
  // }
  //
  // // TODO (Yuri Z.) This loop collects all the wavelets that match the
  // // query, so the view is determined by the query. Instead we should
  // // look at the user's wave view and determine if the view matches
  // // the query.
  // try {
  // // if (waveletContainer == null ||
  // // !waveletContainer.applyFunction(matchesFunction)) {
  // // LOG.fine("----doesn't match: " + waveletContainer);
  // // continue;
  // // }
  // if (view == null) {
  // view = WaveViewDataImpl.create(waveId);
  // }
  // // Just keep adding all the relevant wavelets in this wave.
  // view.addWavelet(waveletContainer.copyWaveletData());
  // } catch (WaveletStateException e) {
  // LOG.warning("Failed to access wavelet " +
  // waveletContainer.getWaveletName(), e);
  // }
  // }
  // if (view != null) {
  // HttpClient httpClient = new HttpClient();
  // // results.put(waveId, view);
  // for (ObservableWaveletData waveletData : view.getWavelets()) {
  // System.out.println("waveId = " + waveletData.getWaveId());
  // for (String docName : waveletData.getDocumentIds()) {
  // BlipData document = waveletData.getDocument(docName);
  // System.out.println("docId = " + document.getId());
  // DocOp docOp = document.getContent().asOperation();
  // String x = x(docOp, waveletData);
  // System.out.println(x);
  // // /*
  // // * TODO update solr index with wave id, wavelet id, blip id, and
  // // * content TODO solr doc id should be bookmarkerable, i.e. wave id
  // // * (?)
  // // */
  // // HttpPost httpPost =
  // // new HttpPost(SolrPerUserWaveViewHandlerImpl.SOLR_BASE_URL +
  // // "/update/json?commit=true");
  // // httpPost.setHeader("Content-Type", "application/json");
  // // httpPost.setEntity(new StringEntity("{'waveId':''}"));
  // }
  // }
  // }
  // }
  // robotMessage = "hello <a href='/'>wave</a>";
  // } catch (InvalidParticipantAddress e) {
  // robotMessage = e.getMessage();
  // LOG.log(Level.SEVERE, "userId: " + modifiedBy, e);
  // // } catch (UnsupportedEncodingException e) {
  // // robotMessage = e.getMessage();
  // // LOG.log(Level.SEVERE, "userId: " + modifiedBy, e);
  // }
  //
  // return robotMessage;
  // }

  // private String x(DocOp docOp, final ObservableWaveletData wavelet) {
  //
  // final StringBuilder sb = new StringBuilder();
  // sb.append(collateTextForOps(Lists.newArrayList(docOp)));
  // sb.append(" ");
  // docOp.apply(InitializationCursorAdapter.adapt(new DocInitializationCursor()
  // {
  // @Override
  // public void annotationBoundary(AnnotationBoundaryMap map) {
  // }
  //
  // @Override
  // public void characters(String chars) {
  // // No chars in the conversation manifest
  // }
  //
  // @Override
  // public void elementEnd() {
  // }
  //
  // @Override
  // public void elementStart(String type, Attributes attrs) {
  // // if (sb.length() >= maxSnippetLength) {
  // // return;
  // // }
  //
  // if (DocumentConstants.BLIP.equals(type)) {
  // String blipId = attrs.get(DocumentConstants.BLIP_ID);
  // if (blipId != null) {
  // ReadableBlipData document = wavelet.getDocument(blipId);
  // if (document == null) {
  // // We see this when a blip has been deleted
  // return;
  // }
  // sb.append(collateTextForDocuments(Arrays.asList(document)));
  // sb.append(" ");
  // }
  // }
  // }
  // }));
  //
  // return sb.toString();
  // }
  //
  // /*
  // * copied from Snippets
  // */
  // /**
  // * Concatenates all of the text of the specified blips into a single String.
  // *
  // * @param documents the documents to concatenate.
  // * @return A String containing the characters from all documents.
  // */
  // private String collateTextForDocuments(Iterable<? extends ReadableBlipData>
  // documents) {
  // ArrayList<DocOp> docOps = new ArrayList<DocOp>();
  // for (ReadableBlipData blipData : documents) {
  // docOps.add(blipData.getContent().asOperation());
  // }
  // return collateTextForOps(docOps);
  // }
  //
  // /*
  // * copied from Snippets with slight modifications
  // */
  // /**
  // * Concatenates all of the text of the specified docops into a single
  // String.
  // *
  // * @param documentops the document operations to concatenate.
  // * @return A String containing the characters from the operations.
  // */
  // private String collateTextForOps(Iterable<DocOp> documentops) {
  // final StringBuilder resultBuilder = new StringBuilder();
  // for (DocOp docOp : documentops) {
  // docOp.apply(InitializationCursorAdapter.adapt(new DocOpCursor() {
  // @Override
  // public void characters(String s) {
  // resultBuilder.append(s);
  // }
  //
  // @Override
  // public void annotationBoundary(AnnotationBoundaryMap map) {
  // }
  //
  // @Override
  // public void elementStart(String type, Attributes attrs) {
  // }
  //
  // @Override
  // public void elementEnd() {
  // }
  //
  // @Override
  // public void retain(int itemCount) {
  // }
  //
  // @Override
  // public void deleteCharacters(String chars) {
  // }
  //
  // @Override
  // public void deleteElementStart(String type, Attributes attrs) {
  // }
  //
  // @Override
  // public void deleteElementEnd() {
  // }
  //
  // @Override
  // public void replaceAttributes(Attributes oldAttrs, Attributes newAttrs) {
  // }
  //
  // @Override
  // public void updateAttributes(AttributesUpdate attrUpdate) {
  // }
  // }));
  // }
  // return resultBuilder.toString().trim();
  // }
}
