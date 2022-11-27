/*
 * Copyright 2018 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy of this
 * software and associated documentation files (the "Software"), to deal in the Software
 * without restriction, including without limitation the rights to use, copy, modify,
 * merge, publish, distribute, sublicense, and/or sell copies of the Software, and to
 * permit persons to whom the Software is furnished to do so.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED,
 * INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A
 * PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT
 * HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION
 * OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE
 * SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */

package org.thon.transcribestreaming;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.Mixer;
import javax.sound.sampled.TargetDataLine;
import javax.swing.JComboBox;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.plaf.DimensionUIResource;

import javafx.application.Platform;
import javafx.beans.value.ChangeListener;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.layout.Background;
import javafx.scene.layout.BackgroundFill;
import javafx.scene.layout.CornerRadii;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.GridPane;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.Text;
import javafx.stage.Screen;
import javafx.stage.Stage;
import software.amazon.awssdk.services.transcribestreaming.model.Result;
import software.amazon.awssdk.services.transcribestreaming.model.StartStreamTranscriptionResponse;
import software.amazon.awssdk.services.transcribestreaming.model.TranscriptEvent;
import software.amazon.awssdk.services.transcribestreaming.model.TranscriptResultStream;

/**
 * This class primarily controls the GUI for this application. Most of the code relevant to starting and working
 * with our streaming API can be found in TranscribeStreamingClientWrapper.java, with the exception of some result
 * parsing logic in this classes method getResponseHandlerForWindow()
 */
public class FullScreenWindowController {

    private static final int MAX_TEXT_LENGTH = 100;
    private static final int TEXT_SIZE = 100;
    private static final int SECONDS_TO_DISAPPEAR = 3;

    private TranscribeStreamingClientWrapper client;
    private TranscribeStreamingSynchronousClient synchronousClient;
    private Text outputText;
    private Button startStopMicButton;
    // private Button fileStreamButton;
    private Button exitButton;
    private ComboBox<Mixer.Info> audioInputSelect;
    // private Button saveButton;
    // private TextArea finalTextArea;
    private CompletableFuture<Void> inProgressStreamingRequest;
    private String finalTranscript = "";
    private Date lastUpdatedTime;
    private Stage primaryStage;
    private ScheduledExecutorService scheduler;

    public FullScreenWindowController(Stage primaryStage) {
        this.primaryStage = primaryStage;
        initializeWindow(primaryStage);
        client = new TranscribeStreamingClientWrapper(getSelectedAudioInput());
        synchronousClient = new TranscribeStreamingSynchronousClient(TranscribeStreamingClientWrapper.getClient());
        startTimeoutThread();
    }

    public void startTimeoutThread() {
        scheduler = Executors.newScheduledThreadPool(1);
        Runnable toRun = new Runnable() {
            public void run() {
                if (lastUpdatedTime != null) {
                    long diffInMillies = Math.abs(new Date().getTime() - lastUpdatedTime.getTime());
                    long diff = TimeUnit.SECONDS.convert(diffInMillies, TimeUnit.MILLISECONDS);
                    System.out.println("Checking (diff = " + String.valueOf(diff) + ")");
                    if (diff > SECONDS_TO_DISAPPEAR) {
                        Platform.runLater(() -> {
                            outputText.setText("");
                        });
                    }
                }
            }
        };
        scheduler.scheduleAtFixedRate(toRun, 1, 1, TimeUnit.SECONDS);
    }

    public void close() {
        stopTranscription();
        scheduler.shutdown();
        if (inProgressStreamingRequest != null) {
            inProgressStreamingRequest.completeExceptionally(new InterruptedException());
        }
        client.close();
    }

    private void startTranscriptionRequest(File inputFile) {
        if (inProgressStreamingRequest == null) {
            finalTranscript = "";
            startStopMicButton.setText("Connecting...");
            startStopMicButton.setDisable(true);
            audioInputSelect.setDisable(true);
            outputText.setText("");
            // TODO: Make it so old text doesn't show up
            inProgressStreamingRequest = client.startTranscription(getResponseHandlerForWindow(), inputFile);
            inProgressStreamingRequest.handle((result, exc) -> {
                if (exc != null) {
                    JOptionPane.showMessageDialog(null, 
                        "WARNING! A fatal error occured. See the log for more details. \n" +
                        exc.getMessage());
                    startStopMicButton.setText("START TRANSCRIPTION");
                    startStopMicButton.setOnAction(__ -> startTranscriptionRequest(null));
                    startStopMicButton.setDisable(false);
                    audioInputSelect.setDisable(false);
                }
                return result;
            });
        }
    }

