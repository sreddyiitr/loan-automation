package com.funding.workflows.activities;

import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import com.funding.domain.income.IncomeVerification;
import com.funding.persistence.TestDatabase;
import com.funding.seed.mock.MockBudget;
import com.funding.seed.mock.MockStub;
import com.funding.workflows.ActivityOptionsConfig;
import com.funding.workflows.IncomeActivities;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import io.temporal.activity.ActivityOptions;
import io.temporal.client.WorkflowOptions;
import io.temporal.failure.ActivityFailure;
import io.temporal.failure.ApplicationFailure;
import io.temporal.testing.TestWorkflowEnvironment;
import io.temporal.worker.Worker;
import io.temporal.workflow.Workflow;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * T304: the real {@code verifyIncome} activity (DB token lookup, HTTP client, classifier) against
 * in-JVM WireMock loaded with the generated stubs, run under the Temporal retry policy in a
 * time-skipping test environment. Stubs are generated from this test's short budget (1 s external
 * timeout, the production retry count) so timeout faults stay derived from the budget (P-5.2).
 */
class IncomeActivityTest {

    @RegisterExtension
    static WireMockExtension wm = WireMockExtension.newInstance().options(wireMockConfig().dynamicPort()).build();

    private static final MockBudget BUDGET = new MockBudget(
            Integer.parseInt(Fixtures.CONFIG.property("funding.activity.retry.max-attempts")), Duration.ofSeconds(1), 5);
    /** Temporal's start-to-close is above the HTTP timeout, so a timeout is always the client's (as in production, where they race). */
    private static final ActivityOptionsConfig OPTIONS = new ActivityOptionsConfig(Duration.ofSeconds(5), Duration.ofSeconds(3),
            Duration.ofSeconds(1), 2.0, Duration.ofSeconds(10), BUDGET.maxAttempts());
    private static final String QUEUE = "income-activity-test";

    private static List<MockStub> stubs;
    private static String wireMockImport;
    private static JdbcClient jdbc;

    private static long versionId;
    private TestWorkflowEnvironment env;

    @BeforeAll
    static void generate() {
        stubs = Fixtures.stubs(BUDGET);
        wireMockImport = Fixtures.wireMockImport(BUDGET);
        jdbc = TestDatabase.fresh().jdbc();
        versionId = Fixtures.insert(jdbc, "LA-1012");
    }

