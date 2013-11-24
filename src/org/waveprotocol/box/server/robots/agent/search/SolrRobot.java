package org.waveprotocol.box.server.robots.agent.search;


import com.google.common.collect.Lists;
import com.google.common.collect.Multimap;
import com.google.inject.Inject;
import com.google.inject.Injector;
import com.google.inject.Singleton;
import com.google.wave.api.Annotation;
import com.google.wave.api.Annotations;
import com.google.wave.api.Blip;
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

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.ParseException;
import org.apache.commons.httpclient.HttpClient;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.StringEntity;
import org.waveprotocol.box.common.DocumentConstants;
import org.waveprotocol.box.server.robots.agent.AbstractCliRobotAgent;
import org.waveprotocol.box.server.waveserver.PerUserWaveViewProvider;
import org.waveprotocol.box.server.waveserver.WaveMap;
import org.waveprotocol.box.server.waveserver.WaveletContainer;
import org.waveprotocol.box.server.waveserver.WaveletStateException;
import org.waveprotocol.wave.model.document.operation.AnnotationBoundaryMap;
import org.waveprotocol.wave.model.document.operation.Attributes;
import org.waveprotocol.wave.model.document.operation.AttributesUpdate;
import org.waveprotocol.wave.model.document.operation.DocInitializationCursor;
import org.waveprotocol.wave.model.document.operation.DocOp;
import org.waveprotocol.wave.model.document.operation.DocOpCursor;
import org.waveprotocol.wave.model.document.operation.impl.InitializationCursorAdapter;
import org.waveprotocol.wave.model.id.WaveId;
import org.waveprotocol.wave.model.id.WaveletId;
import org.waveprotocol.wave.model.id.WaveletName;
import org.waveprotocol.wave.model.wave.InvalidParticipantAddress;
import org.waveprotocol.wave.model.wave.ParticipantId;
import org.waveprotocol.wave.model.wave.data.BlipData;
import org.waveprotocol.wave.model.wave.data.ObservableWaveletData;
import org.waveprotocol.wave.model.wave.data.ReadableBlipData;
import org.waveprotocol.wave.model.wave.data.WaveViewData;
import org.waveprotocol.wave.model.wave.data.impl.WaveViewDataImpl;
import org.waveprotocol.wave.util.logging.Log;

import java.io.UnsupportedEncodingException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.Iterator;
import java.util.List;
import java.util.logging.Level;

@SuppressWarnings("serial")
@Singleton
public class SolrRobot extends AbstractCliRobotAgent {


  // private static final Logger LOG =
  // Logger.getLogger(SolrRobot.class.getName());
  private static final Log LOG = Log.get(SolrRobot.class);

  public static final String ROBOT_URI = AGENT_PREFIX_URI + "/search/solr";

  private final PerUserWaveViewProvider waveViewProvider;
  private final WaveMap waveMap;

  @Inject
  public SolrRobot(Injector injector, PerUserWaveViewProvider userWaveViewProvider, WaveMap waveMap) {
    super(injector);
    this.waveViewProvider = userWaveViewProvider;
    this.waveMap = waveMap;
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
    event.getWavelet().reply("\nHello wave!");
  }

  @Override
  protected String getRobotProfilePageUrl() {
    return "http://localhost:8983/solr/";
  }