    private void initializeWindow(Stage primaryStage) {
        Font buttonGillSans = Font.font("Gill Sans MT", 20);

        // GridPane grid = new GridPane();
        primaryStage.setFullScreen(true);
        FlowPane rootPane = new FlowPane();

        rootPane.setAlignment(Pos.TOP_CENTER);
        rootPane.setVgap(10);
        rootPane.setHgap(10);
        rootPane.setPadding(new Insets(15, 25, 25, 25));
        BackgroundFill fill = new BackgroundFill(Color.WHITE, CornerRadii.EMPTY, Insets.EMPTY);
        rootPane.setBackground(new Background(fill));
        
        
        int width = (int) Screen.getPrimary().getBounds().getWidth();
        int height = (int) Screen.getPrimary().getBounds().getHeight();
        Scene scene = new Scene(rootPane, width, height);
        primaryStage.setScene(scene);

        startStopMicButton = new Button();
        startStopMicButton.setFont(buttonGillSans);
        startStopMicButton.setText("START TRANSCRIPTION");
        startStopMicButton.setOnAction(__ -> startTranscriptionRequest(null));

        exitButton = new Button();
        exitButton.setText("CLOSE PROGRAM");
        exitButton.setFont(buttonGillSans);
        exitButton.setOnAction(__ -> {
            close();
            primaryStage.hide();
        });

        audioInputSelect = new ComboBox<>();
        audioInputSelect.setStyle("-fx-font: 20px \"Gill Sans MT\";");
        audioInputSelect.setCellFactory(new MixerRenderer());
        updateAudioInputDropdown();
        audioInputSelect.valueProperty().addListener(new ChangeListener<Mixer.Info>() {
            public void changed(javafx.beans.value.ObservableValue<? extends Mixer.Info> arg0, Mixer.Info arg1, Mixer.Info arg2) {
                client = new TranscribeStreamingClientWrapper(getSelectedAudioInput());
                synchronousClient = new TranscribeStreamingSynchronousClient(TranscribeStreamingClientWrapper.getClient());
            };
        });

        FlowPane bottomPane = new FlowPane();
        bottomPane.setPrefWidth(width);
        bottomPane.setPrefHeight(height - 50);
        bottomPane.setAlignment(Pos.BOTTOM_CENTER);
        bottomPane.setVgap(10);
        bottomPane.setHgap(10);
        bottomPane.setPadding(new Insets(25, 50, 50, 50));
        fill = new BackgroundFill(Color.rgb(0, 177, 64), CornerRadii.EMPTY, Insets.EMPTY);
        bottomPane.setBackground(new Background(fill));
        ObservableList<Node> list = bottomPane.getChildren();
        
        outputText = new Text();
        outputText.setWrappingWidth(width);
        outputText.setText("");
        outputText.setFont(Font.font("Gill Sans MT", 90));
        outputText.setFill(Color.WHITE);
        outputText.setStroke(Color.BLACK);
        outputText.setStrokeWidth(2);

        list.addAll(outputText);
        
        ObservableList<Node> rootList = rootPane.getChildren();
        rootList.addAll(startStopMicButton, exitButton, audioInputSelect, bottomPane);
    }

    private void updateAudioInputDropdown() {
        List<Mixer.Info> audioInputList = getAudioInputList();
        audioInputSelect.getItems().clear();
        audioInputSelect.getItems().addAll(audioInputList);
        if (audioInputList.size() > 0) {
            audioInputSelect.setValue(audioInputList.get(0));
        }
    }

    public Mixer.Info getSelectedAudioInput() {
        return audioInputSelect.getValue();
    }

    private void stopTranscription() {
        if (inProgressStreamingRequest != null) {
            try {
                // saveButton.setDisable(true);
                client.stopTranscription();
                inProgressStreamingRequest.get();
            } catch (ExecutionException | InterruptedException e) {
                System.out.println("error closing stream");
            } finally {
                inProgressStreamingRequest = null;
                startStopMicButton.setText("START TRANSCRIPTION");
                startStopMicButton.setOnAction(__ -> startTranscriptionRequest(null));
                startStopMicButton.setDisable(false);
                audioInputSelect.setDisable(false);
            }

        }
    }