    @BeforeEach
    void setUp() throws Exception {
        HttpResponse<String> r = HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create(wm.baseUrl() + "/__admin/mappings/import"))
                .POST(HttpRequest.BodyPublishers.ofString(wireMockImport)).build(), HttpResponse.BodyHandlers.ofString());
        assertThat(r.statusCode()).isEqualTo(200);
        env = TestWorkflowEnvironment.newInstance();
        Worker w = env.newWorker(QUEUE);
        w.registerWorkflowImplementationTypes(CallIncomeImpl.class);
        w.registerActivitiesImplementations(activity(OPTIONS.maximumInterval()));
        env.start();
    }

    @AfterEach
    void tearDown() {
        if (env != null) {
            env.close();
        }
        assertThat(wm.findAllUnmatchedRequests()).isEmpty(); // P-5.7
    }

    private IncomeActivitiesImpl activity(Duration maxRetryDelay) {
        return new IncomeActivitiesImpl(Fixtures.ENV, jdbc, wm.baseUrl(), BUDGET.startToClose(), maxRetryDelay);
    }

    /** Points this test's version at a generated catalog stub (by its token) and returns that token. */
    private String useStub(String stubId) {
        MockStub s = stubs.stream().filter(x -> x.id().equals(stubId)).findFirst()
                .orElseThrow(() -> new AssertionError("generator did not produce " + stubId));
        String token = s.attributes().get("token");
        jdbc.sql("UPDATE application_versions SET ssn_itin_encrypted = ? WHERE application_version_id = ?").param(token).param(versionId).update();
        return token;
    }

    private int calls(String token) {
        return wm.findAll(postRequestedFor(urlPathEqualTo(IncomeActivitiesImpl.PATH))
                .withRequestBody(matchingJsonPath("$.ssnItinEncrypted", equalTo(token)))).size();
    }

    private Result run() {
        return env.getWorkflowClient().newWorkflowStub(CallIncome.class, WorkflowOptions.newBuilder()
                .setTaskQueue(QUEUE).setWorkflowId("income-activity-" + versionId + "-" + System.nanoTime()).build()).call(versionId);
    }

    @Test
    void AS_5_3_5xxRetriedWithBackoffThenError() {
        String token = useStub("INCOME_VERIFICATION/catalog/EXHAUSTED/96000.00");
        Result r = run();
        assertThat(r.failureType()).isEqualTo("IncomeUnavailable");
        assertThat(calls(token)).isEqualTo(BUDGET.maxAttempts());
        // Exponential backoff between the attempts: 1 + 2 + 4 + 8 s for five attempts.
        long backoff = 0;
        for (int i = 0; i < BUDGET.maxAttempts() - 1; i++) {
            backoff += (long) (OPTIONS.initialInterval().toMillis() * Math.pow(OPTIONS.backoff(), i));
        }
        assertThat(r.elapsedMillis()).isGreaterThanOrEqualTo(backoff);
    }

    @Test
    void AS_5_3_5xxThenOkSucceedsOnTheLastAttempt() {
        String token = useStub("INCOME_VERIFICATION/catalog/RETRY_THEN_OK/96000.00/k" + (BUDGET.maxAttempts() - 1));
        Result r = run();
        assertThat(r.failureType()).isNull();
        assertThat(r.status()).isEqualTo(IncomeVerification.VERIFIED);
        assertThat(calls(token)).isEqualTo(BUDGET.maxAttempts());
    }

    @Test
    void AS_5_3_timeoutRetried() {
        String token = useStub("INCOME_VERIFICATION/catalog/TIMEOUT_THEN_OK/96000.00");
        Result r = run();
        assertThat(r.failureType()).isNull();
        assertThat(r.status()).isEqualTo(IncomeVerification.VERIFIED);
        assertThat(calls(token)).isEqualTo(2);
    }

    @Test
    void AS_5_3_timeoutOnEveryAttemptIsError() {
        String token = useStub("INCOME_VERIFICATION/catalog/TIMEOUT_ALWAYS/96000.00");
        Result r = run();
        assertThat(r.failureType()).isEqualTo("IncomeUnavailable");
        assertThat(calls(token)).isEqualTo(BUDGET.maxAttempts());
    }

    @Test
    void connectionResetIsRetried() {
        String token = useStub("INCOME_VERIFICATION/catalog/CONNECTION_RESET/96000.00");
        Result r = run();
        assertThat(r.status()).isEqualTo(IncomeVerification.VERIFIED);
        assertThat(calls(token)).isEqualTo(2);
    }

    @Test
    void C_13_429HonorsRetryAfterCappedAtMaxInterval() {
        String id = "INCOME_VERIFICATION/catalog/RATE_LIMITED/96000.00";
        Duration retryAfter = Duration.ofSeconds(Long.parseLong(stubs.stream().filter(x -> x.id().equals(id)).findFirst().orElseThrow()
                .attributes().get("retryAfterSeconds")));
        useStub(id);

        // Below the cap: the server's Retry-After is used as the next retry delay.
        ApplicationFailure honoured = catchThrowableOfType(ApplicationFailure.class, () -> activity(Duration.ofMinutes(1)).verifyIncome(versionId));
        assertThat(honoured.isNonRetryable()).isFalse();
        assertThat(honoured.getNextRetryDelay()).isEqualTo(retryAfter);

        // Above the cap: capped at the retry policy's maximum interval.
        wm.resetScenarios();
        Duration cap = retryAfter.minusSeconds(1);
        ApplicationFailure capped = catchThrowableOfType(ApplicationFailure.class, () -> activity(cap).verifyIncome(versionId));
        assertThat(capped.getNextRetryDelay()).isEqualTo(cap);

        // Under Temporal: the second attempt (200) runs after the Retry-After delay, not the 1 s initial interval.
        wm.resetScenarios();
        Result r = run();
        assertThat(r.status()).isEqualTo(IncomeVerification.VERIFIED);
        assertThat(r.elapsedMillis()).isGreaterThanOrEqualTo(retryAfter.toMillis());
    }

    @Test
    void malformedAndSchemaInvalidAreErrorNotRetried() {
        for (String cls : List.of("MALFORMED", "SCHEMA_INVALID")) {
            String token = useStub("INCOME_VERIFICATION/catalog/" + cls + "/96000.00");
            Result r = run();
            assertThat(r.failureType()).as(cls).isEqualTo("IncomeRejected");
            assertThat(calls(token)).as(cls).isEqualTo(1);
        }
    }

    @Test
    void clientErrorIsErrorNotRetried() {
        for (String status : List.of("400", "422")) {
            String token = useStub("INCOME_VERIFICATION/catalog/CLIENT_ERROR/96000.00/" + status);
            Result r = run();
            assertThat(r.failureType()).as(status).isEqualTo("IncomeRejected");
            assertThat(calls(token)).as(status).isEqualTo(1);
        }
    }

    @Test
    void C_12_notFoundIsAnAnswerNotAFailure() {
        String token = useStub("INCOME_VERIFICATION/catalog/NOT_FOUND/96000.00");
        Result r = run();
        assertThat(r.status()).isEqualTo(IncomeVerification.NOT_FOUND);
        assertThat(calls(token)).isEqualTo(1);
    }

    @Test
    void missingTokenFailsWithoutCallingTheApi() {
        jdbc.sql("UPDATE application_versions SET ssn_itin_encrypted = NULL WHERE application_version_id = ?").param(versionId).update();
        Result r = run();
        assertThat(r.failureType()).isEqualTo("IncomeNoToken");
        assertThat(wm.findAll(postRequestedFor(urlPathEqualTo(IncomeActivitiesImpl.PATH)))).isEmpty();
    }

    // ------------------------------------------------------------- test workflow

    /** What the activity returned, or the type of the failure it ended with, plus workflow time spent. */
    public record Result(String status, BigDecimal verified, String failureType, long elapsedMillis) {}

    @WorkflowInterface
    public interface CallIncome {
        @WorkflowMethod
        Result call(long versionId);
    }

    public static class CallIncomeImpl implements CallIncome {
        @Override
        public Result call(long versionId) {
            ActivityOptions o = OPTIONS.external(QUEUE);
            IncomeActivities api = Workflow.newActivityStub(IncomeActivities.class, o);
            long start = Workflow.currentTimeMillis();
            try {
                IncomeVerification v = api.verifyIncome(versionId);
                return new Result(v.status(), v.totalVerifiedAnnualIncome(), null, Workflow.currentTimeMillis() - start);
            } catch (ActivityFailure f) {
                String type = f.getCause() instanceof ApplicationFailure af ? af.getType() : f.getCause().getClass().getSimpleName();
                return new Result(null, null, type, Workflow.currentTimeMillis() - start);
            }
        }
    }
}
