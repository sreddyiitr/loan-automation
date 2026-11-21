package com.funding.api.functional;

import static org.assertj.core.api.Assertions.assertThat;

import com.funding.api.seed.FixtureRows;
import com.funding.domain.ExternalApi;
import com.funding.seed.Fixture;
import com.funding.seed.SeedConfig;
import com.funding.seed.SeedGates;
import com.funding.workflows.ActivityOptionsConfig;
import com.funding.workflows.FundingInput;
import com.funding.workflows.FundingResult;
import com.funding.workflows.FundingState;
import com.funding.workflows.FundingWorkflow;
import com.funding.workflows.TaskQueues;
import com.funding.workflows.WorkflowIds;
import com.funding.workflows.Workers;
import com.funding.workflows.activities.DisbursementActivitiesImpl;
import com.funding.workflows.activities.FundingActivitiesImpl;
import com.funding.workflows.activities.IncomeActivitiesImpl;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import io.temporal.client.WorkflowStub;
import io.temporal.worker.WorkerFactory;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * T605, NFR-02: a worker that dies and comes back loses nothing. The test runs its own worker
 * factory (production registration and activities) on its own task queues, so killing it never
 * touches the app's workers that the other ITs use. It kills the factory ({@code shutdownNow}: no
 * graceful drain, cached workflow state dropped), waits, and starts a brand-new factory, which
 * rebuilds each run purely by replaying its history from the Temporal server.
 *
 * <p>The versions are written PENDING_REVIEW with their outbox row removed in the same transaction,
 * so the trigger path never starts a second run on the app's queues; the test starts the run itself.
 */
class WorkerRestartIT extends FunctionalTestBase {


    @Autowired private SeedGates gates;
    @Autowired private SeedConfig config;
    @Autowired private WorkflowClient client;
    @Autowired private TransactionTemplate tx;
    @Autowired private FundingActivitiesImpl db;
    @Autowired private DisbursementActivitiesImpl disbursement;
    @Autowired private IncomeActivitiesImpl income;
    @Autowired private ActivityOptionsConfig options;
    @Value("${funding.env}") private String env;

    private TaskQueues queues;
    private WorkerFactory workers;
    private MockControl mocks;

    @BeforeEach
    void startWorkers() {
        queues = TaskQueues.forEnv(env + "-restart");
        workers = newWorkers();
        mocks = new MockControl(env, FunctionalEnvironment.WIREMOCK_URL, config, gates.generate().fixtures());
    }

    @AfterEach
    void stopWorkers() {
        workers.shutdownNow();
        workers.awaitTermination(10, TimeUnit.SECONDS);
    }

    private WorkerFactory newWorkers() {
        WorkerFactory f = WorkerFactory.newInstance(client, Workers.factoryOptions());
        Workers.register(f, queues, db, disbursement, income, options); // production registration
        f.start();
        return f;
    }

    /** The worker process dies (no drain) and, after a pause, a fresh one starts. */
    private void killAndRestart(Duration down) throws InterruptedException {
        workers.shutdownNow();
        workers.awaitTermination(10, TimeUnit.SECONDS);
        assertThat(workers.isTerminated()).as("old worker gone").isTrue();
        Thread.sleep(down.toMillis()); // timers fire and tasks queue up with nobody polling
        workers = newWorkers();
    }

    private long start(String scenario, String suffix) throws Exception {
        Fixture clone = mocks.cloneScenario(scenario, suffix);
        long id = tx.execute(s -> {
            List<Long> ids = FixtureRows.insert(jdbc, gates, clone);
            long v = ids.get(clone.versions().indexOf(clone.latest()));
            jdbc.sql("UPDATE application_versions SET version_status = 'PENDING_REVIEW' WHERE application_version_id = ?").param(v).update();
            jdbc.sql("DELETE FROM outbox_events WHERE (payload->>'application_version_id')::bigint = ?").param(v).update();
            return v;
        });
        FundingWorkflow wf = client.newWorkflowStub(FundingWorkflow.class, WorkflowOptions.newBuilder()
                .setWorkflowId(WorkflowIds.parent(env, id)).setTaskQueue(queues.orchestration()).build());
        WorkflowClient.start(wf::run, new FundingInput(id, FundingInput.VALIDATE, queues));
        return id;
    }

