package com.funding.persistence;

import com.funding.domain.Check;
import com.funding.domain.StipRequest;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Stipulations. Creation is idempotent per (version, code, check) (FR-07). */
public class StipRepository {

    public record Stip(long stipId, long versionId, String check, String code, String description, String status) {}

    private final JdbcClient jdbc;

    public StipRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Returns the stip id if a new PENDING stip was created, or {@code null} if one already existed. */
    public Long createIfAbsent(long versionId, Check check, StipRequest stip, Long documentId) {
        return jdbc.sql("""
                INSERT INTO stipulations (application_version_id, triggered_by_document_id, validation_check, stip_code, description)
                VALUES (?, ?, ?, ?, ?)
                ON CONFLICT (application_version_id, stip_code, validation_check) WHERE status = 'PENDING' DO NOTHING
                RETURNING stip_id""")
                .params(versionId, documentId, check == null ? null : check.name(), stip.code(), stip.description())
                .query(Long.class).optional().orElse(null);
    }

    public List<Stip> forVersion(long versionId) {
        return jdbc.sql("SELECT stip_id, application_version_id, validation_check, stip_code, description, status FROM stipulations WHERE application_version_id = ? ORDER BY stip_id")
                .param(versionId)
                .query((rs, i) -> new Stip(rs.getLong(1), rs.getLong(2), rs.getString(3), rs.getString(4), rs.getString(5), rs.getString(6)))
                .list();
    }

    public List<Stip> pending(long versionId) {
        return forVersion(versionId).stream().filter(s -> "PENDING".equals(s.status())).toList();
    }

    /** AS-8.8: a check that now passes resolves its own PENDING stips. Returns the resolved stips. */
    public List<Stip> autoSatisfy(long versionId, Check check, String actor, String note) {
        return jdbc.sql("""
                UPDATE stipulations SET status = 'SATISFIED', resolved_by = ?, resolution_note = ?, resolved_at = CURRENT_TIMESTAMP
                 WHERE application_version_id = ? AND validation_check = ? AND status = 'PENDING'
                RETURNING stip_id, application_version_id, validation_check, stip_code, description, status""")
                .params(actor, note, versionId, check.name())
                .query((rs, i) -> new Stip(rs.getLong(1), rs.getLong(2), rs.getString(3), rs.getString(4), rs.getString(5), rs.getString(6)))
                .list();
    }
}
