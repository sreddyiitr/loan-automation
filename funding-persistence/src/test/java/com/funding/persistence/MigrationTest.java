package com.funding.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;

/** T106: Flyway baseline applies, and the partial unique indexes and CHECKs enforce the data model. */
class MigrationTest {

    private JdbcClient jdbc;

    @BeforeEach
    void db() {
        jdbc = TestDatabase.fresh().jdbc();
        jdbc.sql("INSERT INTO loan_applications (loan_application_id) VALUES ('LA-1')").update();
    }

    private int version(int number, boolean latest) {
        return jdbc.sql("""
                INSERT INTO application_versions (loan_application_id, version_number, is_latest_version,
                  borrower_name, vehicle_vin, dealership_name, dealership_id)
                VALUES ('LA-1', ?, ?, 'B', '4T1C11AK7RU100101', 'D', 'DLR-1') RETURNING application_version_id""")
                .params(number, latest).query(Integer.class).single();
    }

    @Test
    void onlyOneLatestVersionPerApplication() {
        version(1, true);
        assertThatThrownBy(() -> version(2, true)).isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("idx_unique_active_app_version");
        version(2, false);
    }

    @Test
    void onlyOneLatestDocumentPerTypePerVersion() {
        int v = version(1, true);
        String doc = "INSERT INTO documents (application_version_id, document_type, document_version_number, is_latest_doc, file_name, total_pages) VALUES (?, 'RISC', ?, ?, 'f', 1)";
        jdbc.sql(doc).params(v, 1, true).update();
        assertThatThrownBy(() -> jdbc.sql(doc).params(v, 2, true).update())
                .hasMessageContaining("idx_unique_active_doc_version");
        assertThatThrownBy(() -> jdbc.sql(doc).params(v, 1, false).update())
                .hasMessageContaining("idx_unique_doc_version_number");
    }

    @Test
    void FR_07_onePendingStipPerVersionCodeAndCheckIncludingManual() {
        int v = version(1, true);
        String stip = "INSERT INTO stipulations (application_version_id, validation_check, stip_code, description) VALUES (?, ?, ?, 'd')";
        jdbc.sql(stip).params(v, "TILA", "STIP_TILA_MATH_DISCREPANCY").update();
        assertThatThrownBy(() -> jdbc.sql(stip).params(v, "TILA", "STIP_TILA_MATH_DISCREPANCY").update())
                .hasMessageContaining("idx_unique_pending_stip");
        // Same code from another check is a separate stip.
        jdbc.sql(stip).params(v, "PRICE", "STIP_EXTRACTION_REVIEW_REQUIRED").update();
        jdbc.sql(stip).params(v, "TILA", "STIP_EXTRACTION_REVIEW_REQUIRED").update();
        // Manual stips (NULL check) are unique per code too (NULLS NOT DISTINCT).
        jdbc.sql(stip).params(v, null, "STIP_PROOF_OF_INCOME").update();
        assertThatThrownBy(() -> jdbc.sql(stip).params(v, null, "STIP_PROOF_OF_INCOME").update())
                .hasMessageContaining("idx_unique_pending_stip");
    }

    @Test
    void FR_06_oneCurrentResultPerVersionAndCheck() {
        int v = version(1, true);
        String r = "INSERT INTO validation_results (application_version_id, validation_check, outcome, details, attempt, is_current) VALUES (?, 'TILA', 'PASS', '{}', ?, ?)";
        jdbc.sql(r).params(v, 1, true).update();
        assertThatThrownBy(() -> jdbc.sql(r).params(v, 2, true).update()).hasMessageContaining("idx_current_result");
        jdbc.sql(r).params(v, 2, false).update();
    }

    @Test
    void FR_09_unknownStatusIsRejected() {
        int v = version(1, true);
        assertThatThrownBy(() -> jdbc.sql("UPDATE application_versions SET version_status = 'DONE' WHERE application_version_id = ?")
                .param(v).update()).hasMessageContaining("version_status");
    }

    @Test
    void B4_noIsFlaggedColumn() {
        Integer n = jdbc.sql("SELECT count(*) FROM information_schema.columns WHERE table_name = 'extracted_fields' AND column_name = 'is_flagged'")
                .query(Integer.class).single();
        assertThat(n).isZero();
    }
}
