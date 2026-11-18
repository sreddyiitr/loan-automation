package com.funding.api.functional;

import static org.assertj.core.api.Assertions.assertThat;

import com.funding.domain.Check;
import com.funding.domain.CheckOutcome;
import com.funding.domain.ExternalApi;
import com.funding.seed.SeedConfig;
import com.funding.seed.SeedGates;
import com.funding.seed.SeedPaths;
import com.funding.seed.mock.MockBudget;
import com.funding.seed.mock.MockSet;
import com.funding.seed.mock.MockStub;
import com.funding.workflows.CheckInput;
import com.funding.workflows.IncomeVerificationWorkflow;
import com.funding.workflows.TaskQueues;
import com.funding.workflows.VersionView;
import com.funding.workflows.WorkflowIds;
import com.funding.workflows.activities.FundingActivitiesImpl;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowFailedException;
import io.temporal.client.WorkflowOptions;
import io.temporal.client.WorkflowStub;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;

/**
 * T312: every Income Verification catalog stub (bands, NOT_FOUND, shapes, fault classes) through
 * the real HTTP client, the app's Temporal retry policy and the INCOME rule, asserted against the
 * band's independent expected outcome. Each case gets its own DRAFT version (no outbox trigger)
 * whose SSN token and stated income are the stub's; the cases run in parallel.
 */
class IncomeVerificationMatrixIT extends FunctionalTestBase {

    @Autowired private SeedGates gates;
    @Autowired private SeedConfig config;
    @Autowired private FundingActivitiesImpl activities;
    @Autowired private WorkflowClient client;
    @Autowired private TaskQueues queues;
    @Value("${funding.env}") private String env;

    @Test
    void T312_everyCatalogEntryMatchesItsBand() throws Exception {
        List<MockStub> cases = MockSet.generate(env, MockBudget.from(config), config.rules().incomeVarianceThreshold(),
                        gates.generate().fixtures(), MockSet.catalogs(SeedPaths.discover())).stream()
                .filter(s -> s.api() == ExternalApi.INCOME_VERIFICATION && "catalog".equals(s.source())).toList();
        assertThat(cases).hasSizeGreaterThanOrEqualTo(150);

        Map<MockStub, WorkflowStub> running = new LinkedHashMap<>();
        Map<MockStub, Long> versions = new LinkedHashMap<>();
        int n = 0;
        for (MockStub s : cases) {
            long versionId = insertCase("MX-" + (++n), s.attributes().get("token"), new BigDecimal(s.attributes().get("statedIncome")));
            versions.put(s, versionId);
            VersionView view = activities.loadVersionSnapshot(versionId);
            IncomeVerificationWorkflow wf = client.newWorkflowStub(IncomeVerificationWorkflow.class, WorkflowOptions.newBuilder()
                    .setWorkflowId(WorkflowIds.child(WorkflowIds.parent(env, versionId), Check.INCOME, 1))
                    .setTaskQueue(queues.validation()).build());
            WorkflowClient.start(wf::run, new CheckInput(Check.INCOME, 1, view.snapshot(), view.config(), queues.external()));
            running.put(s, WorkflowStub.fromTyped(wf));
        }

        List<String> mismatches = new ArrayList<>();
        for (Map.Entry<MockStub, WorkflowStub> e : running.entrySet()) {
            MockStub s = e.getKey();
            String actual;
            try {
                actual = e.getValue().getResult(5, TimeUnit.MINUTES, CheckOutcome.class).outcome().name();
            } catch (WorkflowFailedException failed) {
                actual = "ERROR"; // retries exhausted or a non-retryable response: the parent records ERROR (AS-5.3)
            }
            if (!s.expected().equals(actual)) {
                mismatches.add(s.id() + ": band " + s.band() + " expects " + s.expected() + ", got " + actual + " " + s.attributes());
            } else if (!"ERROR".equals(actual)) {
                String recorded = jdbc.sql("SELECT outcome FROM validation_results WHERE application_version_id = ? AND validation_check = 'INCOME' AND is_current")
                        .param(versions.get(s)).query(String.class).single();
                if (!actual.equals(recorded)) {
                    mismatches.add(s.id() + ": returned " + actual + " but recorded " + recorded);
                }
            }
        }
        assertThat(mismatches).as("catalog entries whose engine outcome differs from the band oracle").isEmpty();
        assertThat(unmatchedForThisEnv()).isZero(); // P-5.7
    }

    /** A DRAFT version (so the outbox trigger never starts a parent) carrying only what INCOME reads. */
    private long insertCase(String loanApplicationId, String token, BigDecimal statedIncome) {
        jdbc.sql("INSERT INTO loan_applications (loan_application_id) VALUES (?)").param(loanApplicationId).update();
        return jdbc.sql("""
                INSERT INTO application_versions (loan_application_id, borrower_name, ssn_itin_encrypted, stated_income_annual,
                                                  vehicle_vin, dealership_name, dealership_id)
                VALUES (?, 'Matrix Case', ?, ?, '1HGCM82633A004352', 'Matrix Motors', 'DLR-MX')
                RETURNING application_version_id""")
                .param(loanApplicationId).param(token).param(statedIncome).query(Long.class).single();
    }
}
