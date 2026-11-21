package com.funding.api.funder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.funding.api.ProblemHandler;
import com.funding.api.auth.ActorArgumentResolver;
import com.funding.api.seed.SeedDataLoader;
import com.funding.domain.Check;
import com.funding.domain.CheckOutcome;
import com.funding.domain.Role;
import com.funding.domain.RuleEngine;
import com.funding.domain.RulesConfig;
import com.funding.persistence.TestDatabase;
import com.funding.seed.RuleFingerprints;
import com.funding.seed.SeedConfig;
import com.funding.seed.SeedGates;
import com.funding.seed.SeedPaths;
import com.funding.workflows.FundingInput;
import com.funding.workflows.FundingResult;
import com.funding.workflows.FundingState;
import com.funding.workflows.FundingWorkflow;
import com.funding.workflows.TaskQueues;
import com.funding.workflows.VersionView;
import com.funding.workflows.WorkflowIds;
import com.funding.workflows.activities.FundingActivitiesImpl;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import io.temporal.testing.TestWorkflowEnvironment;
import io.temporal.workflow.Workflow;
import java.time.Duration;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * T406: the funder API over the generated scenarios (seeded and settled with the real DB
 * activities and rules), with a stand-in workflow per version that records the signals it gets.
 */
class FunderApiTest {

    private static final String ENV = "apitest";
    private static final TaskQueues QUEUES = TaskQueues.forEnv(ENV);
    private static final String FUNDER = "funder-7";
    private static final Duration EXPIRE_AFTER = Duration.ofDays(30);
    private static final MediaType PROBLEM = MediaType.APPLICATION_PROBLEM_JSON;

    static final Queue<String> SIGNALS = new ConcurrentLinkedQueue<>();
    static volatile String step = "WAITING_FOR_FUNDER";
    static volatile boolean disbursementFailed = false;

    /** Stand-in parent: stays open, records every signal, reports the configured step. */
    public static class RecordingWorkflow implements FundingWorkflow {
        private boolean done;
        private long id;
        @Override public FundingResult run(FundingInput in) { id = in.applicationVersionId(); Workflow.await(() -> done); return new FundingResult("X"); }
        @Override public void stipResolved() { SIGNALS.add(id + ":stipResolved"); }
        @Override public void approve(String actor) { SIGNALS.add(id + ":approve:" + actor); }
        @Override public void reject(String actor, String reason) { SIGNALS.add(id + ":reject:" + actor + ":" + reason); }
        @Override public void rerunChecks(List<Check> checks) { SIGNALS.add(id + ":rerun:" + checks); }
        @Override public void retryDisbursement(String actor) { SIGNALS.add(id + ":retryDisbursement:" + actor); }
        @Override public void documentsUpdated(Long documentId) { SIGNALS.add(id + ":documentsUpdated:" + documentId); }
        @Override public void supersede() { SIGNALS.add(id + ":supersede"); }
        @Override public FundingState getState() { return new FundingState(step, "", Map.of(), Map.of(), 0L, disbursementFailed); }
    }

    private TestWorkflowEnvironment temporal;
    private JdbcClient jdbc;
    private TransactionTemplate tx;
    private FundingActivitiesImpl activities;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        SIGNALS.clear();
        step = "WAITING_FOR_FUNDER";
        disbursementFailed = false;
        temporal = TestWorkflowEnvironment.newInstance();
        temporal.newWorker(QUEUES.orchestration()).registerWorkflowImplementationTypes(RecordingWorkflow.class);
        temporal.start();

        TestDatabase db = TestDatabase.fresh(true);
        jdbc = db.jdbc();
        tx = new TransactionTemplate(new DataSourceTransactionManager(db.dataSource()));
        SeedConfig config = SeedConfig.fromDefaultsAndSystem(ENV);
        new SeedDataLoader(new SeedGates(SeedPaths.discover(), config, RuleFingerprints.fromClasspath()), config, jdbc, tx).load();
        activities = new FundingActivitiesImpl(ENV, jdbc, tx, RulesConfig::defaults, EXPIRE_AFTER);

