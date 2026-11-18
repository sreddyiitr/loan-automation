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
    private FundingActivitiesImpl db;
    private DisbursementActivitiesImpl disbursement;
    private final RuleEngine engine = new RuleEngine();

    @BeforeEach
    void setUp() throws Exception {
        TestDatabase t = TestDatabase.fresh();
        jdbc = t.jdbc();
        TransactionTemplate tx = new TransactionTemplate(new DataSourceTransactionManager(t.dataSource()));
        db = new FundingActivitiesImpl(Fixtures.ENV, jdbc, tx, RulesConfig::defaults, EnumSet.of(Check.TILA));
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
}
