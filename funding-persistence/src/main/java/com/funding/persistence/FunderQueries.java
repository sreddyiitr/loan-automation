package com.funding.persistence;

import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Read models for the funder API (US-10). Rows are column-name maps; JSONB columns come back as
 * text and the API parses them. Never selects {@code ssn_itin_encrypted} (FR-13).
 */
public class FunderQueries {

    private final JdbcClient jdbc;

    public FunderQueries(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** US-9: every version of an application, newest first. */
    public List<Map<String, Object>> versions(String loanApplicationId) {
        return jdbc.sql("""
                SELECT application_version_id, version_number, is_latest_version, version_status, submitted_at, assigned_funder, created_at
                  FROM application_versions WHERE loan_application_id = ? ORDER BY version_number DESC""")
                .param(loanApplicationId).query().listOfRows();
    }

    /** AS-10.2: stips with their resolution. */
    public List<Map<String, Object>> stips(long versionId) {
        return jdbc.sql("""
                SELECT s.stip_id, s.validation_check, s.stip_code, s.description, s.status, s.resolution_note, s.resolved_by,
                       s.created_at, s.resolved_at, s.triggered_by_document_id, d.document_type AS triggered_by_document_type
                  FROM stipulations s LEFT JOIN documents d ON d.document_id = s.triggered_by_document_id
                 WHERE s.application_version_id = ? ORDER BY s.stip_id""")
                .param(versionId).query().listOfRows();
    }

    /** AS-10.4: current results, or every attempt with {@code history}. */
    public List<Map<String, Object>> results(long versionId, boolean history) {
        return jdbc.sql("""
                SELECT result_id, validation_check, outcome, details::text AS details, attempt, is_current, evaluated_at
                  FROM validation_results WHERE application_version_id = ?""" + (history ? "" : " AND is_current")
                + " ORDER BY validation_check, attempt")
                .param(versionId).query().listOfRows();
    }

    /** AS-10.5: every document version per type, newest first within a type. */
    public List<Map<String, Object>> documents(long versionId) {
        return jdbc.sql("""
                SELECT document_id, document_type, document_version_number, is_latest_doc, file_name, total_pages, processed_at
                  FROM documents WHERE application_version_id = ? ORDER BY document_type, document_version_number DESC""")
                .param(versionId).query().listOfRows();
    }

    /** AS-10.5: one document's fields with section, page, confidence and bounding box. Empty if the document isn't the version's. */
    public List<Map<String, Object>> fields(long versionId, long documentId) {
        return jdbc.sql("""
                SELECT s.section_name, s.start_page, s.end_page, f.field_id, f.field_key, f.field_value, f.confidence_score,
                       f.bounding_box::text AS bounding_box, f.page_number
                  FROM documents d JOIN document_sections s USING (document_id) JOIN extracted_fields f USING (section_id)
                 WHERE d.application_version_id = ? AND d.document_id = ? ORDER BY s.start_page, f.field_id""")
                .params(versionId, documentId).query().listOfRows();
    }

    public boolean documentBelongsTo(long versionId, long documentId) {
        return jdbc.sql("SELECT count(*) FROM documents WHERE application_version_id = ? AND document_id = ?")
                .params(versionId, documentId).query(Integer.class).single() > 0;
    }

    /** FR-10: the version's own audit rows and those of its stips, oldest first. */
    public List<Map<String, Object>> audit(long versionId) {
        return jdbc.sql("""
                SELECT audit_id, entity_type, entity_id, action, actor, before_state::text AS before_state,
                       after_state::text AS after_state, created_at
                  FROM audit_log
                 WHERE (entity_type = 'APPLICATION_VERSION' AND entity_id = ?)
                    OR (entity_type = 'STIPULATION' AND entity_id IN
                          (SELECT stip_id::text FROM stipulations WHERE application_version_id = ?))
                    OR (entity_type = 'DOCUMENT' AND entity_id IN
                          (SELECT document_id::text FROM documents WHERE application_version_id = ?))
                 ORDER BY audit_id""")
                .params(Long.toString(versionId), versionId, versionId).query().listOfRows();
    }

    /** The current outcome per check (for the re-run default and the AS-8.10 TILA condition). */
    public Map<String, String> currentOutcomes(long versionId) {
        Map<String, String> m = new java.util.TreeMap<>();
        jdbc.sql("SELECT validation_check, outcome FROM validation_results WHERE application_version_id = ? AND is_current")
                .param(versionId).query((rs, i) -> m.put(rs.getString(1), rs.getString(2))).list();
        return m;
    }
}
