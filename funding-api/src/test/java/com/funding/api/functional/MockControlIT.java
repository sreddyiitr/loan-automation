package com.funding.api.functional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.funding.domain.ExternalApi;
import com.funding.domain.income.IncomeVerification;
import com.funding.seed.SeedConfig;
import com.funding.seed.SeedGates;
import com.funding.workflows.activities.IncomeActivitiesImpl;
import io.temporal.failure.ApplicationFailure;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;

/** T313: {@link MockControl} swaps a scenario to another generated intent, restores it, and counts calls. */
class MockControlIT extends FunctionalTestBase {

    private static final String SCENARIO = "LA-1013"; // default income intent ok-100k

    @Autowired private SeedGates gates;
    @Autowired private SeedConfig config;
    @Autowired private IncomeActivitiesImpl income;
    @Value("${funding.env}") private String env;

    private MockControl mocks;
    private long versionId;

    @BeforeEach
    void setUp() {
        mocks = new MockControl(env, FunctionalEnvironment.WIREMOCK_URL, config, gates.generate().fixtures());
        versionId = jdbc.sql("""
                SELECT v.application_version_id FROM application_versions v
                 WHERE v.loan_application_id = ? AND v.is_latest_version""").param(SCENARIO).query(Long.class).single();
    }

    @AfterEach
    void restore() throws Exception {
        mocks.reset(SCENARIO, ExternalApi.INCOME_VERIFICATION);
    }

    @Test
    void rejectsAnIntentTheGeneratorDoesNotProduce() {
        assertThatThrownBy(() -> mocks.swap(SCENARIO, ExternalApi.INCOME_VERIFICATION, "hand-written-200"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("not in income-verification.catalog.yaml");
        assertThatThrownBy(() -> mocks.swap(SCENARIO, ExternalApi.DISBURSEMENT, "TEAPOT"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void swapResetAndVerifyCalls() throws Exception {
        int before = mocks.calls(SCENARIO, ExternalApi.INCOME_VERIFICATION);

        mocks.swap(SCENARIO, ExternalApi.INCOME_VERIFICATION, "down");
        assertThatThrownBy(() -> income.verifyIncome(versionId)).isInstanceOf(ApplicationFailure.class)
                .satisfies(t -> assertThat(((ApplicationFailure) t).isNonRetryable()).isFalse());

        mocks.swap(SCENARIO, ExternalApi.INCOME_VERIFICATION, "not-found");
        assertThat(income.verifyIncome(versionId).status()).isEqualTo(IncomeVerification.NOT_FOUND);

        mocks.reset(SCENARIO, ExternalApi.INCOME_VERIFICATION);
        assertThat(income.verifyIncome(versionId).status()).isEqualTo(IncomeVerification.VERIFIED);

        assertThat(mocks.calls(SCENARIO, ExternalApi.INCOME_VERIFICATION)).isEqualTo(before + 3);
        assertThat(unmatchedForThisEnv()).isZero();
    }

    @Test
    void swappingAMultiCallIntentStartsItsSequenceAgain() throws Exception {
        mocks.swap(SCENARIO, ExternalApi.INCOME_VERIFICATION, "flaky");
        assertThatThrownBy(() -> income.verifyIncome(versionId)).isInstanceOf(ApplicationFailure.class); // first 503
        mocks.swap(SCENARIO, ExternalApi.INCOME_VERIFICATION, "flaky");
        assertThatThrownBy(() -> income.verifyIncome(versionId)).isInstanceOf(ApplicationFailure.class); // 503 again, not call #2
    }
}
