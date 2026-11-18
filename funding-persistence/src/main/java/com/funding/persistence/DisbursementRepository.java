package com.funding.persistence;

import java.math.BigDecimal;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Disbursement idempotency record (US-8): one row per version, never two payments. */
public class DisbursementRepository {

    public record Disbursement(long versionId, String reference, BigDecimal amount, String dealershipId, String status) {}

    private final JdbcClient jdbc;

    public DisbursementRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<Disbursement> find(long versionId) {
        return jdbc.sql("SELECT application_version_id, external_reference, amount, dealership_id, status FROM disbursements WHERE application_version_id = ?")
                .param(versionId)
                .query((rs, i) -> new Disbursement(rs.getLong(1), rs.getString(2), rs.getBigDecimal(3), rs.getString(4), rs.getString(5)))
                .optional();
    }

    public void requested(long versionId, BigDecimal amount, String dealershipId) {
        jdbc.sql("INSERT INTO disbursements (application_version_id, amount, dealership_id, status) VALUES (?, ?, ?, 'REQUESTED') ON CONFLICT (application_version_id) DO NOTHING")
                .params(versionId, amount, dealershipId).update();
    }

    public void completed(long versionId, String reference) {
        jdbc.sql("UPDATE disbursements SET status = 'COMPLETED', external_reference = ?, completed_at = CURRENT_TIMESTAMP WHERE application_version_id = ?")
                .params(reference, versionId).update();
    }

    public void failed(long versionId) {
        jdbc.sql("UPDATE disbursements SET status = 'FAILED' WHERE application_version_id = ? AND status <> 'COMPLETED'")
                .param(versionId).update();
    }
}
