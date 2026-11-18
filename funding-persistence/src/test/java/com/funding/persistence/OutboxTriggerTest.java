package com.funding.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

/** T107: the DB trigger writes exactly one outbox row on first submission (FR-02). */
class OutboxTriggerTest {

    private JdbcClient jdbc;
    private int versionId;

    @BeforeEach
    void db() {
        jdbc = TestDatabase.fresh().jdbc();
        jdbc.sql("INSERT INTO loan_applications (loan_application_id) VALUES ('LA-1001')").update();
        versionId = jdbc.sql("""
                INSERT INTO application_versions (loan_application_id, borrower_name, vehicle_vin, dealership_name, dealership_id)
                VALUES ('LA-1001', 'B', '4T1C11AK7RU100101', 'D', 'DLR-100') RETURNING application_version_id""")
                .query(Integer.class).single();
    }

    private void setStatus(String s) {
        jdbc.sql("UPDATE application_versions SET version_status = ? WHERE application_version_id = ?").params(s, versionId).update();
    }

    private List<Map<String, Object>> outbox() {
        return jdbc.sql("SELECT event_type, payload::text AS payload FROM outbox_events ORDER BY event_id").query().listOfRows();
    }

    @Test
    void AS_1_1_pendingReviewWritesOneOutboxRow() {
        assertThat(outbox()).isEmpty(); // inserted as DRAFT
        setStatus("PENDING_REVIEW");
        List<Map<String, Object>> rows = outbox();
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).get("event_type")).isEqualTo("APPLICATION_VERSION_PENDING_REVIEW");
        assertThat((String) rows.get(0).get("payload"))
                .contains("\"loan_application_id\": \"LA-1001\"")
                .contains("\"application_version_id\": " + versionId);
    }

    @Test
    void AS_1_4_otherTransitionsWriteNoOutboxRow() {
        setStatus("PENDING_REVIEW");
        setStatus("STIPULATED");
        setStatus("PENDING_REVIEW"); // re-run: no new workflow, no new message
        setStatus("APPROVED");
        setStatus("FUNDED");
        assertThat(outbox()).hasSize(1);
    }

    @Test
    void AS_8_6_submittedAtStampedOnceAndNeverOverwritten() {
        setStatus("PENDING_REVIEW");
        OffsetDateTime first = submittedAt();
        assertThat(first).isNotNull();
        setStatus("STIPULATED");
        setStatus("PENDING_REVIEW");
        assertThat(submittedAt()).isEqualTo(first);
    }

    private OffsetDateTime submittedAt() {
        return jdbc.sql("SELECT submitted_at FROM application_versions WHERE application_version_id = ?")
                .param(versionId).query(OffsetDateTime.class).single();
    }
}
