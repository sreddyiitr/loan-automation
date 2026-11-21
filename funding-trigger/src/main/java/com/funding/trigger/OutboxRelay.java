package com.funding.trigger;

import com.funding.persistence.OutboxRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;

/**
 * Transactional outbox -> SQS (plan "Trigger Path"). Claims unpublished rows with
 * {@code FOR UPDATE SKIP LOCKED}, sends each, and marks it published only after SQS accepted it.
 * A crash between send and mark sends a duplicate, which the start API absorbs (AS-1.2).
 */
public final class OutboxRelay {

    private static final Logger LOG = LoggerFactory.getLogger(OutboxRelay.class);

    private final OutboxRepository outbox;
    private final TransactionTemplate tx;
    private final SqsClient sqs;
    private final String queueUrl;
    private final int batchSize;

    public OutboxRelay(JdbcClient jdbc, TransactionTemplate tx, SqsClient sqs, String queueUrl, int batchSize) {
        this.outbox = new OutboxRepository(jdbc);
        this.tx = tx;
        this.sqs = sqs;
        this.queueUrl = queueUrl;
        this.batchSize = batchSize;
    }

    /** One pass; returns how many events were published. */
    public int relayOnce() {
        Integer n = tx.execute(s -> {
            int sent = 0;
            for (OutboxRepository.Event e : outbox.claimUnpublished(batchSize)) {
                correlate(e); // NFR-05: the relay's log lines carry the application's ids
                try {
                    String messageId = sqs.sendMessage(SendMessageRequest.builder().queueUrl(queueUrl).messageBody(e.payload()).build()).messageId();
                    outbox.markPublished(e.eventId());
                    LOG.info("outbox event {} ({}) published as SQS message {}", e.eventId(), e.eventType(), messageId);
                } finally {
                    org.slf4j.MDC.remove("loanApplicationId");
                    org.slf4j.MDC.remove("applicationVersionId");
                }
                sent++;
            }
            return sent;
        });
        return n == null ? 0 : n;
    }

    private static final com.fasterxml.jackson.databind.ObjectMapper JSON = new com.fasterxml.jackson.databind.ObjectMapper();

    private static void correlate(OutboxRepository.Event e) {
        try {
            com.fasterxml.jackson.databind.JsonNode p = JSON.readTree(e.payload());
            if (p.hasNonNull("loan_application_id")) {
                org.slf4j.MDC.put("loanApplicationId", p.get("loan_application_id").asText());
            }
            if (p.hasNonNull("application_version_id")) {
                org.slf4j.MDC.put("applicationVersionId", p.get("application_version_id").asText());
            }
        } catch (java.io.IOException ignored) {
            // an unreadable payload still gets published; the consumer acknowledges and logs it (plan: 400)
        }
    }

    public int purge(int days) {
        return outbox.purgePublishedOlderThanDays(days);
    }
}
