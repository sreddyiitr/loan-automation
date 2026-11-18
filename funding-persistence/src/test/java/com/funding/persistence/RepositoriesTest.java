package com.funding.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.funding.domain.Check;
import com.funding.domain.CheckOutcome;
import com.funding.domain.DocumentType;
import com.funding.domain.Outcome;
import com.funding.domain.StipRequest;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

class RepositoriesTest {

    private JdbcClient jdbc;
    private long versionId;

    static ApplicationWriter.NewVersion version(int n, boolean latest, List<ApplicationWriter.NewDocument> docs) {
        return new ApplicationWriter.NewVersion(n, latest, "DRAFT", "Jordan Rivera", null, "enc:t:LA-1",
                new BigDecimal("96000.00"), "4T1C11AK7RU100101", "Lone Star Motors", "DLR-100", docs);
    }

    static ApplicationWriter.NewDocument risc(int v, boolean latest, String cashPrice, String confidence) {
        return new ApplicationWriter.NewDocument("RISC", v, latest, "risc.pdf", 5, List.of(
                new ApplicationWriter.NewSection("Itemization", 2, 3, List.of(
                        new ApplicationWriter.NewField("cash_price", cashPrice, new BigDecimal(confidence), List.of(1, 2, 3, 4), 2)))));
    }

    @BeforeEach
    void db() {
        jdbc = TestDatabase.fresh().jdbc();
        versionId = new ApplicationWriter(jdbc).insert("LA-1", List.of(version(1, true, List.of(
                risc(1, false, "11111.00", "0.99"), risc(2, true, "31450.00", "0.97"))))).get(0);
    }

    @Test
    void AS_9_4_snapshotReadsOnlyLatestDocuments() {
        var loaded = new SnapshotRepository(jdbc).load(versionId).orElseThrow();
        assertThat(loaded.snapshot().document(DocumentType.RISC).orElseThrow().fields().get("cash_price").raw())
                .isEqualTo("31450.00");
    }

    @Test
    void FR_13_snapshotOnlyKnowsWhetherAnSsnTokenExists() {
        var loaded = new SnapshotRepository(jdbc).load(versionId).orElseThrow();
        assertThat(loaded.snapshot().ssnTokenPresent()).isTrue();
        assertThat(loaded.snapshot().toString()).doesNotContain("enc:t:LA-1");
        assertThat(loaded.version().toString()).doesNotContain("enc:t:LA-1");
    }

    @Test
    void FR_07_stipCreationIsIdempotentPerVersionCodeAndCheck() {
        StipRepository stips = new StipRepository(jdbc);
        StipRequest s = new StipRequest("STIP_TILA_MATH_DISCREPANCY", "d");
        assertThat(stips.createIfAbsent(versionId, Check.TILA, s, null)).isNotNull();
        assertThat(stips.createIfAbsent(versionId, Check.TILA, s, null)).isNull(); // activity retry
        assertThat(stips.createIfAbsent(versionId, null, new StipRequest("STIP_PROOF_OF_INCOME", "m"), null)).isNotNull();
        assertThat(stips.createIfAbsent(versionId, null, new StipRequest("STIP_PROOF_OF_INCOME", "m"), null)).isNull();
        assertThat(stips.pending(versionId)).hasSize(2);
    }

    @Test
    void AS_8_8_autoSatisfyResolvesOnlyThatChecksStips() {
        StipRepository stips = new StipRepository(jdbc);
        stips.createIfAbsent(versionId, Check.TILA, new StipRequest("STIP_EXTRACTION_REVIEW_REQUIRED", "a"), null);
        stips.createIfAbsent(versionId, Check.PRICE, new StipRequest("STIP_EXTRACTION_REVIEW_REQUIRED", "b"), null);
        assertThat(stips.autoSatisfy(versionId, Check.TILA, "system:wf", "auto-resolved by re-run")).hasSize(1);
        assertThat(stips.pending(versionId)).extracting(StipRepository.Stip::check).containsExactly("PRICE");
    }

    @Test
    void FR_06_resultsKeepHistoryWithOneCurrent() {
        ResultRepository results = new ResultRepository(jdbc);
        CheckOutcome fail = new CheckOutcome(Check.TILA, Outcome.FAIL, List.of(), List.of(), List.of(), null);
        CheckOutcome pass = new CheckOutcome(Check.TILA, Outcome.PASS, List.of(), List.of(), List.of(), null);
        results.record(versionId, fail, 1, "run-1", Map.of());
        results.record(versionId, fail, 1, "run-1", Map.of()); // retried activity: no duplicate
        results.record(versionId, pass, 2, "run-1", Map.of());
        assertThat(results.current(versionId)).singleElement().satisfies(r -> {
            assertThat(r.get("outcome")).isEqualTo("PASS");
            assertThat(r.get("attempt")).isEqualTo(2);
        });
        assertThat(jdbc.sql("SELECT count(*) FROM validation_results").query(Integer.class).single()).isEqualTo(2);
    }

    @Test
    void AS_9_3_lockLatestRefusesSupersededVersion() {
        VersionRepository versions = new VersionRepository(jdbc);
        assertThat(versions.lockLatest(versionId).latest()).isTrue();
        jdbc.sql("UPDATE application_versions SET is_latest_version = FALSE WHERE application_version_id = ?").param(versionId).update();
        assertThatThrownBy(() -> versions.lockLatest(versionId)).isInstanceOf(SupersededException.class);
    }

    @Test
    void FR_09_setStatusRejectsAnIllegalTransitionAndWritesNothing() {
        VersionRepository versions = new VersionRepository(jdbc);
        VersionRow v = versions.lockLatest(versionId);
        String before = v.status();
        assertThatThrownBy(() -> versions.setStatus(v, "FUNDED"))
                .isInstanceOf(com.funding.domain.status.VersionStatus.IllegalTransitionException.class);
        assertThat(versions.find(versionId).orElseThrow().status()).isEqualTo(before);

        versions.setStatus(v, "PENDING_REVIEW");
        assertThat(versions.find(versionId).orElseThrow().status()).isEqualTo("PENDING_REVIEW");
        // A stale row (status changed since it was read) is refused rather than overwriting the newer status.
        assertThatThrownBy(() -> versions.setStatus(v, "PENDING_REVIEW")).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void outboxClaimMarkAndPurge() {
        OutboxRepository outbox = new OutboxRepository(jdbc);
        jdbc.sql("UPDATE application_versions SET version_status = 'PENDING_REVIEW' WHERE application_version_id = ?").param(versionId).update();
        List<OutboxRepository.Event> events = outbox.claimUnpublished(10);
        assertThat(events).hasSize(1);
        outbox.markPublished(events.get(0).eventId());
        assertThat(outbox.claimUnpublished(10)).isEmpty();
        jdbc.sql("UPDATE outbox_events SET published_at = CURRENT_TIMESTAMP - interval '8 days'").update();
        assertThat(outbox.purgePublishedOlderThanDays(7)).isEqualTo(1);
    }

    @Test
    void disbursementRowIsWrittenOnce() {
        DisbursementRepository d = new DisbursementRepository(jdbc);
        d.requested(versionId, new BigDecimal("28450.00"), "DLR-100");
        d.requested(versionId, new BigDecimal("28450.00"), "DLR-100");
        d.completed(versionId, "DSB-1");
        assertThat(d.find(versionId).orElseThrow().status()).isEqualTo("COMPLETED");
        assertThat(jdbc.sql("SELECT count(*) FROM disbursements").query(Integer.class).single()).isEqualTo(1);
    }
}
