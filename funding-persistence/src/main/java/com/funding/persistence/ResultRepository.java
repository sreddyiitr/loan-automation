package com.funding.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.funding.domain.CheckOutcome;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;

/** One row per (version, check, attempt); exactly one {@code is_current} per (version, check) (FR-06). */
public class ResultRepository {

    private static final ObjectMapper JSON = new ObjectMapper();
    private final JdbcClient jdbc;

    public ResultRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Idempotent per (version, check, attempt), so an activity retry doesn't duplicate the row. */
    public void record(long versionId, CheckOutcome outcome, int attempt, String workflowRunId, Map<String, Long> documentIds) {
        Integer existing = jdbc.sql("SELECT count(*) FROM validation_results WHERE application_version_id = ? AND validation_check = ? AND attempt = ?")
                .params(versionId, outcome.check().name(), attempt).query(Integer.class).single();
        if (existing > 0) {
            return;
        }
        jdbc.sql("UPDATE validation_results SET is_current = FALSE WHERE application_version_id = ? AND validation_check = ? AND is_current")
                .params(versionId, outcome.check().name()).update();
        jdbc.sql("""
                INSERT INTO validation_results (application_version_id, validation_check, outcome, details, attempt, is_current, workflow_run_id)
                VALUES (?, ?, ?, ?::jsonb, ?, TRUE, ?)""")
                .params(versionId, outcome.check().name(), outcome.outcome().name(), details(outcome, documentIds), attempt, workflowRunId)
                .update();
    }

    public List<Map<String, Object>> current(long versionId) {
        return jdbc.sql("SELECT validation_check, outcome, attempt, details::text AS details FROM validation_results WHERE application_version_id = ? AND is_current ORDER BY validation_check")
                .param(versionId).query().listOfRows();
    }

    static String details(CheckOutcome o, Map<String, Long> documentIds) {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("comparisons", o.comparisons());
        d.put("flagged_fields", o.flaggedFields());
        d.put("document_ids", documentIds);
        if (o.error() != null) {
            d.put("error", o.error());
        }
        try {
            return JSON.writeValueAsString(d);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}
