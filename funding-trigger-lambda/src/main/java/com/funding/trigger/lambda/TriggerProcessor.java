package com.funding.trigger.lambda;

import com.amazonaws.services.lambda.runtime.events.SQSBatchResponse;
import com.amazonaws.services.lambda.runtime.events.SQSEvent;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.LongSupplier;

/**
 * The trigger Lambda's logic (plan.md "Lambda funding-trigger"). Sends every record of the batch
 * concurrently on virtual threads and reports only the records that should be retried, so one bad
 * message never fails the batch:
 *
 * <pre>
 *   202 / 200          ack                (started / alreadyStarted, AS-1.2, AS-1.3)
 *   409                ack, warn          (stale message, AS-1.6)
 *   400 or bad body    ack, error         (will never succeed; don't cycle it to the DLQ)
 *   401                fail item          (config error; retries end in the DLQ, AS-1.7)
 *   5xx / timeout      fail item          (SQS redelivers; DLQ after 5 receives, AS-1.5)
 * </pre>
 *
 * A record that can't finish within the Lambda's remaining time is failed without being sent.
 */
public final class TriggerProcessor {

    private static final Logger LOG = System.getLogger(TriggerProcessor.class.getName());
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final long SAFETY_MARGIN_MS = 500;

    private final StartApi api;
    private final Duration httpTimeout;

    public TriggerProcessor(StartApi api, Duration httpTimeout) {
        this.api = api;
        this.httpTimeout = httpTimeout;
    }

    enum Decision { ACK, RETRY }

    public SQSBatchResponse process(List<SQSEvent.SQSMessage> records, LongSupplier remainingMillis) {
        List<SQSBatchResponse.BatchItemFailure> failures = new ArrayList<>();
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<Decision>> futures = new ArrayList<>();
            for (SQSEvent.SQSMessage m : records) {
                futures.add(pool.submit(() -> handle(m, remainingMillis)));
            }
            for (int i = 0; i < records.size(); i++) {
                Decision d;
                try {
                    d = futures.get(i).get();
                } catch (Exception e) {
                    LOG.log(Level.ERROR, "message {0}: unexpected failure, will retry", records.get(i).getMessageId(), e);
                    d = Decision.RETRY;
                }
                if (d == Decision.RETRY) {
                    failures.add(SQSBatchResponse.BatchItemFailure.builder()
                            .withItemIdentifier(records.get(i).getMessageId()).build());
                }
            }
        }
        return SQSBatchResponse.builder().withBatchItemFailures(failures).build();
    }

    Decision handle(SQSEvent.SQSMessage m, LongSupplier remainingMillis) {
        String body = request(m);
        if (body == null) {
            return Decision.ACK;
        }
        if (remainingMillis.getAsLong() < httpTimeout.toMillis() + SAFETY_MARGIN_MS) {
            LOG.log(Level.WARNING, "message {0}: not enough Lambda time left; leaving it for redelivery", m.getMessageId());
            return Decision.RETRY;
        }
        StartApi.Response r;
        try {
            r = api.post(body);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Decision.RETRY;
        } catch (Exception e) {
            LOG.log(Level.WARNING, "message {0}: start API unreachable or timed out ({1}); will retry", m.getMessageId(), e.toString());
            return Decision.RETRY;
        }
        return decide(m.getMessageId(), r);
    }

    static Decision decide(String messageId, StartApi.Response r) {
        int s = r.status();
        if (s == 200 || s == 202) {
            return Decision.ACK;
        }
        if (s == 409) {
            LOG.log(Level.WARNING, "message {0}: stale ({1}); acknowledged", messageId, r.body());
            return Decision.ACK;
        }
        if (s == 400) {
            LOG.log(Level.ERROR, "message {0}: rejected as malformed ({1}); acknowledged, will never succeed", messageId, r.body());
            return Decision.ACK;
        }
        LOG.log(Level.WARNING, "message {0}: start API returned {1}; will retry", messageId, s);
        return Decision.RETRY;
    }

    /** Outbox payload (snake_case) -> start API request (camelCase). Null when the body is unusable. */
    static String request(SQSEvent.SQSMessage m) {
        try {
            JsonNode n = JSON.readTree(m.getBody());
            if (n == null || !n.hasNonNull("application_version_id") || !n.hasNonNull("event_type")) {
                LOG.log(Level.ERROR, "message {0}: body has no event_type/application_version_id; acknowledged", m.getMessageId());
                return null;
            }
            ObjectNode out = JSON.createObjectNode();
            out.put("eventType", n.get("event_type").asText());
            out.put("loanApplicationId", n.path("loan_application_id").asText(null));
            out.put("applicationVersionId", n.get("application_version_id").asLong());
            if (n.hasNonNull("document_id")) {
                out.put("documentId", n.get("document_id").asLong());
            } else {
                out.putNull("documentId");
            }
            out.put("sourceMessageId", m.getMessageId());
            return JSON.writeValueAsString(out);
        } catch (Exception e) {
            LOG.log(Level.ERROR, "message {0}: body is not JSON; acknowledged", m.getMessageId());
            return null;
        }
    }
}
