package com.funding.api.functional;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.funding.api.seed.FixtureRows;
import com.funding.persistence.ApplicationWriter;
import com.funding.seed.Fixture;
import com.funding.seed.SeedGates;
import com.funding.trigger.InfraBootstrap;
import com.funding.trigger.LocalLambdaRunner;
import com.funding.trigger.QueueNames;
import com.funding.trigger.lambda.SqsTriggerHandler;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.MessageSystemAttributeName;
import software.amazon.awssdk.services.sqs.model.QueueAttributeName;

/** T125: the trigger path against the real emulator SQS (AS-1.1, AS-1.2, AS-1.5). */
class TriggerPathIT extends FunctionalTestBase {

    @RegisterExtension
    static WireMockExtension failingApi = WireMockExtension.newInstance().options(wireMockConfig().dynamicPort()).build();

    @Autowired
    private SeedGates gates;
    @Autowired
    private SqsClient sqs;
    @Autowired
    private InfraBootstrap.Queues queues;
    @Value("${funding.trigger.api-key}")
    private String apiKey;

    /** A copy of LA-1002 (TILA FAIL, so no disbursement call) under its own application id. */
    private long insertStipulatingApplication(String loanId) {
        Fixture f = gates.generate().fixtures().stream().filter(x -> x.scenarioId().equals("LA-1002")).findFirst().orElseThrow();
        return new ApplicationWriter(jdbc).insert(loanId, FixtureRows.versions(f)).get(0);
    }

    private JsonNode workflow(long versionId) {
        try {
            return getJson(base() + "/api/v1/funding-workflows/" + versionId, apiKey);
        } catch (Exception e) {
            return null;
        }
    }

    @Test
    void AS_1_1_submittedVersionStartsWorkflowWithin10s() throws Exception {
        long v = insertStipulatingApplication("TP-1");
        jdbc.sql("UPDATE application_versions SET version_status = 'PENDING_REVIEW' WHERE application_version_id = ?").param(v).update();
        await(Duration.ofSeconds(10), () -> workflow(v) != null, "workflow for version " + v);
        assertThat(workflow(v).path("workflowId").asText()).isEqualTo("funding-" + FunctionalEnvironment.ENV + "-" + v);
        await(Duration.ofSeconds(20), () -> "STIPULATED".equals(status(v)), "STIPULATED");
    }

    @Test
    void AS_1_2_duplicateDeliveryStartsOneRun() throws Exception {
        long v = insertStipulatingApplication("TP-2");
        jdbc.sql("UPDATE application_versions SET version_status = 'PENDING_REVIEW' WHERE application_version_id = ?").param(v).update();
        String payload = "{\"event_type\":\"APPLICATION_VERSION_PENDING_REVIEW\",\"loan_application_id\":\"TP-2\",\"application_version_id\":" + v + "}";
        // Two extra copies on top of the outbox's own message.
        sqs.sendMessage(b -> b.queueUrl(queues.queueUrl()).messageBody(payload));
        sqs.sendMessage(b -> b.queueUrl(queues.queueUrl()).messageBody(payload));
        await(Duration.ofSeconds(20), () -> "STIPULATED".equals(status(v)), "STIPULATED");
        String runId = workflow(v).path("runId").asText();
        Thread.sleep(3000); // let any late duplicate arrive
        assertThat(workflow(v).path("runId").asText()).isEqualTo(runId);
        // One run: exactly one result row per check, none of them a second attempt.
        assertThat(jdbc.sql("SELECT count(*) - count(DISTINCT validation_check) FROM validation_results WHERE application_version_id = ?")
                .param(v).query(Integer.class).single()).isZero();
        assertThat(jdbc.sql("SELECT max(attempt) FROM validation_results WHERE application_version_id = ?").param(v).query(Integer.class).single()).isEqualTo(1);
        assertThat(jdbc.sql("SELECT count(*) FROM stipulations WHERE application_version_id = ?").param(v).query(Integer.class).single()).isEqualTo(1);
    }

    @Test
    void AS_1_5_5xxIsRetriedThenMovesToDlqAfter5ReceivesWhileOthersAreAcked() throws Exception {
        // A separate queue (2 s visibility so the test is quick) and a start API that fails for version 2.
        QueueNames names = new QueueNames("funding-pending-review-" + FunctionalEnvironment.ENV + "-retry",
                "funding-pending-review-" + FunctionalEnvironment.ENV + "-retry-dlq");
        FunctionalEnvironment.EXTRA_QUEUES.addAll(List.of(names.queue(), names.dlq()));
        InfraBootstrap.Queues q = InfraBootstrap.ensureQueues(sqs, names, 2, 5);
        failingApi.stubFor(post(urlEqualTo("/api/v1/funding-workflows"))
                .withRequestBody(matchingJsonPath("$.applicationVersionId", equalTo("2"))).willReturn(aResponse().withStatus(503)));
        failingApi.stubFor(post(urlEqualTo("/api/v1/funding-workflows"))
                .withRequestBody(matchingJsonPath("$.applicationVersionId", equalTo("1"))).willReturn(aResponse().withStatus(202)));
        for (int id = 1; id <= 2; id++) {
            String body = "{\"event_type\":\"APPLICATION_VERSION_PENDING_REVIEW\",\"loan_application_id\":\"X\",\"application_version_id\":" + id + "}";
            sqs.sendMessage(b -> b.queueUrl(q.queueUrl()).messageBody(body));
        }
        LocalLambdaRunner runner = new LocalLambdaRunner(sqs, q.queueUrl(),
                new SqsTriggerHandler(failingApi.baseUrl(), "k", Duration.ofSeconds(2)), 1);
        AtomicReference<String> dlqBody = new AtomicReference<>();
        await(Duration.ofSeconds(60), () -> {
            runner.pollOnce();
            var m = sqs.receiveMessage(b -> b.queueUrl(q.dlqUrl()).maxNumberOfMessages(10).waitTimeSeconds(0)
                    .messageSystemAttributeNames(MessageSystemAttributeName.APPROXIMATE_RECEIVE_COUNT)).messages();
            if (!m.isEmpty()) {
                dlqBody.set(m.get(0).body());
                return true;
            }
            return false;
        }, "the failing message in the DLQ");
        assertThat(dlqBody.get()).contains("\"application_version_id\":2");
        // version 1 was acked (deleted) on the first pass; version 2 was received 5 times before redrive.
        failingApi.verify(1, com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor(urlEqualTo("/api/v1/funding-workflows"))
                .withRequestBody(matchingJsonPath("$.applicationVersionId", equalTo("1"))));
        failingApi.verify(5, com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor(urlEqualTo("/api/v1/funding-workflows"))
                .withRequestBody(matchingJsonPath("$.applicationVersionId", equalTo("2"))));
        String left = sqs.getQueueAttributes(b -> b.queueUrl(q.queueUrl()).attributeNames(QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES))
                .attributes().get(QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES);
        assertThat(left).isEqualTo("0");
    }
}
