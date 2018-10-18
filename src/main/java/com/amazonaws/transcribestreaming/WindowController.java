/*
 * Copyright 2010-2018 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * A copy of the License is located at
 *
 *  http://aws.amazon.com/apache2.0
 *
 * or in the "license" file accompanying this file. This file is distributed
 * on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 * express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */

package com.amazonaws.transcribestreaming;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.TextArea;
import javafx.scene.layout.GridPane;
import javafx.scene.text.Text;
import javafx.stage.FileChooser;
import javafx.stage.Stage;
import software.amazon.awssdk.services.transcribestreaming.model.Result;
import software.amazon.awssdk.services.transcribestreaming.model.StartStreamTranscriptionResponseHandler;
import software.amazon.awssdk.services.transcribestreaming.model.TranscriptEvent;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

/**
 * This class primarily controls the GUI for this application. Most of the code relevant to starting and working
 * with our streaming API can be found in TranscribeStreamingClientWrapper.java, with the exception of some result
 * parsing logic in this classes method getResponseHandlerForWindow()
 */
public class WindowController {

    private TranscribeStreamingClientWrapper client;
    private TextArea outputTextArea;
    private Button startStopMicButton;
    private Button fileStreamButton;
    private Button saveButton;
    private TextArea finalTextArea;
    private CompletableFuture<Void> inProgressStreamingRequest;
    private String finalTranscript = "";
    private Double startTime;
    private Double endTime;
    private Stage primaryStage;

    public WindowController(Stage primaryStage) {
        client = new TranscribeStreamingClientWrapper();
        this.primaryStage = primaryStage;
        initializeWindow(primaryStage);
    }

    public void close() {
        if (inProgressStreamingRequest != null) {
            inProgressStreamingRequest.completeExceptionally(new InterruptedException());
        }
        client.close();
    }

    private void startTranscriptionRequest(File inputFile) {
        if (inProgressStreamingRequest == null) {
            finalTextArea.clear();
            finalTranscript = "";
            startStopMicButton.setText("Connecting...");
            startStopMicButton.setDisable(true);
            outputTextArea.clear();
            finalTextArea.clear();
            saveButton.setDisable(true);
            startTime = null;
            inProgressStreamingRequest = client.startTranscription(getResponseHandlerForWindow(), inputFile);
        }
    }

    private void initializeWindow(Stage primaryStage) {
        GridPane grid = new GridPane();
        grid.setAlignment(Pos.CENTER);
        grid.setVgap(10);
        grid.setHgap(10);
        grid.setPadding(new Insets(25, 25, 25, 25));

        Scene scene = new Scene(grid, 500, 600);
        primaryStage.setScene(scene);

        startStopMicButton = new Button();
        startStopMicButton.setText("Start Transcription");
        startStopMicButton.setOnAction(__ -> startTranscriptionRequest(null));
        grid.add(startStopMicButton, 0, 0, 1, 1);

        fileStreamButton = new Button();
        fileStreamButton.setText("Stream From Audio File"); //TODO: what file types do we support?
        fileStreamButton.setOnAction(__ -> {
            FileChooser inputFileChooser = new FileChooser();
            inputFileChooser.setTitle("Stream Audio File");
            File inputFile = inputFileChooser.showOpenDialog(primaryStage);
            startTranscriptionRequest(inputFile);
        });
        grid.add(fileStreamButton, 1, 0, 1, 1);

        Text inProgressText = new Text("In Progress Transcriptions:");
        grid.add(inProgressText, 0, 1, 2, 1);

        outputTextArea = new TextArea();
        outputTextArea.setWrapText(true);
        outputTextArea.setEditable(false);
        grid.add(outputTextArea, 0, 2, 2, 1);

        Text finalText = new Text("Final Transcription:");
        grid.add(finalText, 0, 3, 2, 1);

        finalTextArea = new TextArea();
        finalTextArea.setWrapText(true);
        finalTextArea.setEditable(false);
        grid.add(finalTextArea, 0, 4, 2, 1);

        saveButton = new Button();
        saveButton.setDisable(true);
        saveButton.setText("Save Full Transcript");
        grid.add(saveButton, 0, 5, 2, 1);


    }

    private void stopTranscription() {
        if (inProgressStreamingRequest != null) {
            try {
                saveButton.setDisable(true);
                client.stopTranscription();
                inProgressStreamingRequest.get();
            } catch (ExecutionException | InterruptedException e) {
                System.out.println("error closing stream");
            } finally {
                inProgressStreamingRequest = null;
                startStopMicButton.setText("Start Transcription");
                startStopMicButton.setOnAction(__ -> startTranscriptionRequest(null));
                startStopMicButton.setDisable(false);
            }

        }
    }

    /**
     * A StartStreamTranscriptionResponseHandler class listens to events from Transcribe streaming service that return
     * transcriptions, and decides what to do with them. This example displays the transcripts in the GUI window, and
     * combines the transcripts together into a final transcript at the end.
     */
    private StartStreamTranscriptionResponseHandler getResponseHandlerForWindow() {
        return StartStreamTranscriptionResponseHandler.builder()
                .onResponse(r -> {
                    System.out.println(String.format("=== Received Initial response. Request Id: %s ===", r.requestId()));
                    Platform.runLater(() -> {
                        startStopMicButton.setText("Stop Transcription");
                        startStopMicButton.setOnAction(__ -> stopTranscription());
                        startStopMicButton.setDisable(false);
                    });
                })
                .onError(e -> {
                    System.out.println(e.getMessage());
                    System.out.println("Error Occurred: " + e);
                })
                .onComplete(() -> {
                    System.out.println("=== All records streamed successfully ===");
                    Platform.runLater(() -> {
                        finalTextArea.setText(finalTranscript);



                        saveButton.setDisable(false);
                        saveButton.setOnAction(__ -> {
                            FileChooser fileChooser = new FileChooser();
                            fileChooser.setTitle("Save Transcript");
                            File file = fileChooser.showSaveDialog(primaryStage);
                            if (file != null) {
                                try {
                                    FileWriter writer = new FileWriter(file);
                                    writer.write(finalTranscript);
                                    writer.close();
                                } catch (IOException e) {
                                    System.out.println("Error saving transcript to file: " + e);
                                }
                            }
                        });

                    });
                })
                .subscriber(event -> {
                    List<Result> results = ((TranscriptEvent) event).transcript().results();
                    if(results.size()>0) {
                        Result firstResult = results.get(0);
                        if (firstResult.alternatives().size() > 0 && !firstResult.alternatives().get(0).transcript().isEmpty()) {
                            String transcript = firstResult.alternatives().get(0).transcript();
                            if(!transcript.isEmpty() && !firstResult.isPartial()) {
                                System.out.println(transcript);
                                if(startTime == null) {
                                    startTime = firstResult.startTime();
                                }
                                endTime = firstResult.endTime();
                                finalTranscript += transcript + " ";
                                Platform.runLater(() -> {
                                    outputTextArea.appendText(transcript + "\n");
                                    outputTextArea.setScrollTop(Double.MAX_VALUE);
                                });

                            }
                        }

                    }
                })
                .build();
    }

}
