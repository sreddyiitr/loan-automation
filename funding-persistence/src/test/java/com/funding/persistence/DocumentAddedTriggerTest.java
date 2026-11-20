package com.funding.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * T501: AS-1.8, a new latest document on the latest version under review writes one
 * {@code DOCUMENT_VERSION_ADDED} outbox row in the same transaction; AS-9.4, the old document
 * loses its latest flag. (Testcontainers Postgres, so it runs with the unit tests.)
 */
class DocumentAddedTriggerTest {

    private JdbcClient jdbc;
    private ApplicationWriter writer;
    private long versionId;

    @BeforeEach
    void db() {
        jdbc = TestDatabase.fresh().jdbc();
        writer = new ApplicationWriter(jdbc);
        versionId = writer.insert("LA-1", List.of(RepositoriesTest.version(1, true, List.of(
                RepositoriesTest.risc(1, true, "31450.00", "0.97"))))).get(0);
    }

    private void status(String s) {
        jdbc.sql("UPDATE application_versions SET version_status = ? WHERE application_version_id = ?").params(s, versionId).update();
    }

    private ApplicationWriter.AddedDocument addRiscV2() {
        return writer.addDocumentVersion(versionId, RepositoriesTest.risc(0, false, "31450.00", "0.99"));
    }

    private List<Map<String, Object>> documentEvents() {
        return jdbc.sql("SELECT payload::text AS payload FROM outbox_events WHERE event_type = 'DOCUMENT_VERSION_ADDED' ORDER BY event_id")
                .query().listOfRows();
    }

    @ParameterizedTest
    @ValueSource(strings = {"PENDING_REVIEW", "STIPULATED"})
    void AS_1_8_newLatestDocOnPendingReviewOrStipulatedWritesEvent(String underReview) {
        status("PENDING_REVIEW");
        status(underReview);
        ApplicationWriter.AddedDocument added = addRiscV2();
        assertThat(documentEvents()).singleElement().satisfies(r -> assertThat((String) r.get("payload"))
                .contains("\"event_type\": \"DOCUMENT_VERSION_ADDED\"")
                .contains("\"loan_application_id\": \"LA-1\"")
                .contains("\"application_version_id\": " + versionId)
                .contains("\"document_id\": " + added.documentId()));
    }

    @Test
    void AS_1_8_noEventForDraft() {
        addRiscV2();
        assertThat(documentEvents()).isEmpty();
    }

    @Test
    void AS_1_8_noEventForNonLatestVersionOrNonLatestDocument() {
        status("PENDING_REVIEW");
        // a non-latest document insert (an OCR backfill of an old document version) doesn't trigger
        jdbc.sql("UPDATE documents SET document_version_number = 2 WHERE application_version_id = ?").param(versionId).update();
        writer.insertDocument(versionId, RepositoriesTest.risc(1, false, "11111.00", "0.99"));
        assertThat(documentEvents()).isEmpty();
        // nor does a new document on a version that is no longer latest
        writer.addVersion("LA-1", RepositoriesTest.version(0, false, List.of()));
        addRiscV2();
        assertThat(documentEvents()).isEmpty();
    }

    @Test
    void AS_9_4_oldDocLosesLatestFlag() {
        status("PENDING_REVIEW");
        ApplicationWriter.AddedDocument added = addRiscV2();
        assertThat(added.documentVersion()).isEqualTo(2);
        List<Map<String, Object>> docs = jdbc.sql("""
                SELECT document_version_number AS n, is_latest_doc AS latest FROM documents
                 WHERE application_version_id = ? AND document_type = 'RISC' ORDER BY 1""").param(versionId).query().listOfRows();
        assertThat(docs).extracting(r -> r.get("n"), r -> r.get("latest"))
                .containsExactly(org.assertj.core.groups.Tuple.tuple(1, false), org.assertj.core.groups.Tuple.tuple(2, true));
        // and the snapshot reads only the new one
        assertThat(new SnapshotRepository(jdbc).load(versionId).orElseThrow().snapshot()
                .document(com.funding.domain.DocumentType.RISC).orElseThrow().documentId()).isEqualTo(added.documentId());
    }

    @Test
    void latestDocumentsReadsBackWhatWasWritten() {
        List<ApplicationWriter.NewDocument> docs = writer.latestDocuments(versionId);
        assertThat(docs).singleElement().satisfies(d -> {
            assertThat(d.type()).isEqualTo("RISC");
            assertThat(d.sections()).singleElement().satisfies(s -> assertThat(s.fields()).singleElement().satisfies(f -> {
                assertThat(f.key()).isEqualTo("cash_price");
                assertThat(f.value()).isEqualTo("31450.00");
                assertThat(f.confidence()).isEqualByComparingTo(new BigDecimal("0.97"));
                assertThat(f.boundingBox()).containsExactly(1, 2, 3, 4);
                assertThat(f.page()).isEqualTo(2);
            }));
        });
    }
}
