package com.funding.trigger;

import java.util.Map;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.CreateQueueRequest;
import software.amazon.awssdk.services.sqs.model.GetQueueAttributesRequest;
import software.amazon.awssdk.services.sqs.model.GetQueueUrlRequest;
import software.amazon.awssdk.services.sqs.model.QueueAttributeName;
import software.amazon.awssdk.services.sqs.model.QueueDoesNotExistException;
import software.amazon.awssdk.services.sqs.model.SetQueueAttributesRequest;

/**
 * {@code local} only: the emulator keeps state in memory, so this runs on every start and is
 * idempotent. An existing queue is looked up, not created again: the emulator refuses a
 * {@code CreateQueue} without attributes for a queue that has a redrive policy ("a different value
 * for attribute RedrivePolicy"), which broke every second {@code make run} on the same emulator. Creates the queue and its DLQ with {@code maxReceiveCount=5}. No Lambda, role or
 * event source mapping locally (plan V-2, V-4); the visibility timeout (60 s) stays above the
 * Lambda timeout (30 s), as the future IaC must (V-3).
 */
public final class InfraBootstrap {

    public record Queues(String queueUrl, String dlqUrl) {}

    private InfraBootstrap() {}

    public static Queues ensureQueues(SqsClient sqs, QueueNames names, int visibilityTimeoutSeconds, int maxReceiveCount) {
        String dlqUrl = urlOrCreate(sqs, names.dlq());
        String dlqArn = EmulatorCalls.call("sqs", "GetQueueAttributes", () -> sqs.getQueueAttributes(
                GetQueueAttributesRequest.builder().queueUrl(dlqUrl).attributeNames(QueueAttributeName.QUEUE_ARN).build())
                .attributes().get(QueueAttributeName.QUEUE_ARN));
        String queueUrl = urlOrCreate(sqs, names.queue());
        String redrive = "{\"deadLetterTargetArn\":\"" + dlqArn + "\",\"maxReceiveCount\":\"" + maxReceiveCount + "\"}";
        EmulatorCalls.call("sqs", "SetQueueAttributes", () -> sqs.setQueueAttributes(SetQueueAttributesRequest.builder()
                .queueUrl(queueUrl)
                .attributes(Map.of(
                        QueueAttributeName.VISIBILITY_TIMEOUT, Integer.toString(visibilityTimeoutSeconds),
                        QueueAttributeName.REDRIVE_POLICY, redrive))
                .build()));
        return new Queues(queueUrl, dlqUrl);
    }

    private static String urlOrCreate(SqsClient sqs, String name) {
        try {
            return EmulatorCalls.call("sqs", "GetQueueUrl",
                    () -> sqs.getQueueUrl(GetQueueUrlRequest.builder().queueName(name).build()).queueUrl());
        } catch (QueueDoesNotExistException missing) {
            return EmulatorCalls.call("sqs", "CreateQueue",
                    () -> sqs.createQueue(CreateQueueRequest.builder().queueName(name).build()).queueUrl());
        }
    }
}
