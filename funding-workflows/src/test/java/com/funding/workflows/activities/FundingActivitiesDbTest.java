package com.funding.workflows.activities;

import static org.assertj.core.api.Assertions.assertThat;

import com.funding.domain.Check;
import com.funding.domain.CheckOutcome;
import com.funding.domain.RuleEngine;
import com.funding.domain.RulesConfig;
import com.funding.persistence.AuditRepository;
import com.funding.persistence.DisbursementRepository;
import com.funding.persistence.ResultRepository;
import com.funding.persistence.StipRepository;
import com.funding.persistence.TestDatabase;
import com.funding.workflows.VersionView;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.EnumSet;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;

/** T128: activities against Postgres 18 and the generated WireMock stubs (no hand-written stub). */
class FundingActivitiesDbTest {

    @RegisterExtension
    static WireMockExtension wm = WireMockExtension.newInstance().options(wireMockConfig().dynamicPort()).build();

    private JdbcClient jdbc;
    private javax.sql.DataSource dataSource;
    private FundingActivitiesImpl db;
    private DisbursementActivitiesImpl disbursement;
    private final RuleEngine engine = new RuleEngine();

    @BeforeEach
    void setUp() throws Exception {
        TestDatabase t = TestDatabase.fresh();
        jdbc = t.jdbc();
        dataSource = t.dataSource();
        TransactionTemplate tx = new TransactionTemplate(new DataSourceTransactionManager(t.dataSource()));
        db = new FundingActivitiesImpl(Fixtures.ENV, jdbc, tx, RulesConfig::defaults, EnumSet.of(Check.TILA), Duration.ofDays(30));
        disbursement = new DisbursementActivitiesImpl(Fixtures.ENV, jdbc, tx, wm.baseUrl(), Duration.ofSeconds(5));
        HttpResponse<String> r = HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create(wm.baseUrl() + "/__admin/mappings/import"))
                .POST(HttpRequest.BodyPublishers.ofString(Fixtures.wireMockImport())).build(), HttpResponse.BodyHandlers.ofString());
        assertThat(r.statusCode()).isEqualTo(200);
    }

    @AfterEach
    void P_5_7_noUnmatchedRequests() {
        assertThat(wm.findAllUnmatchedRequests()).isEmpty();
    }

    private long submitted(String scenario) {
        long id = Fixtures.insert(jdbc, scenario);
        jdbc.sql("UPDATE application_versions SET version_status = 'PENDING_REVIEW' WHERE application_version_id = ?").param(id).update();
        return id;
    }

    private CheckOutcome evaluateTila(long id) {
        VersionView v = db.loadVersionSnapshot(id);
        return engine.evaluate(Check.TILA, v.snapshot(), v.config());
    }

    private String status(long id) {
        return jdbc.sql("SELECT version_status FROM application_versions WHERE application_version_id = ?").param(id).query(String.class).single();
    }

    @Test
    void AS_8_1_happyPathApprovesDisbursesOnceAndFunds() {
        long id = submitted("LA-1001");
        CheckOutcome tila = evaluateTila(id);
        assertThat(db.recordOutcome(id, tila, 1)).isTrue();
        assertThat(db.applyOutcome(id, Map.of(Check.TILA, tila))).isEqualTo("APPROVED");

        String ref = disbursement.disburse(id);
        assertThat(ref).startsWith("DSB-");
        // Idempotent: a retried activity doesn't pay twice (no second HTTP call, one row).
        assertThat(disbursement.disburse(id)).isEqualTo(ref);
        assertThat(wm.findAll(com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor(
                com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo(DisbursementActivitiesImpl.PATH)))).hasSize(1);

        DisbursementRepository.Disbursement d = new DisbursementRepository(jdbc).find(id).orElseThrow();
        assertThat(d.amount()).isEqualByComparingTo("28450.00"); // AS-8.9: RISC amount_financed
        assertThat(d.dealershipId()).isEqualTo("DLR-100");
        assertThat(d.status()).isEqualTo("COMPLETED");

        assertThat(db.markFunded(id)).isEqualTo("FUNDED");
        assertThat(new AuditRepository(jdbc).forEntity("APPLICATION_VERSION", Long.toString(id)))
                .filteredOn(e -> e.action().equals("STATUS_CHANGED"))
                .extracting(AuditRepository.Entry::actor).containsOnly("system:funding-test-" + id);
    }

    @Test
    void AS_8_2_tilaFailCreatesStipAndStipulates() {
        long id = submitted("LA-1002");
        CheckOutcome tila = evaluateTila(id);
        db.recordOutcome(id, tila, 1);
        db.recordOutcome(id, tila, 1); // activity retry: still one stip, one result
        assertThat(new StipRepository(jdbc).pending(id)).singleElement().satisfies(s -> {
            assertThat(s.code()).isEqualTo("STIP_TILA_MATH_DISCREPANCY");
            assertThat(s.check()).isEqualTo("TILA");
        });
        assertThat(db.applyOutcome(id, Map.of(Check.TILA, tila))).isEqualTo("STIPULATED");
        assertThat(disbursement.disburse(id)).isNull(); // not APPROVED: no call
        assertThat(new ResultRepository(jdbc).current(id)).singleElement()
                .satisfies(r -> assertThat((String) r.get("details")).contains("33802.60"));
    }

    @Test
    void AS_9_3_noWritesForSupersededVersion() {
        long id = submitted("LA-1002");
        CheckOutcome tila = evaluateTila(id);
        jdbc.sql("UPDATE application_versions SET is_latest_version = FALSE WHERE application_version_id = ?").param(id).update();
        assertThat(db.recordOutcome(id, tila, 1)).isFalse();
        assertThat(db.applyOutcome(id, Map.of(Check.TILA, tila))).isEqualTo("SUPERSEDED");
        assertThat(jdbc.sql("SELECT count(*) FROM validation_results").query(Integer.class).single()).isZero();
        assertThat(jdbc.sql("SELECT count(*) FROM stipulations").query(Integer.class).single()).isZero();
        assertThat(status(id)).isEqualTo("PENDING_REVIEW");
    }

    /** T504 race: V2 is created while V1's checks are in flight; V1's write waits for the lock, then refuses. */
    @Test
    void AS_9_3_activitiesRefuseWritesForNonLatest() throws Exception {
        long v1 = submitted("LA-1002");
        CheckOutcome tila = evaluateTila(v1); // V1's check ran on V1's snapshot
        TransactionTemplate tx = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        java.util.concurrent.CountDownLatch v2Created = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        Thread resubmission = new Thread(() -> tx.executeWithoutResult(s -> {
            new com.funding.persistence.ApplicationWriter(jdbc).addVersion("LA-1002",
                    new com.funding.persistence.ApplicationWriter.NewVersion(0, false, "DRAFT", "B", null, "enc:x",
                            new java.math.BigDecimal("1.00"), "4T1C11AK7RU100101", "D", "DLR-100", java.util.List.of()));
            v2Created.countDown();
            try {
                release.await(10, java.util.concurrent.TimeUnit.SECONDS); // V1's row stays locked until V2 commits
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }));
        resubmission.start();
        assertThat(v2Created.await(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        java.util.concurrent.CompletableFuture<Boolean> write = java.util.concurrent.CompletableFuture.supplyAsync(() -> db.recordOutcome(v1, tila, 1));
        Thread.sleep(300); // the write is now waiting on V1's row lock
        assertThat(write).isNotDone();
        release.countDown();
        resubmission.join(10_000);

        assertThat(write.get(10, java.util.concurrent.TimeUnit.SECONDS)).isFalse(); // re-read after the lock: no longer latest
        assertThat(db.applyOutcome(v1, Map.of(Check.TILA, tila))).isEqualTo("SUPERSEDED");
        assertThat(jdbc.sql("SELECT count(*) FROM validation_results").query(Integer.class).single()).isZero();
        assertThat(jdbc.sql("SELECT count(*) FROM stipulations").query(Integer.class).single()).isZero();
        assertThat(status(v1)).isEqualTo("PENDING_REVIEW");
    }

    private Map<Check, CheckOutcome> evaluateAll(long id) {
        VersionView v = db.loadVersionSnapshot(id);
        Map<Check, CheckOutcome> out = new java.util.EnumMap<>(Check.class);
        for (Check c : java.util.List.of(Check.TILA, Check.PRICE, Check.VIN, Check.GAP)) {
            CheckOutcome o = engine.evaluate(c, v.snapshot(), v.config());
            db.recordOutcome(id, o, 1);
            out.put(c, o);
        }
        return out;
    }

    @Test
    void T206_snapshotCarriesTheGapRegistry() {
        long id = submitted("LA-1001");
        assertThat(db.loadVersionSnapshot(id).snapshot().gapForms()).extracting(com.funding.domain.GapForm::formNumber)
                .containsExactlyInAnyOrder("GAP-TX-2024-01", "GAP-TX-2019-07", "GAP-CA-2025-03");
        assertThat(evaluateAll(id).get(Check.GAP).outcome()).isEqualTo(com.funding.domain.Outcome.PASS);
    }

    @Test
    void AS_8_10_autoApprovalBlockedWhenRiscIncomplete() {
        long id = submitted("LA-1001");
        // A required RISC field no enabled check reads: TILA passes, but the RISC is incomplete.
        jdbc.sql("UPDATE extracted_fields SET confidence_score = 0.50 WHERE field_key = 'vehicle_make' AND section_id IN "
                + "(SELECT s.section_id FROM document_sections s JOIN documents d USING (document_id) WHERE d.document_type = 'RISC')").update();
        CheckOutcome tila = evaluateTila(id);
        assertThat(tila.outcome()).isEqualTo(com.funding.domain.Outcome.PASS);
        db.recordOutcome(id, tila, 1);
        assertThat(db.applyOutcome(id, Map.of(Check.TILA, tila))).isEqualTo("PENDING_REVIEW");
        assertThat(disbursement.disburse(id)).isNull();
        assertThat(new AuditRepository(jdbc).forEntity("APPLICATION_VERSION", Long.toString(id)))
                .noneMatch(e -> e.after() != null && e.after().contains("APPROVED"));
    }

    @Test
    void FR_07_sharedStipCodeIsPerCheck() {
        long id = submitted("LA-1001");
        // Flag a field TILA reads (apr) and one VIN reads (Buyer's Order vin): both checks raise EXTRACTION_REVIEW.
        jdbc.sql("UPDATE extracted_fields f SET confidence_score = 0.50 FROM document_sections s JOIN documents d USING (document_id) "
                + "WHERE f.section_id = s.section_id AND ((d.document_type = 'RISC' AND f.field_key = 'apr') "
                + "OR (d.document_type = 'BUYERS_ORDER' AND f.field_key = 'vin'))").update();
        Map<Check, CheckOutcome> outcomes = evaluateAll(id);
        assertThat(new StipRepository(jdbc).pending(id))
                .extracting(StipRepository.Stip::check, StipRepository.Stip::code)
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple("TILA", "STIP_EXTRACTION_REVIEW_REQUIRED"),
                        org.assertj.core.groups.Tuple.tuple("VIN", "STIP_EXTRACTION_REVIEW_REQUIRED"));
        assertThat(db.applyOutcome(id, outcomes)).isEqualTo("STIPULATED");
    }

    @Test
    void AS_8_8_passingRerunAutoSatisfiesItsStip() {
        long id = submitted("LA-1002");
        db.recordOutcome(id, evaluateTila(id), 1);
        db.applyOutcome(id, Map.of(Check.TILA, evaluateTila(id)));
        // Corrected RISC value (S5 will do this through a new document version).
        jdbc.sql("UPDATE extracted_fields SET field_value = '33792.60' WHERE field_key = 'total_of_payments'").update();
        CheckOutcome pass = evaluateTila(id);
        db.recordOutcome(id, pass, 2);
        assertThat(db.applyOutcome(id, Map.of(Check.TILA, pass))).isEqualTo("APPROVED");
        assertThat(new StipRepository(jdbc).forVersion(id)).singleElement().satisfies(s -> assertThat(s.status()).isEqualTo("SATISFIED"));
    }

    @Test
    void AS_8_6_expireIsAuditedKeepsStipsAndIsTerminal() {
        long id = submitted("LA-1002");
        CheckOutcome tila = evaluateTila(id);
        db.recordOutcome(id, tila, 1);
        assertThat(db.applyOutcome(id, Map.of(Check.TILA, tila))).isEqualTo("STIPULATED");
        assertThat(db.loadVersionSnapshot(id).expiresAtEpochMillis() - db.loadVersionSnapshot(id).submittedAtEpochMillis())
                .isEqualTo(Duration.ofDays(30).toMillis());

        assertThat(db.expire(id)).isEqualTo("EXPIRED");
        assertThat(status(id)).isEqualTo("EXPIRED");
        assertThat(new StipRepository(jdbc).pending(id)).singleElement() // PENDING stips stay as history
                .satisfies(st -> assertThat(st.code()).isEqualTo("STIP_TILA_MATH_DISCREPANCY"));
        assertThat(new AuditRepository(jdbc).forEntity("APPLICATION_VERSION", Long.toString(id)))
                .filteredOn(e -> e.after() != null && e.after().contains("EXPIRED"))
                .singleElement().satisfies(e -> {
                    assertThat(e.actor()).isEqualTo("system:funding-test-" + id);
                    assertThat(e.after()).contains("EXPIRED_AFTER_P30D");
                });
        assertThat(db.expire(id)).isEqualTo("EXPIRED"); // retried activity: no second transition
        assertThat(db.applyOutcome(id, Map.of(Check.TILA, tila))).isEqualTo("EXPIRED"); // terminal
    }

    @Test
    void AS_8_7_manualApproveOverridingErrorStaysApproved() {
        long id = submitted("LA-1001");
        CheckOutcome error = CheckOutcome.error(Check.INCOME, "income API down");
        db.recordOutcome(id, error, 1);
        assertThat(db.applyOutcome(id, Map.of(Check.INCOME, error))).isEqualTo("PENDING_REVIEW");
        jdbc.sql("UPDATE application_versions SET version_status = 'APPROVED' WHERE application_version_id = ?").param(id).update(); // funder API
        assertThat(db.applyOutcome(id, Map.of(Check.INCOME, error))).isEqualTo("APPROVED");
    }

    @Test
    void FR_09_illegalTransitionIsRefused() {
        long id = submitted("LA-1001");
        assertThat(FundingActivitiesImpl.iso(Duration.ofDays(30))).isEqualTo("P30D");
        assertThat(FundingActivitiesImpl.iso(Duration.ofSeconds(20))).isEqualTo("PT20S");
        jdbc.sql("UPDATE application_versions SET version_status = 'DRAFT' WHERE application_version_id = ?").param(id).update();
        assertThat(org.assertj.core.api.Assertions.catchThrowable(() -> db.expire(id)))
                .isInstanceOf(com.funding.domain.status.VersionStatus.IllegalTransitionException.class);
        assertThat(status(id)).isEqualTo("DRAFT");
    }

    @Test
    void AS_10_7_noResultsOrStipsAfterTheVersionClosed() {
        long id = submitted("LA-1002");
        jdbc.sql("UPDATE application_versions SET version_status = 'REJECTED' WHERE application_version_id = ?").param(id).update();
        assertThat(db.recordOutcome(id, evaluateTila(id), 1)).isFalse(); // rejected while TILA was still running
        assertThat(jdbc.sql("SELECT count(*) FROM validation_results").query(Integer.class).single()).isZero();
        assertThat(jdbc.sql("SELECT count(*) FROM stipulations").query(Integer.class).single()).isZero();
    }
}
