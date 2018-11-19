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

package com.amazonaws.transcribestreaming;

import org.reactivestreams.Publisher;
import org.reactivestreams.Subscriber;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.signer.EventStreamAws4Signer;
import software.amazon.awssdk.core.client.config.SdkAdvancedClientOption;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.regions.providers.DefaultAwsRegionProviderChain;
import software.amazon.awssdk.services.transcribestreaming.TranscribeStreamingAsyncClient;
import software.amazon.awssdk.services.transcribestreaming.model.AudioStream;
import software.amazon.awssdk.services.transcribestreaming.model.LanguageCode;
import software.amazon.awssdk.services.transcribestreaming.model.MediaEncoding;
import software.amazon.awssdk.services.transcribestreaming.model.StartStreamTranscriptionRequest;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.TargetDataLine;
import javax.sound.sampled.UnsupportedAudioFileException;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.concurrent.CompletableFuture;

/**
 * This wraps the TranscribeStreamingAsyncClient with easier to use methods for quicker integration with the GUI. This
 * also provides examples on how to handle the various exceptions that can be thrown and how to implement a request
 * stream for input to the streaming service.
 */
public class TranscribeStreamingClientWrapper {

    private TranscribeStreamingRetryClient client;
    private AudioStreamPublisher requestStream;

    public TranscribeStreamingClientWrapper() {
        client = new TranscribeStreamingRetryClient(getClient());
    }

    public static TranscribeStreamingAsyncClient getClient() {
        Region region = getRegion();
        String endpoint = "https://transcribestreaming." + region.toString().toLowerCase().replace('_','-') + ".amazonaws.com";
        try {
            return TranscribeStreamingAsyncClient.builder()
                    .credentialsProvider(getCredentials())
                    .endpointOverride(new URI(endpoint))
                    .region(region)
                    .build();
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("Invalid URI syntax for endpoint: " + endpoint);
        }

    }

    /**
     * Get region from default region provider chain, default to PDX (us-west-2)
     */
    private static Region getRegion() {
        Region region;
        try {
            region = new DefaultAwsRegionProviderChain().getRegion();
        } catch (SdkClientException e) {
            region = Region.US_WEST_2;
        }
        return region;
    }

    /**
     * Start real-time speech recognition. Transcribe streaming java client uses Reactive-streams interface.
     * For reference on Reactive-streams: https://github.com/reactive-streams/reactive-streams-jvm
     *
     * @param responseHandler StartStreamTranscriptionResponseHandler that determines what to do with the response
     *                        objects as they are received from the streaming service
     * @param inputFile optional input file to stream audio from. Will stream from the microphone if this is set to null
     */
    public CompletableFuture<Void> startTranscription(StreamTranscriptionBehavior responseHandler, File inputFile) {
        if (requestStream != null) {
            throw new IllegalStateException("Stream is already open");
        }
        try {
            int sampleRate = 16_000; //default
            if (inputFile != null) {
                sampleRate = (int) AudioSystem.getAudioInputStream(inputFile).getFormat().getSampleRate();
                requestStream = new AudioStreamPublisher(getStreamFromFile(inputFile));
            } else {
                requestStream = new AudioStreamPublisher(getStreamFromMic());
            }
            return client.startStreamTranscription(
                    //Request parameters. Refer to API documentation for details.
                    getRequest(sampleRate),
                    //AudioEvent publisher containing "chunks" of audio data to transcribe
                    requestStream,
                    //Defines what to do with transcripts as they arrive from the service
                    responseHandler);
        } catch (LineUnavailableException | UnsupportedAudioFileException | IOException ex) {
            CompletableFuture<Void> failedFuture = new CompletableFuture<>();
            failedFuture.completeExceptionally(ex);
            return failedFuture;
        }
    }

    /**
     * Stop in-progress transcription if there is one in progress by closing the request stream
     */
    public void stopTranscription() {
        if (requestStream != null) {
            try {
                requestStream.inputStream.close();
            } catch (IOException ex) {
                System.out.println("Error stopping input stream: " + ex);
            } finally {
                requestStream = null;
            }
        }
    }

    /**
     * Close clients and streams
     */
    public void close() {
        try {
            if (requestStream != null) {
                requestStream.inputStream.close();
            }
        } catch (IOException ex) {
            System.out.println("error closing in-progress microphone stream: " + ex);
        } finally {
            client.close();
        }
    }

    /**
     * Build an input stream from a microphone if one is present.
     * @return InputStream containing streaming audio from system's microphone
     * @throws LineUnavailableException When a microphone is not detected or isn't properly working
     */
    private static InputStream getStreamFromMic() throws LineUnavailableException {

        // Signed PCM AudioFormat with 16kHz, 16 bit sample size, mono
        int sampleRate = 16000;
        AudioFormat format = new AudioFormat(sampleRate, 16, 1, true, false);
        DataLine.Info info = new DataLine.Info(TargetDataLine.class, format);

        if (!AudioSystem.isLineSupported(info)) {
            System.out.println("Line not supported");
            System.exit(0);
        }

        TargetDataLine line = (TargetDataLine) AudioSystem.getLine(info);
        line.open(format);
        line.start();

        return new AudioInputStream(line);
    }

    /**
     * Build an input stream from an audio file
     * @param inputFile Name of the file containing audio to transcribe
     * @return InputStream built from reading the file's audio
     */
    private static InputStream getStreamFromFile(File inputFile) {
        try {
            return new FileInputStream(inputFile);
        } catch (FileNotFoundException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Build StartStreamTranscriptionRequestObject containing required parameters to open a streaming transcription
     * request, such as audio sample rate and language spoken in audio
     * @param mediaSampleRateHertz sample rate of the audio to be streamed to the service in Hertz
     * @return StartStreamTranscriptionRequest to be used to open a stream to transcription service
     */
    private StartStreamTranscriptionRequest getRequest(Integer mediaSampleRateHertz) {
        return StartStreamTranscriptionRequest.builder()
                .languageCode(LanguageCode.EN_US.toString())
                .mediaEncoding(MediaEncoding.PCM)
                .mediaSampleRateHertz(mediaSampleRateHertz)
                .build();
    }

    /**
     * @return AWS credentials to be used to connect to Transcribe service. This example uses the default credentials
     * provider, which looks for environment variables (AWS_ACCESS_KEY_ID and AWS_SECRET_ACCESS_KEY) or a credentials
     * file on the system running this program.
     */
    private static AwsCredentialsProvider getCredentials() {
        return DefaultCredentialsProvider.create();
    }

    /**
     * AudioStreamPublisher implements audio stream publisher.
     * AudioStreamPublisher emits audio stream asynchronously in a separate thread
     */
    private static class AudioStreamPublisher implements Publisher<AudioStream> {
        private final InputStream inputStream;

        private AudioStreamPublisher(InputStream inputStream) {
            this.inputStream = inputStream;
        }

        @Override
        public void subscribe(Subscriber<? super AudioStream> s) {
            s.onSubscribe(new ByteToAudioEventSubscription(s, inputStream));
        }
    }
}
