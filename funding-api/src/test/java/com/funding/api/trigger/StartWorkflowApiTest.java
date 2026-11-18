package com.funding.api.trigger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.funding.persistence.ApplicationWriter;
import com.funding.persistence.TestDatabase;
import com.funding.workflows.FundingInput;
import com.funding.workflows.FundingResult;
import com.funding.workflows.FundingState;
import com.funding.workflows.FundingWorkflow;
import com.funding.workflows.TaskQueues;
import io.temporal.testing.TestWorkflowEnvironment;
import io.temporal.workflow.Workflow;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** T122: start-workflow API contract (plan "Start-workflow API"), AS-1.1/1.2/1.3/1.6/1.7. */
class StartWorkflowApiTest {

    private static final String KEY = "test-key";
    private static final TaskQueues QUEUES = TaskQueues.forEnv("apitest");
    static final AtomicInteger RUNS = new AtomicInteger();

    /** Stand-in workflow: counts runs and waits, so a run stays open. */
    public static class WaitingWorkflow implements FundingWorkflow {
        private boolean done;
        @Override public FundingResult run(FundingInput input) { RUNS.incrementAndGet(); Workflow.await(() -> done); return new FundingResult("X"); }
        @Override public void stipResolved() { done = true; }
        @Override public void approve(String actor) { }
        @Override public void reject(String actor, String reason) { }
        @Override public void rerunChecks(java.util.List<com.funding.domain.Check> checks) { }
        @Override public void retryDisbursement(String actor) { }
        @Override public FundingState getState() { return new FundingState("WAITING_FOR_FUNDER", "STIPULATED", Map.of(), Map.of(), 0L, false); }
    }

    private TestWorkflowEnvironment temporal;
    private JdbcClient jdbc;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        RUNS.set(0);
        temporal = TestWorkflowEnvironment.newInstance();
        temporal.newWorker(QUEUES.orchestration()).registerWorkflowImplementationTypes(WaitingWorkflow.class);
        temporal.start();
        TestDatabase db = TestDatabase.fresh();
        jdbc = db.jdbc();
        StartWorkflowService service = new StartWorkflowService(jdbc, temporal.getWorkflowClient(), "apitest", QUEUES);
        mvc = MockMvcBuilders.standaloneSetup(new StartWorkflowController(service))
                .addFilters(new TriggerApiKeyFilter(KEY))
                .setControllerAdvice(new com.funding.api.ProblemHandler())
                .build();
    }

    @AfterEach
    void tearDown() {
        temporal.close();
    }

    private long version(String loanId, int number, boolean latest, String status) {
        jdbc.sql("INSERT INTO loan_applications (loan_application_id) VALUES (?) ON CONFLICT DO NOTHING").param(loanId).update();
        return new ApplicationWriter(jdbc).insertVersion(loanId, new ApplicationWriter.NewVersion(number, latest, status, "B", null,
                "enc:x", new BigDecimal("1.00"), "4T1C11AK7RU100101", "D", "DLR-100", List.of()));
    }

    private org.springframework.test.web.servlet.ResultActions start(long versionId, String key) throws Exception {
        var req = post("/api/v1/funding-workflows").contentType(MediaType.APPLICATION_JSON).content("""
                {"eventType":"APPLICATION_VERSION_PENDING_REVIEW","loanApplicationId":"LA-1","applicationVersionId":%d,
                 "documentId":null,"sourceMessageId":"m-1"}""".formatted(versionId));
        if (key != null) {
            req.header("X-Trigger-Api-Key", key);
        }
        return mvc.perform(req);
    }

    @Test
    void AS_1_1_startsWorkflow202() throws Exception {
        long v = version("LA-1", 1, true, "PENDING_REVIEW");
        start(v, KEY).andExpect(status().isAccepted())
                .andExpect(jsonPath("$.workflowId").value("funding-apitest-" + v))
                .andExpect(jsonPath("$.runId").isNotEmpty());
        temporal.sleep(java.time.Duration.ofSeconds(1));
        assertThat(RUNS.get()).isEqualTo(1);
    }

    @Test
    void AS_1_2_duplicateStartReturnsExistingRun() throws Exception {
        long v = version("LA-1", 1, true, "PENDING_REVIEW");
        String first = start(v, KEY).andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString();
        String runId = com.jayway.jsonpath.JsonPath.read(first, "$.runId");
        start(v, KEY).andExpect(status().isOk())
                .andExpect(jsonPath("$.alreadyStarted").value(true))
                .andExpect(jsonPath("$.runId").value(runId));
        temporal.sleep(java.time.Duration.ofSeconds(1));
        assertThat(RUNS.get()).isEqualTo(1);
    }

    @Test
    void AS_1_3_redeliveryAfterSettlingReturns200AlreadyStarted() throws Exception {
        long v = version("LA-1", 1, true, "PENDING_REVIEW");
        start(v, KEY).andExpect(status().isAccepted());
        // The workflow has settled the version to STIPULATED and is waiting; SQS redelivers.
        jdbc.sql("UPDATE application_versions SET version_status = 'STIPULATED' WHERE application_version_id = ?").param(v).update();
        start(v, KEY).andExpect(status().isOk()).andExpect(jsonPath("$.alreadyStarted").value(true));
    }

    @Test
    void AS_1_6_notLatestVersion409() throws Exception {
        long v1 = version("LA-1", 1, false, "PENDING_REVIEW");
        version("LA-1", 2, true, "DRAFT");
        start(v1, KEY).andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.reason").value("NOT_LATEST_VERSION"));
        assertThat(RUNS.get()).isZero();
    }

    @Test
    void AS_1_6_notPendingReview409() throws Exception {
        long v = version("LA-1", 1, true, "FUNDED");
        start(v, KEY).andExpect(status().isConflict()).andExpect(jsonPath("$.reason").value("NOT_PENDING_REVIEW"));
        assertThat(RUNS.get()).isZero();
    }

    @Test
    void AS_1_7_missingOrBadApiKey401() throws Exception {
        long v = version("LA-1", 1, true, "PENDING_REVIEW");
        start(v, null).andExpect(status().isUnauthorized());
        start(v, "wrong").andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
        assertThat(RUNS.get()).isZero();
    }

    @Test
    void malformedPayload400() throws Exception {
        mvc.perform(post("/api/v1/funding-workflows").header("X-Trigger-Api-Key", KEY)
                .contentType(MediaType.APPLICATION_JSON).content("{\"eventType\":\"NOPE\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
        mvc.perform(post("/api/v1/funding-workflows").header("X-Trigger-Api-Key", KEY)
                .contentType(MediaType.APPLICATION_JSON).content("{not json"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void getReturnsRunStatusAndState() throws Exception {
        long v = version("LA-1", 1, true, "PENDING_REVIEW");
        start(v, KEY).andExpect(status().isAccepted());
        temporal.sleep(java.time.Duration.ofSeconds(1));
        mvc.perform(get("/api/v1/funding-workflows/" + v).header("X-Trigger-Api-Key", KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.workflowId").value("funding-apitest-" + v))
                .andExpect(jsonPath("$.runStatus").value("RUNNING"))
                .andExpect(jsonPath("$.state.step").value("WAITING_FOR_FUNDER"));
    }
}
