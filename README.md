# AWS Live Captions

A Java Application using AWS SDK to create live transcriptions via AWS Transcribe. It takes a live audio source from the target machine and outputs captions. 

## License Summary

This sample code is made available under a modified MIT license. See the LICENSE file.

## Setup

**This application builds with Java 8 using JavaFX. It may not build using OpenJDK 11 due to JavaFX being moved to its own library.**

Make sure you have AWS credentials set up on your machine with at least the following permission:

```json
{
    "Version": "2012-10-17",
    "Statement": [
        {
            "Sid": "transcribestreaming",
            "Effect": "Allow",
            "Action": "transcribe:StartStreamTranscription",
            "Resource": "*"
        }
    ]
}
```

To generate an executable jar, use the following commands:
```bash
export AWS_ACCESS_KEY_ID=<your access key>
export AWS_SECRET_ACCESS_KEY=<your secret key>
export AWS_REGION=us-west-2
mvn clean package
java -jar target/aws-transcribe-sample-application-1.0-SNAPSHOT-jar-with-dependencies.jar
```

You can also run from the command line using:
```bash
mvn clean javafx:run
```

## Description

This application demonstrates how to use AWS Transcribe's streaming API by wrapping it in a graphical user-interface. 
The code with the call to the Transcribe API is located in TranscribeStreamingClientWrapper.java, in the 
"startTranscription" method.

This API takes advantage of a more advanced AWS SDK feature: the EventStream. These allow for streaming APIs by defining
behaviors to execute for multiple types of events, including success and error events. You can see an example 
implementation of this behavior defined in the WindowController.java class, in the "getResponseHandlerForWindow" method.
These events are handled asynchronously, but you can see an example of treating the streaming API as a synchronous 
service in the TranscribeStreamingSynchronousClient.java class, which is used for reading files in the UI.

## Classes

|Class|Description|
|---|---|
| `TranscribeStreamingDemoApp` | Main method that launches the application, instantiates the `WindowController` |
| `WindowController` | Handles the GUI elements for the application. Also defines the behavior for the responses from the Stream API |
| `TranscribeStreamingClientWrapper` | Wrapper around the AWS SDK Transcribe Client, provides examples of how to call the SDK's methods properly |
| `AudioStreamPublisher` | Used to provide streaming events to the service, wraps `ByteToAudioEventSubscription` |
| `ByteToAudioEventSubscription` | Converts bytes from audio input into AudioEvents to send to the AWS Transcribe Service |
| `TranscribeStreamingRetryClient` | Wraps retry logic around the AWS Transcribe SDK, including resuming sessions in the case of disconnects |
| `StreamTranscriptionBehavior` | Class required by `TranscribeStreamingRetryClient` to determine response handling behavior |
| `TranscribeStreamingSynchronousClient` | Class providing example of turning the asynchronous event-stream API into a synchronous one | 

## See Also
https://docs.aws.amazon.com/transcribe/latest/dg/streaming.html