  @Override
  protected String getRobotAvatarUrl() {
    return "http://localhost:8983/solr/img/solr.png";
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
    String modifiedBy = event.getModifiedBy();

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
        int start = a.getRange().getStart();
        String message = content.substring(0, start);
        if (message.endsWith("\n")) {
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
          CommandLine commandLine = null;
          try {
            commandLine = preprocessCommand(message);
          } catch (IllegalArgumentException e) {
            appendLine(blip.insertInlineBlip(start - 1), e.getMessage());
          }
          if (commandLine != null) {
            if (commandLine.hasOption("help")
                || /* Or if only options */(commandLine.getArgs().length
                    - commandLine.getOptions().length <= 1)) {
              appendLine(blip.insertInlineBlip(start - 1), getFullDescription());
            } else {
              // /*
              // * XXX only listens to the creator
              // */
              // if (modifiedBy.equals(wavelet.getCreator())) {
              Wavelet wavelet = event.getWavelet();
              /*
               * XXX the creator will be aware of the query
               */
              String robotMessage;
              if (wavelet.getParticipants().contains(wavelet.getCreator())) {
                robotMessage = maybeExecuteCommand(commandLine, modifiedBy);
                appendLine(blip.insertInlineBlip(start - 1), robotMessage);
              } else {
                robotMessage = "This wave wasn't created by you. To execute solr commands, invite me, solr-bot, to a wave created by you.";
                appendLine(
                    blip.insertInlineBlip(start - 1),
                    robotMessage);
              }
            }
          }
        }
      }
    }

    return;
  }

  private void appendLine(Blip blip, String message) {
    // blip.reply().append(new Markup(message));
    // blip.appendMarkup(message);
    // blip.continueThread().appendMarkup(message);
    // blip.insertInlineBlip(6).appendMarkup(message);
    // Annotations annotations = blip.getAnnotations();
    blip.appendMarkup(message);
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
    // TODO Auto-generated method stub
    super.onWaveletSelfRemoved(event);
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

  @Override
  protected String maybeExecuteCommand(CommandLine commandLine, String modifiedBy) {

    String robotMessage;

    String[] args = commandLine.getArgs();
    if ("search".equals(args[1])) {
      robotMessage = "hello <a href='/'>wave</a>";
    } else if ("update".equals(args[1])) {
      robotMessage = update(modifiedBy);
    } else {
      robotMessage = null;
    }

    return robotMessage;
  }

  private String update(String modifiedBy) {

    String robotMessage = null;

    try {
      Multimap<WaveId, WaveletId> currentUserWavesView =
          waveViewProvider.retrievePerUserWaveView(ParticipantId.of(modifiedBy));
      // Loop over the user waves view.
      for (WaveId waveId : currentUserWavesView.keySet()) {
        WaveViewData view = null;
        for (WaveletId waveletId : currentUserWavesView.get(waveId)) {
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
          // look at the user's wave view and determine if the view matches
          // the query.
          try {
            // if (waveletContainer == null ||
            // !waveletContainer.applyFunction(matchesFunction)) {
            // LOG.fine("----doesn't match: " + waveletContainer);
            // continue;
            // }
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
          HttpClient httpClient = new HttpClient();
          // results.put(waveId, view);
          for (ObservableWaveletData waveletData : view.getWavelets()) {
            System.out.println("waveId = " + waveletData.getWaveId());
            for (String docName : waveletData.getDocumentIds()) {
              BlipData document = waveletData.getDocument(docName);
              System.out.println("docId = " + document.getId());
              DocOp docOp = document.getContent().asOperation();
              String x = x(docOp, waveletData);
              System.out.println(x);
//              /*
//               * TODO update solr index with wave id, wavelet id, blip id, and
//               * content TODO solr doc id should be bookmarkerable, i.e. wave id
//               * (?)
//               */
//              HttpPost httpPost =
//                  new HttpPost("http://localhost:8983/solr/update/json?commit=true");
//              httpPost.setHeader("Content-Type", "application/json");
//              httpPost.setEntity(new StringEntity("{'waveId':''}"));
            }
          }
        }
      }
      robotMessage = "hello <a href='/'>wave</a>";
    } catch (InvalidParticipantAddress e) {
      robotMessage = e.getMessage();
      LOG.log(Level.SEVERE, "userId: " + modifiedBy, e);
//    } catch (UnsupportedEncodingException e) {
//      robotMessage = e.getMessage();
//      LOG.log(Level.SEVERE, "userId: " + modifiedBy, e);
    }

    return robotMessage;
  }

  private String x(DocOp docOp, final ObservableWaveletData wavelet) {

    final StringBuilder sb = new StringBuilder();
    sb.append(collateTextForOps(Lists.newArrayList(docOp)));
    sb.append(" ");
    docOp.apply(InitializationCursorAdapter.adapt(new DocInitializationCursor() {
      @Override
      public void annotationBoundary(AnnotationBoundaryMap map) {
      }

      @Override
      public void characters(String chars) {
        // No chars in the conversation manifest
      }

      @Override
      public void elementEnd() {
      }

      @Override
      public void elementStart(String type, Attributes attrs) {
        // if (sb.length() >= maxSnippetLength) {
        // return;
        // }

        if (DocumentConstants.BLIP.equals(type)) {
          String blipId = attrs.get(DocumentConstants.BLIP_ID);
          if (blipId != null) {
            ReadableBlipData document = wavelet.getDocument(blipId);
            if (document == null) {
              // We see this when a blip has been deleted
              return;
            }
            sb.append(collateTextForDocuments(Arrays.asList(document)));
            sb.append(" ");
          }
        }
      }
    }));

    return sb.toString();
  }

  /*
   * copied from Snippets
   */
  /**
   * Concatenates all of the text of the specified blips into a single String.
   * 
   * @param documents the documents to concatenate.
   * @return A String containing the characters from all documents.
   */
  private String collateTextForDocuments(Iterable<? extends ReadableBlipData> documents) {
    ArrayList<DocOp> docOps = new ArrayList<DocOp>();
    for (ReadableBlipData blipData : documents) {
      docOps.add(blipData.getContent().asOperation());
    }
    return collateTextForOps(docOps);
  }

  /*
   * copied from Snippets with slight modifications
   */
  /**
   * Concatenates all of the text of the specified docops into a single String.
   * 
   * @param documentops the document operations to concatenate.
   * @return A String containing the characters from the operations.
   */
  private String collateTextForOps(Iterable<DocOp> documentops) {
    final StringBuilder resultBuilder = new StringBuilder();
    for (DocOp docOp : documentops) {
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
    }
    return resultBuilder.toString().trim();
  }

  @Override
  public String getShortDescription() {
    return "short desc";
  }

  @Override
  public String getFullDescription() {
    return "full desc";
  }

  @Override
  public String getCommandName() {
    return "solr";
  }

  @Override
  public String getCmdLineSyntax() {
    return "update|search";
  }

  @Override
  public String getExample() {
    return "example";
  }

  @Override
  public int getMinNumOfArguments() {
    return 0;
  }

  @Override
  public int getMaxNumOfArguments() {
    return Integer.MAX_VALUE;
  }

  @Override
  protected CommandLine preprocessCommand(String lastLine) throws IllegalArgumentException {

    CommandLine commandLine = null;
    try {
      commandLine = parse(lastLine.split(" "));
    } catch (ParseException e) {
      throw new IllegalArgumentException(e);
    }
    String[] args = commandLine.getArgs();
    if (!args[0].equals(getCommandName())) {
      return null;
    }
    int argsNum = args.length - commandLine.getOptions().length - 1;
    // If there are only options in the command - then it is also invalid and
    // have to display usage anyway.
    if ((argsNum > 0) && (argsNum < getMinNumOfArguments() || argsNum > getMaxNumOfArguments())) {
      String message = null;
      if (getMinNumOfArguments() == getMaxNumOfArguments()) {
        message =
            String.format("Invalid number of arguments. Expected: %d , actual: %d %s",
                getMinNumOfArguments(), argsNum, getUsage());
      } else {
        message =
            String.format(
                "Invalid number of arguments. Expected between %d and %d, actual: %d. %s",
                getMinNumOfArguments(), getMaxNumOfArguments(), argsNum, getUsage());
      }
      throw new IllegalArgumentException(message);
    }

    return commandLine;
  }

}
