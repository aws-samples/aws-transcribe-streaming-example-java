package com.amazonaws.transcribestreaming;

import org.reactivestreams.Publisher;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.transcribestreaming.TranscribeStreamingAsyncClient;
import software.amazon.awssdk.services.transcribestreaming.model.AudioEvent;
import software.amazon.awssdk.services.transcribestreaming.model.AudioStream;
import software.amazon.awssdk.services.transcribestreaming.model.LanguageCode;
import software.amazon.awssdk.services.transcribestreaming.model.MediaEncoding;
import software.amazon.awssdk.services.transcribestreaming.model.StartStreamTranscriptionRequest;
import software.amazon.awssdk.services.transcribestreaming.model.StartStreamTranscriptionResponseHandler;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.TargetDataLine;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.ByteBuffer;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

/**
 * This wraps the TranscribeStreamingAsyncClient with easier to use methods for quicker integration with the GUI. This
 * also provides examples on how to handle the various exceptions that can be thrown and how to implement a request
 * stream for input to the streaming service.
 */
public class TranscribeStreamingClientWrapper {

    private static final String ENDPOINT = "https://transcribestreaming.us-east-1.amazonaws.com";

    private TranscribeStreamingAsyncClient client;
    private AudioStreamPublisher requestStream;

    public TranscribeStreamingClientWrapper() {
        try {
            client = TranscribeStreamingAsyncClient.builder()
                    .credentialsProvider(getCredentials())
                    .endpointOverride(new URI(ENDPOINT))
                    .region(Region.US_EAST_1)
                    .build();
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("Invalid URI syntax for endpoint: " + ENDPOINT);
        }
    }

    /**
     * Start real-time speech recognition. Transcribe streaming java client uses Reactive-streams interface.
     * For reference on Reactive-streams: https://github.com/reactive-streams/reactive-streams-jvm
     *
     * @param responseHandler StartStreamTranscriptionResponseHandler that determines what to do with the response
     *                        objects as they are received from the streaming service
     * @param inputFile optional input file to stream audio from. Will stream from the microphone if this is set to null
     */
    public CompletableFuture<Void> startTranscription(StartStreamTranscriptionResponseHandler responseHandler, File inputFile) {
        if (requestStream != null) {
            throw new IllegalStateException("Stream is already open");
        }
        try {
            /*
             * Provide input audio stream.
             * For input from microphone, see getStreamFromMic().
             * For input from file, see getStreamFromFile().
             */
            if (inputFile != null) {
                requestStream = new AudioStreamPublisher(getStreamFromFile(inputFile));
            } else {
                requestStream = new AudioStreamPublisher(getStreamFromMic());
            }
            return client.startStreamTranscription(
                    /*
                     * Request parameters. Refer to API document for details.
                     */
                    getRequest(16_000),
                    requestStream,
                    /*
                     * Subscriber of real-time transcript stream.
                     * Output will print to your computer's standard output.
                     */
                    responseHandler);
        } catch (LineUnavailableException ex) {
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
     * @param audioFileName Name of the file containing audio to transcribe
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

    /**
     * This is an example Subscription implementation that converts bytes read from an AudioStream into AudioEvents
     * that can be sent to the Transcribe service. It implements a simple demand system that will read chunks of bytes
     * from an input stream containing audio data
     *
     * To read more about how Subscriptions and reactive streams work, please see
     * https://github.com/reactive-streams/reactive-streams-jvm/blob/v1.0.2/README.md
     */
    private static class ByteToAudioEventSubscription implements Subscription {
        private static final int CHUNK_SIZE_IN_BYTES = 1024 * 1;
        private ExecutorService executor = Executors.newFixedThreadPool(1);
        private AtomicLong demand = new AtomicLong(0);

        private final Subscriber<? super AudioStream> subscriber;
        private final InputStream inputStream;

        private ByteToAudioEventSubscription(Subscriber<? super AudioStream> s, InputStream inputStream) {
            this.subscriber = s;
            this.inputStream = inputStream;
        }

        @Override
        public void request(long n) {
            if (n <= 0) {
                subscriber.onError(new IllegalArgumentException("Demand must be positive"));
            }

            demand.getAndAdd(n);

            executor.submit(() -> {
                try {
                    do {
                        ByteBuffer audioBuffer = getNextEvent();
                        if (audioBuffer.remaining() > 0) {
                            AudioEvent audioEvent = audioEventFromBuffer(audioBuffer);
                            subscriber.onNext(audioEvent);
                        } else {
                            subscriber.onComplete();
                            break;
                        }
                    } while (demand.decrementAndGet() > 0);
                } catch (Exception e) {
                    subscriber.onError(e);
                }
            });
        }

        @Override
        public void cancel() {
            executor.shutdown();
        }

        private ByteBuffer getNextEvent() {
            ByteBuffer audioBuffer = null;
            byte[] audioBytes = new byte[CHUNK_SIZE_IN_BYTES];

            try {
                int len = inputStream.read(audioBytes);

                if (len <= 0) {
                    audioBuffer = ByteBuffer.allocate(0);
                } else {
                    audioBuffer = ByteBuffer.wrap(audioBytes, 0, len);
                }
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }

            return audioBuffer;
        }

        private AudioEvent audioEventFromBuffer(ByteBuffer bb) {
            return AudioEvent.builder()
                    .audioChunk(SdkBytes.fromByteBuffer(bb))
                    .build();
        }
    }

}
