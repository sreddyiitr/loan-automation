package com.funding.workflows.activities;

import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.classic.spi.ThrowableProxyUtil;
import ch.qos.logback.core.read.ListAppender;
import com.funding.domain.Check;
import com.funding.domain.RulesConfig;
import com.funding.persistence.TestDatabase;
import com.funding.seed.mock.MockBudget;
import com.funding.workflows.ActivityOptionsConfig;
import com.funding.workflows.FundingInput;
import com.funding.workflows.FundingWorkflow;
import com.funding.workflows.TaskQueues;
import com.funding.workflows.WorkflowIds;
import com.funding.workflows.Workers;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import io.temporal.testing.TestWorkflowEnvironment;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * T306 / AS-5.4: the encrypted SSN/ITIN reaches the Income API and nothing else. Runs the parent
 * workflow with the real DB, income and disbursement activities over three income paths (FAIL with
 * details, flaky retries, API down to ERROR) and searches every log event, every workflow history
 * (parent and children) and every {@code validation_results.details} row for the token.
 */
class SsnNeverLeaksTest {

    @RegisterExtension
    static WireMockExtension wm = WireMockExtension.newInstance().options(wireMockConfig().dynamicPort()).build();

    private static final ActivityOptionsConfig OPTIONS = ActivityOptionsConfig.forTests();
    /** Stubs come from the same budget the workflow runs with (P-5.2). */
    private static final MockBudget BUDGET = new MockBudget(OPTIONS.maxAttempts(), OPTIONS.externalStartToClose(), 5);
    private static final TaskQueues QUEUES = TaskQueues.forEnv(Fixtures.ENV);
    private static final List<String> SCENARIOS = List.of("LA-1012", "LA-1017", "LA-1016");

    private final ListAppender<ILoggingEvent> logs = new ListAppender<>();
    private final Logger root = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
    private ch.qos.logback.classic.Level rootLevel;
    private final List<ch.qos.logback.core.Appender<ILoggingEvent>> consoleAppenders = new ArrayList<>();
    private TestWorkflowEnvironment env;
    private JdbcClient jdbc;

    @BeforeEach
    void setUp() throws Exception {
        HttpResponse<String> r = HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create(wm.baseUrl() + "/__admin/mappings/import"))
                .POST(HttpRequest.BodyPublishers.ofString(Fixtures.wireMockImport(BUDGET))).build(), HttpResponse.BodyHandlers.ofString());
        assertThat(r.statusCode()).isEqualTo(200);
        TestDatabase t = TestDatabase.fresh();
        jdbc = t.jdbc();
        TransactionTemplate tx = new TransactionTemplate(new DataSourceTransactionManager(t.dataSource()));

        env = TestWorkflowEnvironment.newInstance();
        Workers.register(env.getWorkerFactory(), QUEUES,
                new FundingActivitiesImpl(Fixtures.ENV, jdbc, tx, RulesConfig::defaults, Duration.ofDays(30)),
                new DisbursementActivitiesImpl(Fixtures.ENV, jdbc, tx, wm.baseUrl(), OPTIONS.externalStartToClose()),
                new IncomeActivitiesImpl(jdbc, wm.baseUrl(), OPTIONS.externalStartToClose(), OPTIONS.maximumInterval()),
                OPTIONS);
        env.start();
    }

    /** Every level, so a DEBUG/TRACE line that leaks the token is caught even though the default config hides it. */
    private void captureLogs() {
        rootLevel = root.getLevel();
        root.iteratorForAppenders().forEachRemaining(consoleAppenders::add);
        consoleAppenders.forEach(root::detachAppender); // TRACE goes to the list only, not the build output
        root.setLevel(ch.qos.logback.classic.Level.TRACE);
        logs.start();
        root.addAppender(logs);
    }

    @AfterEach
    void tearDown() {
        root.detachAppender(logs);
        if (rootLevel != null) {
            root.setLevel(rootLevel);
        }
        consoleAppenders.forEach(root::addAppender);
        env.close();
        assertThat(wm.findAllUnmatchedRequests()).isEmpty();
    }

    @Test
    void AS_5_4_ssnNeverInLogsHistoryOrDetails() {
        WorkflowClient client = env.getWorkflowClient();
        Map<String, Long> ids = new java.util.LinkedHashMap<>();
        for (String scenario : SCENARIOS) {
            long id = Fixtures.insert(jdbc, scenario);
            jdbc.sql("UPDATE application_versions SET version_status = 'PENDING_REVIEW' WHERE application_version_id = ?").param(id).update();
            ids.put(scenario, id);
        }
        // Capture from here: the test's own fixture inserts bind the token as a JDBC parameter (Spring logs those at TRACE).
        captureLogs();
        ids.values().forEach(id -> {
            FundingWorkflow wf = client.newWorkflowStub(FundingWorkflow.class, WorkflowOptions.newBuilder()
                    .setWorkflowId(WorkflowIds.parent(Fixtures.ENV, id)).setTaskQueue(QUEUES.orchestration()).build());
            WorkflowClient.start(wf::run, new FundingInput(id, FundingInput.VALIDATE, QUEUES));
        });
        // STIPULATED and held versions keep their parent open (signals arrive in S4): wait for every INCOME result instead.
        long deadline = System.currentTimeMillis() + 60_000;
        while (jdbc.sql("SELECT count(*) FROM validation_results WHERE validation_check = 'INCOME' AND is_current").query(Integer.class).single() < SCENARIOS.size()) {
            assertThat(System.currentTimeMillis()).as("INCOME results for " + SCENARIOS).isLessThan(deadline);
            env.sleep(Duration.ofSeconds(1));
        }

        for (Map.Entry<String, Long> e : ids.entrySet()) {
            String token = Fixtures.scenario(e.getKey()).latest().ssnItinToken();
            String parentId = WorkflowIds.parent(Fixtures.ENV, e.getValue());

            // Positive control: the token was really sent to the Income API (so its absence elsewhere means something).
            assertThat(wm.findAll(postRequestedFor(urlPathEqualTo(IncomeActivitiesImpl.PATH))
                    .withRequestBody(matchingJsonPath("$.ssnItinEncrypted", equalTo(token))))).as(e.getKey()).isNotEmpty();

            List<String> histories = new ArrayList<>();
            histories.add(client.fetchHistory(parentId).toJson(false));
            for (Check c : Check.values()) {
                histories.add(client.fetchHistory(WorkflowIds.child(parentId, c, 1)).toJson(false));
            }
            assertThat(histories).as(e.getKey() + " workflow histories").noneMatch(h -> h.contains(token));

            assertThat(jdbc.sql("SELECT details::text FROM validation_results WHERE application_version_id = ?")
                    .param(e.getValue()).query(String.class).list()).as(e.getKey() + " validation_results").isNotEmpty()
                    .noneMatch(d -> d.contains(token));

            assertThat(logs.list).as(e.getKey() + " log events").noneMatch(ev -> rendered(ev).contains(token));
        }
        assertThat(logs.list).as("captured logs").isNotEmpty();
    }

    private static String rendered(ILoggingEvent ev) {
        StringBuilder b = new StringBuilder(ev.getFormattedMessage()).append(ev.getMDCPropertyMap());
        IThrowableProxy t = ev.getThrowableProxy();
        if (t != null) {
            b.append(ThrowableProxyUtil.asString(t));
        }
        return b.toString();
    }
}
