package com.funding.trigger;

import com.amazonaws.services.lambda.runtime.ClientContext;
import com.amazonaws.services.lambda.runtime.CognitoIdentity;
import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.LambdaLogger;
import com.amazonaws.services.lambda.runtime.events.SQSBatchResponse;
import com.amazonaws.services.lambda.runtime.events.SQSEvent;
import com.funding.trigger.lambda.SqsTriggerHandler;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.DeleteMessageBatchRequest;
import software.amazon.awssdk.services.sqs.model.DeleteMessageBatchRequestEntry;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.MessageSystemAttributeName;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest;

/**
 * {@code local} profile, always on (plan V-2, V-4): the emulator can't run a java21 Lambda and its
 * Lambdas can't reach the host, so this long-polls the queue and calls the <b>same</b>
 * {@link SqsTriggerHandler}. Like an event source mapping with {@code ReportBatchItemFailures}, it
 * deletes the messages not listed in {@code batchItemFailures} and leaves the others for the
 * visibility timeout and DLQ redrive, which are real emulator behaviour (V-1).
 */
public final class LocalLambdaRunner implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(LocalLambdaRunner.class);
    private static final Duration LAMBDA_TIMEOUT = Duration.ofSeconds(30);

    private final SqsClient sqs;
    private final String queueUrl;
    private final SqsTriggerHandler handler;
    private final int waitSeconds;
    private final AtomicBoolean running = new AtomicBoolean();
    private Thread thread;

    public LocalLambdaRunner(SqsClient sqs, String queueUrl, SqsTriggerHandler handler, int waitSeconds) {
        this.sqs = sqs;
        this.queueUrl = queueUrl;
        this.handler = handler;
        this.waitSeconds = waitSeconds;
    }

    public void start() {
        if (running.compareAndSet(false, true)) {
            thread = Thread.ofVirtual().name("local-lambda-runner").start(this::loop);
        }
    }

    private void loop() {
        while (running.get()) {
            try {
                pollOnce();
            } catch (Exception e) {
                if (running.get()) {
                    LOG.warn("local Lambda runner poll failed: {}", e.toString());
                    sleep(Duration.ofSeconds(1));
                }
            }
        }
    }

    /** One receive + invoke + delete cycle; returns the number of messages received. */
    public int pollOnce() {
        List<Message> messages = sqs.receiveMessage(ReceiveMessageRequest.builder()
                .queueUrl(queueUrl).maxNumberOfMessages(10).waitTimeSeconds(waitSeconds)
                .messageSystemAttributeNames(MessageSystemAttributeName.APPROXIMATE_RECEIVE_COUNT)
                .build()).messages();
        if (messages.isEmpty()) {
            return 0;
        }
        SQSEvent event = new SQSEvent();
        List<SQSEvent.SQSMessage> records = new ArrayList<>();
        for (Message m : messages) {
            SQSEvent.SQSMessage r = new SQSEvent.SQSMessage();
            r.setMessageId(m.messageId());
            r.setReceiptHandle(m.receiptHandle());
            r.setBody(m.body());
            r.setAttributes(m.attributesAsStrings());
            r.setEventSource("aws:sqs");
            records.add(r);
        }
        event.setRecords(records);
        long deadline = System.currentTimeMillis() + LAMBDA_TIMEOUT.toMillis();
        SQSBatchResponse response = handler.handleRequest(event, new LocalContext(deadline));
        Set<String> failed = response.getBatchItemFailures().stream()
                .map(SQSBatchResponse.BatchItemFailure::getItemIdentifier).collect(Collectors.toSet());
        List<DeleteMessageBatchRequestEntry> deletes = messages.stream().filter(m -> !failed.contains(m.messageId()))
                .map(m -> DeleteMessageBatchRequestEntry.builder().id(m.messageId()).receiptHandle(m.receiptHandle()).build())
                .toList();
        if (!deletes.isEmpty()) {
            sqs.deleteMessageBatch(DeleteMessageBatchRequest.builder().queueUrl(queueUrl).entries(deletes).build());
        }
        if (!failed.isEmpty()) {
            LOG.warn("{} of {} messages left for redelivery: {}", failed.size(), messages.size(), failed);
        }
        return messages.size();
    }

    @Override
    public void close() {
        running.set(false);
        if (thread != null) {
            thread.interrupt();
        }
    }

    private static void sleep(Duration d) {
        try {
            Thread.sleep(d);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Minimal Lambda context: only the remaining-time budget matters to the handler. */
    private record LocalContext(long deadlineMillis) implements Context {
        public String getAwsRequestId() { return "local"; }
        public String getLogGroupName() { return "local"; }
        public String getLogStreamName() { return "local"; }
        public String getFunctionName() { return "funding-trigger"; }
        public String getFunctionVersion() { return "$LATEST"; }
        public String getInvokedFunctionArn() { return "arn:aws:lambda:us-west-2:000000000000:function:funding-trigger"; }
        public CognitoIdentity getIdentity() { return null; }
        public ClientContext getClientContext() { return null; }
        public int getRemainingTimeInMillis() { return (int) Math.max(0, deadlineMillis - System.currentTimeMillis()); }
        public int getMemoryLimitInMB() { return 512; }
        public LambdaLogger getLogger() {
            return new LambdaLogger() {
                public void log(String message) { LOG.info(message); }
                public void log(byte[] message) { LOG.info(new String(message, StandardCharsets.UTF_8)); }
            };
        }
    }

}
