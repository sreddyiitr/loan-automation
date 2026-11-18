package com.funding.persistence;

import com.funding.domain.status.VersionStatus;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Application versions. Never selects {@code ssn_itin_encrypted} (FR-13). */
public class VersionRepository {

    private static final String COLUMNS = """
            application_version_id, loan_application_id, version_number, is_latest_version, version_status,
            submitted_at, borrower_name, co_borrower_name, stated_income_annual, vehicle_vin, dealership_name,
            dealership_id, assigned_funder""";

    private final JdbcClient jdbc;

    public VersionRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<VersionRow> find(long versionId) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM application_versions WHERE application_version_id = ?")
                .param(versionId).query(VersionRepository::map).optional();
    }

    public Optional<VersionRow> findLatest(String loanApplicationId) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM application_versions WHERE loan_application_id = ? AND is_latest_version")
                .param(loanApplicationId).query(VersionRepository::map).optional();
    }

    /**
     * Locks the row for the rest of the transaction and returns it only if it is still the latest
     * version (AS-9.3: the guard and the write share one transaction). Throws if superseded.
     */
    public VersionRow lockLatest(long versionId) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM application_versions WHERE application_version_id = ? FOR UPDATE")
                .param(versionId).query(VersionRepository::map).optional()
                .filter(VersionRow::latest)
                .orElseThrow(() -> new SupersededException(versionId));
    }

    /**
     * The only status writer. Call after {@link #lockLatest} with the locked row; the change must be
     * one spec §6 allows (FR-09), or {@link VersionStatus.IllegalTransitionException} is thrown and
     * nothing is written. The write is also conditional on the status still being {@code current}'s.
     */
    public void setStatus(VersionRow current, String next) {
        VersionStatus.requireTransition(current.status(), next);
        int n = jdbc.sql("UPDATE application_versions SET version_status = ? WHERE application_version_id = ? AND version_status = ?")
                .params(next, current.applicationVersionId(), current.status()).update();
        if (n != 1) {
            throw new IllegalStateException("version " + current.applicationVersionId() + " is no longer " + current.status()
                    + "; lock it with lockLatest before changing its status");
        }
    }

    /** AS-10.3: reassigns the funder; call after {@link #lockLatest}. */
    public void setAssignee(long versionId, String funderId) {
        jdbc.sql("UPDATE application_versions SET assigned_funder = ? WHERE application_version_id = ?").params(funderId, versionId).update();
    }

    static VersionRow map(ResultSet rs, int i) throws SQLException {
        return new VersionRow(
                rs.getLong("application_version_id"),
                rs.getString("loan_application_id"),
                rs.getInt("version_number"),
                rs.getBoolean("is_latest_version"),
                rs.getString("version_status"),
                rs.getObject("submitted_at", OffsetDateTime.class),
                rs.getString("borrower_name"),
                rs.getString("co_borrower_name"),
                rs.getBigDecimal("stated_income_annual"),
                rs.getString("vehicle_vin"),
                rs.getString("dealership_name"),
                rs.getString("dealership_id"),
                rs.getString("assigned_funder"));
    }
}