    /**
     * A StartStreamTranscriptionResponseHandler class listens to events from Transcribe streaming service that return
     * transcriptions, and decides what to do with them. This example displays the transcripts in the GUI window, and
     * combines the transcripts together into a final transcript at the end.
     */
    private StreamTranscriptionBehavior getResponseHandlerForWindow() {
        return new StreamTranscriptionBehavior() {

            //This will handle errors being returned from AWS Transcribe in your response. Here we just print the exception.
            @Override
            public void onError(Throwable e) {
                System.out.println(e.getMessage());
                Throwable cause = e.getCause();
                while (cause != null) {
                    System.out.println("Caused by: " + cause.getMessage());
                    Arrays.stream(cause.getStackTrace()).forEach(l -> System.out.println("  " + l));
                    if (cause.getCause() != cause) { //Look out for circular causes
                        cause = cause.getCause();
                    } else {
                        cause = null;
                    }
                }
                System.out.println("Error Occurred: " + e);
            }

            /*
            This handles each event being received from the Transcribe service. In this example we are displaying the
            transcript as it is updated, and when we receive a "final" transcript, we append it to our finalTranscript
            which is returned at the end of the microphone streaming.
             */
            @Override
            public void onStream(TranscriptResultStream event) {
                List<Result> results = ((TranscriptEvent) event).transcript().results();
                if(results.size()>0) {
                    Result firstResult = results.get(0);
                    if (firstResult.alternatives().size() > 0 && !firstResult.alternatives().get(0).transcript().isEmpty()) {
                        String transcript = firstResult.alternatives().get(0).transcript();
                        if(!transcript.isEmpty()) {
                            // System.out.println(transcript);
                            String displayText;
                            if (!firstResult.isPartial()) {
                                finalTranscript += transcript + " ";
                                displayText = finalTranscript;
                            } else {
                                displayText = finalTranscript + " " + transcript;
                            }
                            Platform.runLater(() -> {
                                int maxLength = 100;
                                if (displayText.length() > maxLength) {
                                    String newText = displayText.substring(displayText.length() - maxLength);
                                    // Break off word
                                    outputText.setText(newText.substring(newText.indexOf(" ")));
                                } else {
                                    outputText.setText(displayText);
                                }
                                lastUpdatedTime = new Date();
                                // outputTextArea.setScrollTop(Double.MAX_VALUE);
                            });
                        }
                    }

                }
            }

            /*
            This handles the initial response from the AWS Transcribe service, generally indicating the streams have
            successfully been opened. Here we just print that we have received the initial response and do some
            UI updates.
             */
            @Override
            public void onResponse(StartStreamTranscriptionResponse r) {
                System.out.println(String.format("=== Received Initial response. Request Id: %s ===", r.requestId()));
                Platform.runLater(() -> {
                    startStopMicButton.setText("Stop Transcription");
                    startStopMicButton.setOnAction(__ -> stopTranscription());
                    startStopMicButton.setDisable(false);
                });
            }

            /*
            This method is called when the stream is terminated without error. In our case we will use this opportunity
            to display the final, total transcript we've been aggregating during the transcription period and activates
            the save button.
             */
            @Override
            public void onComplete() {
                System.out.println("=== All records streamed successfully ===");
                audioInputSelect.setDisable(false);
                // Platform.runLater(() -> {
                //     // finalTextArea.setText(finalTranscript);
                //     // saveButton.setDisable(false);
                //     // saveButton.setOnAction(__ -> {
                //     //     FileChooser fileChooser = new FileChooser();
                //     //     fileChooser.setTitle("Save Transcript");
                //     //     File file = fileChooser.showSaveDialog(primaryStage);
                //     //     if (file != null) {
                //     //         try {
                //     //             FileWriter writer = new FileWriter(file);
                //     //             writer.write(finalTranscript);
                //     //             writer.close();
                //     //         } catch (IOException e) {
                //     //             System.out.println("Error saving transcript to file: " + e);
                //     //         }
                //     //     }
                //     // });

                // });
            }
        };
    }

    /**
     * @return a list of system audio inputs
     */
    private static List<Mixer.Info> getAudioInputList() {
        List<Mixer.Info> result = new ArrayList<>();
        int sampleRate = 16000;
        AudioFormat format = new AudioFormat(sampleRate, 16, 1, true, false);
        DataLine.Info info = new DataLine.Info(TargetDataLine.class, format);

        for (Mixer.Info mx : AudioSystem.getMixerInfo()) {
            if (AudioSystem.getMixer(mx).isLineSupported(info)) {
                result.add(mx);
            }
        }

        return result;
    }

}