        FunderService service = new FunderService(ENV, jdbc, tx, temporal.getWorkflowClient(), RulesConfig.defaults(), EXPIRE_AFTER);
        mvc = MockMvcBuilders.standaloneSetup(new FunderController(service)).setControllerAdvice(new ProblemHandler())
                .setCustomArgumentResolvers(new ActorArgumentResolver()).build();
    }

    @AfterEach
    void tearDown() {
        temporal.close();
    }

    // ------------------------------------------------------------------ helpers

    private long latest(String scenario) {
        return jdbc.sql("SELECT application_version_id FROM application_versions WHERE loan_application_id = ? AND is_latest_version")
                .param(scenario).query(Long.class).single();
    }

    /** Submits the scenario's latest version, runs the document checks through the real activities, starts the stand-in. */
    private long settled(String scenario) {
        long id = latest(scenario);
        jdbc.sql("UPDATE application_versions SET version_status = 'PENDING_REVIEW' WHERE application_version_id = ?").param(id).update();
        VersionView v = activities.loadVersionSnapshot(id);
        Map<Check, CheckOutcome> outcomes = new EnumMap<>(Check.class);
        RuleEngine engine = new RuleEngine();
        for (Check c : List.of(Check.TILA, Check.PRICE, Check.VIN, Check.GAP)) {
            CheckOutcome o = engine.evaluate(c, v.snapshot(), v.config());
            activities.recordOutcome(id, o, 1);
            outcomes.put(c, o);
        }
        activities.applyOutcome(id, outcomes);
        WorkflowClient.start(temporal.getWorkflowClient().newWorkflowStub(FundingWorkflow.class, WorkflowOptions.newBuilder()
                .setWorkflowId(WorkflowIds.parent(ENV, id)).setTaskQueue(QUEUES.orchestration()).build())::run,
                new FundingInput(id, FundingInput.VALIDATE, QUEUES));
        return id;
    }

    /** A DRAFT v2 of the scenario (resubmission arrives in S5); returns v1's id, now not latest. */
    private long superseded(String scenario) {
        long v1 = latest(scenario);
        jdbc.sql("UPDATE application_versions SET is_latest_version = FALSE WHERE application_version_id = ?").param(v1).update();
        jdbc.sql("""
                INSERT INTO application_versions (loan_application_id, version_number, is_latest_version, borrower_name, ssn_itin_encrypted,
                       stated_income_annual, vehicle_vin, dealership_name, dealership_id)
                SELECT loan_application_id, version_number + 1, TRUE, borrower_name, ssn_itin_encrypted, stated_income_annual,
                       vehicle_vin, dealership_name, dealership_id FROM application_versions WHERE application_version_id = ?""").param(v1).update();
        return v1;
    }

    private String versionStatus(long id) {
        return jdbc.sql("SELECT version_status FROM application_versions WHERE application_version_id = ?").param(id).query(String.class).single();
    }

    private long stip(long id, String code) {
        return jdbc.sql("SELECT stip_id FROM stipulations WHERE application_version_id = ? AND stip_code = ?")
                .params(id, code).query(Long.class).single();
    }

    /**
     * A write as {@link #FUNDER}. This MockMvc has no security filter (FunderAuthTest covers the
     * token path), so the signed-in user is put in the security context directly. SUPERVISOR,
     * because supervisors may do everything (spec 002 D-11), so spec 001's scenarios keep their meaning.
     */
    private ResultActions write(MockHttpServletRequestBuilder req, String body) throws Exception {
        req.contentType(MediaType.APPLICATION_JSON);
        SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                FUNDER, null, List.of(new SimpleGrantedAuthority("ROLE_" + Role.SUPERVISOR))));
        try {
            return mvc.perform(body == null ? req : req.content(body));
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    private ResultActions resolve(long id, long stipId, String status, String reason) throws Exception {
        return write(patch("/api/v1/versions/{v}/stipulations/{s}", id, stipId), """
                {"status":"%s","reason":"%s"}""".formatted(status, reason));
    }

    private static org.springframework.test.web.servlet.ResultMatcher problem(int http, String reason) {
        return result -> {
            status().is(http).match(result);
            content().contentTypeCompatibleWith(PROBLEM).match(result);
            jsonPath("$.reason").value(reason).match(result);
        };
    }

    private void awaitSignal(String s) {
        temporal.sleep(Duration.ofMillis(200));
        assertThat(SIGNALS).contains(s);
    }

    // ------------------------------------------------------------------ reads

    @Test
    void AS_10_1_overview() throws Exception {
        long id = latest("LA-1001");
        mvc.perform(get("/api/v1/applications/LA-1001")).andExpect(status().isOk())
                .andExpect(jsonPath("$.loanApplicationId").value("LA-1001"))
                .andExpect(jsonPath("$.applicationVersionId").value(id))
                .andExpect(jsonPath("$.latestVersionNumber").value(1))
                .andExpect(jsonPath("$.dealershipId").value("DLR-100"))
                .andExpect(jsonPath("$.borrowerName").isNotEmpty())
                .andExpect(jsonPath("$.statedIncomeAnnual").isNumber())
                .andExpect(jsonPath("$.vehicleVin").isNotEmpty())
                .andExpect(jsonPath("$.status").value("DRAFT"))
                .andExpect(jsonPath("$.ssnItinEncrypted").doesNotExist());
        mvc.perform(get("/api/v1/applications/LA-404")).andExpect(problem(404, "NOT_FOUND"));
    }

    /** Spec 002 T103: a DRAFT has no run, no deadline and no payment. */
    @Test
    void AS_2_1_overviewOfADraft() throws Exception {
        long id = latest("LA-1001");
        mvc.perform(get("/api/v1/applications/LA-1001")).andExpect(status().isOk())
                .andExpect(jsonPath("$.expiresAt").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.workflowId").value(WorkflowIds.parent(ENV, id)))
                .andExpect(jsonPath("$.workflowStep").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.inProgress").value(false))
                .andExpect(jsonPath("$.payment.state").value("NOT_STARTED"));
    }

    /** Spec 002 T103 (AS-2.1, AS-2.5): a deal waiting for a funder; expiry is the workflow's own deadline (AS-8.6). */
    @Test
    void AS_2_1_overviewShowsExpiryWorkflowAndPayment() throws Exception {
        long id = settled("LA-1006");
        java.time.OffsetDateTime submitted = jdbc.sql("SELECT submitted_at FROM application_versions WHERE application_version_id = ?")
                .param(id).query(java.time.OffsetDateTime.class).single();
        String body = mvc.perform(get("/api/v1/applications/LA-1006")).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("STIPULATED"))
                .andExpect(jsonPath("$.workflowId").value(WorkflowIds.parent(ENV, id)))
                .andExpect(jsonPath("$.workflowStep").value("WAITING_FOR_FUNDER"))
                .andExpect(jsonPath("$.inProgress").value(false))
                .andExpect(jsonPath("$.payment.state").value("NOT_STARTED"))
                .andReturn().getResponse().getContentAsString();
        // This standalone MockMvc writes dates as epoch seconds (the app writes ISO-8601); compare instants.
        com.fasterxml.jackson.databind.JsonNode expires = new com.fasterxml.jackson.databind.ObjectMapper().readTree(body).path("expiresAt");
        java.time.Instant expected = submitted.plus(EXPIRE_AFTER).toInstant();
        if (expires.isNumber()) {
            java.math.BigDecimal secs = expires.decimalValue();
            assertThat(java.time.Instant.ofEpochSecond(secs.longValue(), secs.remainder(java.math.BigDecimal.ONE).movePointRight(9).longValue()))
                    .isCloseTo(expected, org.assertj.core.api.Assertions.within(1, java.time.temporal.ChronoUnit.MILLIS));
        } else {
            assertThat(java.time.OffsetDateTime.parse(expires.asText()).toInstant()).isEqualTo(expected);
        }
    }

    /** Spec 002 T103 (AS-2.2): every step but WAITING_FOR_FUNDER and DONE means something is running. */
    @Test
    void AS_2_2_inProgressWhileTheWorkflowIsBusy() throws Exception {
        settled("LA-1006");
        for (String busy : List.of("VALIDATING", "APPLYING", "DISBURSING", "EXPIRING")) {
            step = busy;
            mvc.perform(get("/api/v1/applications/LA-1006")).andExpect(jsonPath("$.workflowStep").value(busy))
                    .andExpect(jsonPath("$.inProgress").value(true));
        }
        step = "DONE";
        mvc.perform(get("/api/v1/applications/LA-1006")).andExpect(jsonPath("$.inProgress").value(false));
    }

    /** Spec 002 T103 (AS-2.1): the payment states, from the disbursement row and the workflow step. */
    @Test
    void AS_2_1_paymentStates() throws Exception {
        long id = settled("LA-1006");
        jdbc.sql("INSERT INTO disbursements (application_version_id, amount, dealership_id, status) VALUES (?, 25000.00, 'DLR-100', 'REQUESTED')")
                .param(id).update();
        step = "DISBURSING";
        mvc.perform(get("/api/v1/applications/LA-1006")).andExpect(jsonPath("$.payment.state").value("IN_FLIGHT"))
                .andExpect(jsonPath("$.payment.amount").value(25000.00));
        step = "WAITING_FOR_FUNDER";   // retries exhausted, outcome unknown (C-8)
        mvc.perform(get("/api/v1/applications/LA-1006")).andExpect(jsonPath("$.payment.state").value("UNKNOWN"));
        jdbc.sql("UPDATE disbursements SET status = 'FAILED' WHERE application_version_id = ?").param(id).update();
        mvc.perform(get("/api/v1/applications/LA-1006")).andExpect(jsonPath("$.payment.state").value("FAILED"));
        jdbc.sql("UPDATE disbursements SET status = 'COMPLETED', external_reference = 'DSB-42' WHERE application_version_id = ?").param(id).update();
        jdbc.sql("UPDATE application_versions SET version_status = 'FUNDED' WHERE application_version_id = ?").param(id).update();
        step = "DONE";
        mvc.perform(get("/api/v1/applications/LA-1006")).andExpect(jsonPath("$.payment.state").value("PAID"))
                .andExpect(jsonPath("$.payment.reference").value("DSB-42"))
                .andExpect(jsonPath("$.payment.amount").value(25000.00))
                .andExpect(jsonPath("$.expiresAt").value(org.hamcrest.Matchers.nullValue()))   // paid: no deadline
                .andExpect(jsonPath("$.inProgress").value(false));
    }

    @Test
    void US_9_versionHistoryNewestFirst() throws Exception {
        superseded("LA-1001");
        mvc.perform(get("/api/v1/applications/LA-1001/versions")).andExpect(status().isOk())
                .andExpect(jsonPath("$[0].versionNumber").value(2))
                .andExpect(jsonPath("$[0].isLatestVersion").value(true))
                .andExpect(jsonPath("$[1].isLatestVersion").value(false));
    }

    @Test
    void AS_10_4_resultsWithExpectedVsActual() throws Exception {
        long id = settled("LA-1006");
        mvc.perform(get("/api/v1/versions/{v}/validation-results", id)).andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(4))
                .andExpect(jsonPath("$[?(@.validationCheck == 'PRICE')].outcome").value("FAIL"))
                .andExpect(jsonPath("$[?(@.validationCheck == 'PRICE')].details.comparisons[0].expected").isNotEmpty())
                .andExpect(jsonPath("$[?(@.validationCheck == 'PRICE')].details.comparisons[0].actual").isNotEmpty());
        activities.recordOutcome(id, new RuleEngine().evaluate(Check.PRICE, activities.loadVersionSnapshot(id).snapshot(), RulesConfig.defaults()), 2);
        mvc.perform(get("/api/v1/versions/{v}/validation-results", id)).andExpect(jsonPath("$.length()").value(4));
        mvc.perform(get("/api/v1/versions/{v}/validation-results?history=true", id)).andExpect(jsonPath("$.length()").value(5))
                .andExpect(jsonPath("$[?(@.validationCheck == 'PRICE' && @.attempt == 1)].isCurrent").value(false));
    }

    @Test
    void AS_10_5_documentsWithConfidenceAndBoxes() throws Exception {
        long id = latest("LA-1031");
        String docs = mvc.perform(get("/api/v1/versions/{v}/documents", id)).andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.documentType == 'RISC')].processedAt").isNotEmpty())
                .andReturn().getResponse().getContentAsString();
        List<Integer> risc = com.jayway.jsonpath.JsonPath.read(docs, "$[?(@.documentType == 'RISC')].documentId");
        mvc.perform(get("/api/v1/versions/{v}/documents/{d}/fields", id, risc.get(0))).andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.fieldKey == 'cash_price')].confidenceScore").isNotEmpty())
                .andExpect(jsonPath("$[?(@.fieldKey == 'cash_price')].pageNumber").value(3))
                .andExpect(jsonPath("$[?(@.fieldKey == 'cash_price')].boundingBox").isNotEmpty())
                .andExpect(jsonPath("$[?(@.fieldKey == 'amount_financed')].sectionName").isNotEmpty());
        mvc.perform(get("/api/v1/versions/{v}/documents/{d}/fields", latest("LA-1001"), risc.get(0))).andExpect(problem(404, "NOT_FOUND"));
    }

    // ------------------------------------------------------------------ stips

    @Test
    void AS_10_2_resolveStipSetsResolvedAt() throws Exception {
        long id = settled("LA-1006");
        long s = stip(id, "STIP_PRICE_MISMATCH");
        resolve(id, s, "WAIVED", "dealer confirmed the price").andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("WAIVED"));
        mvc.perform(get("/api/v1/versions/{v}/stipulations", id))
                .andExpect(jsonPath("$[0].status").value("WAIVED"))
                .andExpect(jsonPath("$[0].resolvedBy").value(FUNDER))
                .andExpect(jsonPath("$[0].resolutionNote").value("dealer confirmed the price"))
                .andExpect(jsonPath("$[0].resolvedAt").isNotEmpty());
        mvc.perform(get("/api/v1/versions/{v}/audit", id))
                .andExpect(jsonPath("$[?(@.entityType == 'STIPULATION' && @.action == 'WAIVED')].actor").value(FUNDER));
        awaitSignal(id + ":stipResolved");
        write(patch("/api/v1/versions/{v}/stipulations/{s}", id, s), """
                {"status":"WAIVED"}""").andExpect(problem(400, "MISSING_REASON"));
    }

    @Test
    void AS_10_2_secondConcurrentResolveIs409AlreadyResolved() throws Exception {
        long id = settled("LA-1006");
        long s = stip(id, "STIP_PRICE_MISMATCH");
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch go = new CountDownLatch(1);
        try {
            List<Future<Integer>> both = List.of(
                    pool.submit(() -> { go.await(); return resolve(id, s, "SATISFIED", "a").andReturn().getResponse().getStatus(); }),
                    pool.submit(() -> { go.await(); return resolve(id, s, "WAIVED", "b").andReturn().getResponse().getStatus(); }));
            go.countDown();
            assertThat(List.of(both.get(0).get(), both.get(1).get())).containsExactlyInAnyOrder(200, 409);
        } finally {
            pool.shutdownNow();
        }
        resolve(id, s, "SATISFIED", "again").andExpect(problem(409, "ALREADY_RESOLVED"));
    }

    @Test
    void AS_8_10_riscStipNotWaivable409() throws Exception {
        long tila = settled("LA-1002");
        resolve(tila, stip(tila, "STIP_TILA_MATH_DISCREPANCY"), "WAIVED", "x").andExpect(problem(409, "RISC_STIP_NOT_WAIVABLE"));
        resolve(tila, stip(tila, "STIP_TILA_MATH_DISCREPANCY"), "SATISFIED", "x").andExpect(problem(409, "RISC_STIP_NOT_WAIVABLE"));
        long missing = settled("LA-1026"); // RISC finance_charge missing
        long extraction = jdbc.sql("SELECT stip_id FROM stipulations WHERE application_version_id = ? AND stip_code = 'STIP_EXTRACTION_REVIEW_REQUIRED' AND description LIKE '%RISC.%' LIMIT 1")
                .param(missing).query(Long.class).single();
        resolve(missing, extraction, "WAIVED", "x").andExpect(problem(409, "RISC_STIP_NOT_WAIVABLE"));
    }

    @Test
    void AS_10_8_manualStipHasNullCheck() throws Exception {
        long id = settled("LA-1006"); // STIPULATED
        write(post("/api/v1/versions/{v}/stipulations", id), """
                {"code":"STIP_PROOF_OF_INCOME","description":"Upload two recent pay stubs"}""")
                .andExpect(status().isCreated()).andExpect(jsonPath("$.versionStatus").value("STIPULATED"));
        mvc.perform(get("/api/v1/versions/{v}/stipulations", id))
                .andExpect(jsonPath("$[?(@.stipCode == 'STIP_PROOF_OF_INCOME')].validationCheck").value(org.hamcrest.Matchers.contains((Object) null)));
        write(post("/api/v1/versions/{v}/stipulations", id), """
                {"code":"STIP_PROOF_OF_INCOME","description":"again"}""").andExpect(problem(409, "STIP_ALREADY_PENDING"));
    }

    @Test
    void AS_10_8_manualStipMovesApprovedToStipulatedUnlessDisbursing() throws Exception {
        long id = settled("LA-1001");
        assertThat(versionStatus(id)).isEqualTo("APPROVED");
        step = "DISBURSING";
        write(post("/api/v1/versions/{v}/stipulations", id), """
                {"code":"STIP_PROOF_OF_INCOME","description":"pay stubs"}""").andExpect(problem(409, "DISBURSEMENT_IN_PROGRESS"));
        write(post("/api/v1/versions/{v}/reject", id), """
                {"reason":"x"}""").andExpect(problem(409, "DISBURSEMENT_IN_PROGRESS"));
        assertThat(versionStatus(id)).isEqualTo("APPROVED");

        step = "WAITING_FOR_FUNDER"; // e.g. the disbursement failed (C-8)
        write(post("/api/v1/versions/{v}/stipulations", id), """
                {"code":"STIP_PROOF_OF_INCOME","description":"pay stubs"}""")
                .andExpect(status().isCreated()).andExpect(jsonPath("$.versionStatus").value("STIPULATED"));
        awaitSignal(id + ":stipResolved");
    }

    // ------------------------------------------------------------------ actions

    @Test
    void AS_10_6_actionsSignalWorkflow() throws Exception {
        long waived = settled("LA-1006");
        resolve(waived, stip(waived, "STIP_PRICE_MISMATCH"), "WAIVED", "ok").andExpect(status().isOk());
        write(post("/api/v1/versions/{v}/approve", waived), null).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("APPROVED"));
        assertThat(versionStatus(waived)).isEqualTo("APPROVED");
        awaitSignal(waived + ":approve:" + FUNDER);

        long rejected = settled("LA-1020");
        write(post("/api/v1/versions/{v}/reject", rejected), """
                {"reason":"dealer withdrew"}""").andExpect(status().isOk());
        assertThat(versionStatus(rejected)).isEqualTo("REJECTED");
        awaitSignal(rejected + ":reject:" + FUNDER + ":dealer withdrew");

        long rerun = settled("LA-1007");
        write(post("/api/v1/versions/{v}/rerun", rerun), """
                {"checks":["VIN"]}""").andExpect(status().isOk()).andExpect(jsonPath("$.checks[0]").value("VIN"));
        assertThat(versionStatus(rerun)).isEqualTo("PENDING_REVIEW"); // STIPULATED -> PENDING_REVIEW: re-run started
        awaitSignal(rerun + ":rerun:[VIN]");
        write(post("/api/v1/versions/{v}/rerun", latest("LA-1009")), null).andExpect(problem(409, "NOT_RERUNNABLE")); // DRAFT

        long approved = settled("LA-1001");
        write(post("/api/v1/versions/{v}/retry-disbursement", approved), null).andExpect(problem(409, "NOTHING_TO_RETRY"));
        disbursementFailed = true;
        write(post("/api/v1/versions/{v}/retry-disbursement", approved), null).andExpect(status().isOk());
        awaitSignal(approved + ":retryDisbursement:" + FUNDER);
    }

    @Test
    void AS_8_4_rerunDefaultsToErroredChecks() throws Exception {
        long id = settled("LA-1001");
        jdbc.sql("UPDATE application_versions SET version_status = 'PENDING_REVIEW' WHERE application_version_id = ?").param(id).update();
        write(post("/api/v1/versions/{v}/rerun", id), null).andExpect(problem(409, "NOTHING_TO_RERUN"));
        activities.recordOutcome(id, CheckOutcome.error(Check.GAP, "registry down"), 2);
        write(post("/api/v1/versions/{v}/rerun", id), null).andExpect(status().isOk()).andExpect(jsonPath("$.checks[0]").value("GAP"));
        awaitSignal(id + ":rerun:[GAP]");
    }

    @Test
    void AS_8_7_approveWithPendingStips409() throws Exception {
        long id = settled("LA-1006");
        write(post("/api/v1/versions/{v}/approve", id), null).andExpect(problem(409, "PENDING_STIPS"));
        assertThat(versionStatus(id)).isEqualTo("STIPULATED");
        assertThat(SIGNALS).isEmpty();
    }

    @Test
    void AS_8_10_approveRiscIncomplete409() throws Exception {
        long id = settled("LA-1006");
        resolve(id, stip(id, "STIP_PRICE_MISMATCH"), "WAIVED", "ok").andExpect(status().isOk());
        jdbc.sql("""
                DELETE FROM extracted_fields f USING document_sections s, documents d
                 WHERE f.section_id = s.section_id AND s.document_id = d.document_id
                   AND d.application_version_id = ? AND d.document_type = 'RISC' AND f.field_key = 'amount_financed'""").param(id).update();
        write(post("/api/v1/versions/{v}/approve", id), null).andExpect(problem(409, "RISC_INCOMPLETE"))
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("amount_financed")));
        assertThat(versionStatus(id)).isEqualTo("STIPULATED");
    }

    @Test
    void AS_10_3_reassignWritesAudit() throws Exception {
        long id = settled("LA-1006");
        write(put("/api/v1/versions/{v}/assignee", id), """
                {"funderId":"funder-9"}""").andExpect(status().isOk());
        mvc.perform(get("/api/v1/applications/LA-1006")).andExpect(jsonPath("$.assignedFunder").value("funder-9"));
        mvc.perform(get("/api/v1/versions/{v}/audit", id))
                .andExpect(jsonPath("$[?(@.action == 'ASSIGNEE_CHANGED')].afterState.assignedFunder").value("funder-9"))
                .andExpect(jsonPath("$[?(@.action == 'ASSIGNEE_CHANGED')].actor").value(FUNDER));
    }

    // ------------------------------------------------------------------ guards

    @Test
    void AS_10_7_terminalVersionWrites409() throws Exception {
        long id = settled("LA-1006");
        long s = stip(id, "STIP_PRICE_MISMATCH");
        for (String terminal : List.of("FUNDED", "REJECTED")) {
            jdbc.sql("UPDATE application_versions SET version_status = ? WHERE application_version_id = ?").params(terminal, id).update();
            resolve(id, s, "WAIVED", "x").andExpect(problem(409, "VERSION_TERMINAL"));
            write(post("/api/v1/versions/{v}/approve", id), null).andExpect(problem(409, "VERSION_TERMINAL"));
            write(put("/api/v1/versions/{v}/assignee", id), """
                    {"funderId":"f"}""").andExpect(problem(409, "VERSION_TERMINAL"));
        }
        long v1 = superseded("LA-1007");
        write(post("/api/v1/versions/{v}/approve", v1), null).andExpect(problem(409, "NOT_LATEST_VERSION"));
    }

    @Test
    void AS_8_6_expiredVersionWrites409VersionExpired() throws Exception {
        long id = settled("LA-1006");
        jdbc.sql("UPDATE application_versions SET version_status = 'EXPIRED' WHERE application_version_id = ?").param(id).update();
        resolve(id, stip(id, "STIP_PRICE_MISMATCH"), "SATISFIED", "x").andExpect(problem(409, "VERSION_EXPIRED"));
        write(post("/api/v1/versions/{v}/reject", id), """
                {"reason":"x"}""").andExpect(problem(409, "VERSION_EXPIRED"));
    }

    /** AS-10.9's token checks are in FunderAuthTest; here a write with no signed-in user changes nothing. */
    @Test
    void AS_10_9_writeWithoutUserIsRefused() throws Exception {
        long id = settled("LA-1006");
        mvc.perform(post("/api/v1/versions/{v}/approve", id)).andExpect(problem(401, "UNAUTHORIZED"));
        mvc.perform(patch("/api/v1/versions/{v}/stipulations/{s}", id, stip(id, "STIP_PRICE_MISMATCH"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"WAIVED\",\"reason\":\"x\"}"))
                .andExpect(problem(401, "UNAUTHORIZED"));
        assertThat(versionStatus(id)).isEqualTo("STIPULATED");
        mvc.perform(get("/api/v1/versions/{v}/stipulations", 999999)).andExpect(problem(404, "NOT_FOUND"));
    }
}