    private FundingWorkflow stub(long id) {
        return client.newWorkflowStub(FundingWorkflow.class, WorkflowIds.parent(env, id));
    }

    private String result(long id) throws java.util.concurrent.TimeoutException {
        return WorkflowStub.fromTyped(stub(id)).getResult(3, TimeUnit.MINUTES, FundingResult.class).result();
    }

    private int count(String sql, long id) {
        return jdbc.sql(sql).param(id).query(Integer.class).single();
    }

    /** Mid fan-out: the INCOME child is retrying a flaky API when the worker dies; the run still funds, once. */
    @Test
    void NFR_02_killedMidFanOutCompletesWithTheSameOutcome() throws Exception {
        long id = start("LA-1017", "RESTART");
        String clone = "LA-1017-RESTART";
        // Kill right after a 503 lands: the next attempt is in its retry backoff, so no attempt dies with the worker.
        await(Duration.ofSeconds(30), () -> callsQuietly(clone) >= 2, "INCOME retrying");
        FundingState before = stub(id).getState();
        assertThat(before.step()).isEqualTo("VALIDATING");
        killAndRestart(Duration.ofSeconds(3));

        assertThat(result(id)).isEqualTo("FUNDED"); // LA-1017's expected status
        assertThat(jdbc.sql("SELECT validation_check || '=' || outcome FROM validation_results WHERE application_version_id = ? AND is_current ORDER BY 1")
                .param(id).query(String.class).list())
                .containsExactly("GAP=PASS", "INCOME=PASS", "PRICE=PASS", "TILA=PASS", "VIN=PASS");
        assertThat(count("SELECT count(*) FROM validation_results WHERE application_version_id = ?", id)).isEqualTo(5); // one each, no duplicates
        assertThat(count("SELECT count(*) FROM disbursements WHERE application_version_id = ? AND status = 'COMPLETED'", id)).isEqualTo(1);
        assertThat(mocks.calls(clone, ExternalApi.INCOME_VERIFICATION)).isEqualTo(options.maxAttempts()); // 503 x (max-1), then 200
    }

    /** Waiting on a signal: the run is rebuilt from history, answers queries, and acts on the funder's waiver. */
    @Test
    void NFR_02_killedWhileWaitingForAFunderStillActsOnTheSignal() throws Exception {
        long id = start("LA-1006", "RESTART");
        await(Duration.ofSeconds(30), () -> {
            FundingState s = state(id);
            return s != null && "WAITING_FOR_FUNDER".equals(s.step());
        }, "waiting for a funder");
        assertThat(status(id)).isEqualTo("STIPULATED");
        killAndRestart(Duration.ofSeconds(3));

        FundingState rebuilt = stub(id).getState(); // answered by the new worker after replaying the history
        assertThat(rebuilt.status()).isEqualTo("STIPULATED");
        assertThat(rebuilt.step()).isEqualTo("WAITING_FOR_FUNDER");
        long stip = jdbc.sql("SELECT stip_id FROM stipulations WHERE application_version_id = ?").param(id).query(Long.class).single();
        HttpResponse<String> r = HTTP.send(HttpRequest.newBuilder(URI.create(base() + "/api/v1/versions/" + id + "/stipulations/" + stip))
                .header("Content-Type", "application/json").header("Authorization", bearer())
                .method("PATCH", HttpRequest.BodyPublishers.ofString("{\"status\":\"WAIVED\",\"reason\":\"1 cent, dealer confirmed\"}")).build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
        assertThat(result(id)).isEqualTo("FUNDED");
        assertThat(count("SELECT count(*) FROM disbursements WHERE application_version_id = ? AND status = 'COMPLETED'", id)).isEqualTo(1);
    }

    private FundingState state(long id) {
        try {
            return stub(id).getState();
        } catch (RuntimeException e) {
            return null;
        }
    }

    private int callsQuietly(String scenarioId) {
        try {
            return mocks.calls(scenarioId, ExternalApi.INCOME_VERIFICATION);
        } catch (Exception e) {
            return 0;
        }
    }
}
