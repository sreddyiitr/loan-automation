package com.funding.workflows.activities;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.funding.domain.DocumentType;
import com.funding.domain.FieldValue;
import com.funding.domain.money.Money;
import com.funding.persistence.AuditRepository;
import com.funding.persistence.DisbursementRepository;
import com.funding.persistence.SnapshotRepository;
import com.funding.persistence.StipRepository;
import com.funding.persistence.SupersededException;
import com.funding.persistence.VersionRepository;
import com.funding.persistence.VersionRow;
import com.funding.workflows.DisbursementActivities;
import com.funding.workflows.WorkflowIds;
import io.temporal.failure.ApplicationFailure;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * AS-8.1 / AS-8.9: pays RISC {@code amount_financed} to the dealer with idempotency key = the
 * workflow ID. Right before calling, one transaction re-checks latest + APPROVED + no PENDING stip,
 * which closes the race with a manual stip or a reject. 5xx and timeouts are retried by Temporal;
 * 4xx and malformed responses are not (they will never succeed).
 */
public class DisbursementActivitiesImpl implements DisbursementActivities {

    public static final String PATH = "/api/v1/external/disbursements";
    private static final Logger LOG = LoggerFactory.getLogger(DisbursementActivitiesImpl.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    private final String env;
    private final TransactionTemplate tx;
    private final VersionRepository versions;
    private final StipRepository stips;
    private final SnapshotRepository snapshots;
    private final DisbursementRepository disbursements;
    private final AuditRepository audit;
    private final HttpClient http;
    private final URI uri;
    private final Duration timeout;

    public DisbursementActivitiesImpl(String env, JdbcClient jdbc, TransactionTemplate tx, String baseUrl, Duration timeout) {
        this.env = env;
        this.tx = tx;
        this.versions = new VersionRepository(jdbc);
        this.stips = new StipRepository(jdbc);
        this.snapshots = new SnapshotRepository(jdbc);
        this.disbursements = new DisbursementRepository(jdbc);
        this.audit = new AuditRepository(jdbc);
        this.uri = URI.create(baseUrl.replaceAll("/+$", "") + PATH);
        this.timeout = timeout;
        this.http = HttpClient.newBuilder().connectTimeout(timeout).build();
    }

    private record Payment(VersionRow version, BigDecimal amount, String existingReference) {}

    @Override
    public String disburse(long versionId) {
        String workflowId = WorkflowIds.parent(env, versionId);
        Payment p = tx.execute(s -> prepare(versionId));
        if (p == null) {
            return null;
        }
        if (p.existingReference() != null) {
            return p.existingReference();
        }
        ObjectNode body = JSON.createObjectNode();
        body.put("applicationVersionId", versionId);
        body.put("loanApplicationId", p.version().loanApplicationId());
        body.put("dealershipId", p.version().dealershipId());
        body.put("amount", Money.format(p.amount()));

        HttpResponse<String> resp;
        try {
            resp = http.send(HttpRequest.newBuilder(uri).timeout(timeout)
                    .header("Content-Type", "application/json")
                    .header("Idempotency-Key", workflowId)
                    .POST(HttpRequest.BodyPublishers.ofString(body.toString())).build(),
                    HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw ApplicationFailure.newFailureWithCause("disbursement API unreachable: " + e, "DisbursementUnavailable", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw ApplicationFailure.newFailure("interrupted", "Interrupted");
        }

        int status = resp.statusCode();
        if (status >= 500 || status == 429) {
            throw ApplicationFailure.newFailure("disbursement API returned " + status, "DisbursementUnavailable");
        }
        if (status != 200 && status != 201) {
            fail(versionId, workflowId, "HTTP_" + status, resp.body());
            throw ApplicationFailure.newNonRetryableFailure("disbursement refused with " + status + ": " + resp.body(), "DisbursementRefused");
        }
        String reference = reference(resp.body());
        if (reference == null) {
            fail(versionId, workflowId, "MALFORMED_RESPONSE", resp.body());
            throw ApplicationFailure.newNonRetryableFailure("disbursement response is malformed", "DisbursementMalformed");
        }
        tx.executeWithoutResult(s -> {
            disbursements.completed(versionId, reference);
            audit.record("APPLICATION_VERSION", Long.toString(versionId), "DISBURSEMENT_COMPLETED",
                    WorkflowIds.systemActor(workflowId), null, Map.of("reference", reference, "amount", Money.format(p.amount())));
        });
        LOG.info("version {} disbursed {} to {} (reference {})", versionId, p.amount(), p.version().dealershipId(), reference);
        return reference;
    }

    private Payment prepare(long versionId) {
        VersionRow v;
        try {
            v = versions.lockLatest(versionId);
        } catch (SupersededException e) {
            LOG.info("version {} superseded; not disbursing", versionId);
            return null;
        }
        if (!"APPROVED".equals(v.status()) || !stips.pending(versionId).isEmpty()) {
            LOG.info("version {} is {} with {} pending stips; not disbursing", versionId, v.status(), stips.pending(versionId).size());
            return null;
        }
        Optional<DisbursementRepository.Disbursement> existing = disbursements.find(versionId);
        if (existing.isPresent() && "COMPLETED".equals(existing.get().status())) {
            return new Payment(v, existing.get().amount(), existing.get().reference());
        }
        BigDecimal amount = snapshots.load(versionId)
                .flatMap(l -> l.snapshot().document(DocumentType.RISC))
                .map(d -> d.fields().get("amount_financed"))
                .map(FieldValue::raw)
                .flatMap(Money::parse)
                .orElseThrow(() -> ApplicationFailure.newNonRetryableFailure(
                        "version " + versionId + " has no parseable RISC amount_financed", "RiscIncomplete"));
        disbursements.requested(versionId, amount, v.dealershipId());
        return new Payment(v, amount, null);
    }

    private void fail(long versionId, String workflowId, String reason, String body) {
        tx.executeWithoutResult(s -> {
            disbursements.failed(versionId);
            audit.record("APPLICATION_VERSION", Long.toString(versionId), "DISBURSEMENT_ERROR",
                    WorkflowIds.systemActor(workflowId), null, Map.of("reason", reason, "body", body == null ? "" : body));
        });
    }

    private static String reference(String body) {
        try {
            JsonNode n = JSON.readTree(body);
            String ref = n == null ? null : n.path("reference").asText(null);
            return ref == null || ref.isBlank() ? null : ref;
        } catch (IOException e) {
            return null;
        }
    }
}
