package com.funding.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.simple.JdbcClient;

/** T209: AS-9.7, documents are refused at the DB level once the version is APPROVED or final. */
class DocumentFreezeTest {

    private JdbcClient jdbc;
    private long versionId;
    private int nextDocVersion = 2;

    @BeforeEach
    void db() {
        jdbc = TestDatabase.fresh().jdbc();
        versionId = new ApplicationWriter(jdbc).insert("LA-1", List.of(new ApplicationWriter.NewVersion(1, true, "DRAFT", "B", null,
                "enc:x", new BigDecimal("1.00"), "4T1C11AK7RU100101", "D", "DLR-1", List.of()))).get(0);
    }

    private void status(String s) {
        jdbc.sql("UPDATE application_versions SET version_status = ? WHERE application_version_id = ?").params(s, versionId).update();
    }

    private void addRisc() {
        jdbc.sql("UPDATE documents SET is_latest_doc = FALSE WHERE application_version_id = ? AND document_type = 'RISC'").param(versionId).update();
        new ApplicationWriter(jdbc).insertDocument(versionId,
                new ApplicationWriter.NewDocument("RISC", nextDocVersion++, true, "risc-v2.pdf", 5, List.of()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"APPROVED", "FUNDED", "REJECTED", "EXPIRED"})
    void AS_9_7_insertRefusedWhenApprovedOrFinal(String frozen) {
        status("PENDING_REVIEW");
        status(frozen);
        assertThatThrownBy(this::addRisc)
                .hasMessageContaining("DOCUMENTS_FROZEN")
                .hasRootCauseInstanceOf(org.postgresql.util.PSQLException.class)
                .satisfies(e -> assertThat(((SQLException) rootSql(e)).getSQLState()).isEqualTo("P0001"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"DRAFT", "PENDING_REVIEW", "STIPULATED"})
    void AS_9_7_insertAllowedWhileUnderReview(String open) {
        if (!"DRAFT".equals(open)) {
            status("PENDING_REVIEW");
            status(open);
        }
        assertThatCode(this::addRisc).doesNotThrowAnyException();
    }

    private static Throwable rootSql(Throwable t) {
        Throwable c = t;
        while (c.getCause() != null) {
            c = c.getCause();
        }
        return c;
    }
}
