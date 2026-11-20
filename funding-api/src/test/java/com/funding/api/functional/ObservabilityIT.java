package com.funding.api.functional;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.JsonNode;
import com.funding.api.seed.FixtureRows;
import com.funding.seed.Fixture;
import com.funding.seed.SeedConfig;
import com.funding.seed.SeedGates;
import com.funding.workflows.WorkflowIds;
import com.funding.workflows.activities.FundingActivitiesImpl;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.transaction.support.TransactionTemplate;

/** T607: NFR-05 log correlation on activity log lines, and the Temporal SDK's metrics in the app's registry. */
class ObservabilityIT extends FunctionalTestBase {

    @Autowired private SeedGates gates;
    @Autowired private SeedConfig config;
    @Autowired private TransactionTemplate tx;
    @Value("${funding.env}") private String env;

    @Test
    void NFR_05_activityLogLinesCarryWorkflowVersionAndLoanIds() throws Exception {
        Logger activities = (Logger) LoggerFactory.getLogger(FundingActivitiesImpl.class);
        ListAppender<ILoggingEvent> captured = new ListAppender<>();
        captured.start();
        activities.addAppender(captured);
        try {
            MockControl mocks = new MockControl(env, FunctionalEnvironment.WIREMOCK_URL, config, gates.generate().fixtures());
            Fixture clone = mocks.cloneScenario("LA-1001", "OBS");
            List<Long> ids = tx.execute(s -> FixtureRows.insert(jdbc, gates, clone));
            long id = ids.get(clone.versions().indexOf(clone.latest()));
            HttpResponse<String> r = httpPost(base() + "/api/v1/dev/applications/" + clone.loanApplicationId() + "/submit", null);
            assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
            await(Duration.ofSeconds(60), () -> "FUNDED".equals(status(id)), "LA-1001-OBS funded");

            // "version <id>: PENDING_REVIEW -> APPROVED (VALIDATION_OUTCOME)" and "-> FUNDED" are written inside activities.
            List<Map<String, String>> transitions = captured.list.stream()
                    .filter(e -> e.getFormattedMessage().startsWith("version " + id + ": "))
                    .map(ILoggingEvent::getMDCPropertyMap).toList();
            assertThat(transitions).isNotEmpty().allSatisfy(mdc -> assertThat(mdc)
                    .containsEntry("workflowId", WorkflowIds.parent(env, id))
                    .containsEntry("applicationVersionId", Long.toString(id)));
            assertThat(transitions).anySatisfy(mdc -> assertThat(mdc).containsEntry("loanApplicationId", clone.loanApplicationId()));
        } finally {
            activities.detachAppender(captured);
        }
    }

    @Test
    void temporalSdkMetricsAreInTheAppsRegistry() throws Exception {
        await(Duration.ofSeconds(45), () -> {
            try {
                return meterNames().stream().anyMatch(n -> n.startsWith("temporal"));
            } catch (Exception e) {
                return false;
            }
        }, "temporal_* meters at /actuator/metrics (reported every 10 s)");
        assertThat(meterNames()).anyMatch(n -> n.startsWith("temporal_request") || n.startsWith("temporal_long_request"));
    }

    private List<String> meterNames() throws Exception {
        HttpResponse<String> r = HTTP.send(HttpRequest.newBuilder(URI.create(base() + "/actuator/metrics")).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
        JsonNode names = JSON.readTree(r.body()).path("names");
        return StreamSupport.stream(names.spliterator(), false).map(JsonNode::asText).toList();
    }
}
