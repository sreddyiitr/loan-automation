package com.funding.trigger.lambda;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;

import com.amazonaws.services.lambda.runtime.events.SQSBatchResponse;
import com.amazonaws.services.lambda.runtime.events.SQSEvent;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/** AS-1.3, AS-1.5, AS-1.6, AS-1.7: response code -> ack / retry, per item (ReportBatchItemFailures). */
class TriggerProcessorTest {

    @RegisterExtension
    static WireMockExtension api = WireMockExtension.newInstance().options(wireMockConfig().dynamicPort()).build();

    private static final String PATH = "/api/v1/funding-workflows";

    private TriggerProcessor processor(Duration timeout) {
        return new TriggerProcessor(new HttpStartApi(api.baseUrl(), "secret", timeout), timeout);
    }

    private static SQSEvent.SQSMessage msg(String id, long versionId) {
        return raw(id, "{\"event_type\":\"APPLICATION_VERSION_PENDING_REVIEW\",\"loan_application_id\":\"LA-" + versionId
                + "\",\"application_version_id\":" + versionId + "}");
    }

    private static SQSEvent.SQSMessage raw(String id, String body) {
        SQSEvent.SQSMessage m = new SQSEvent.SQSMessage();
        m.setMessageId(id);
        m.setBody(body);
        return m;
    }

    private void respond(long versionId, int status) {
        api.stubFor(post(urlEqualTo(PATH)).withRequestBody(matchingJsonPath("$.applicationVersionId", equalTo(Long.toString(versionId))))
                .willReturn(aResponse().withStatus(status).withHeader("Content-Type", "application/json").withBody("{}")));
    }

    private static List<String> failed(SQSBatchResponse r) {
        return r.getBatchItemFailures().stream().map(SQSBatchResponse.BatchItemFailure::getItemIdentifier).toList();
    }

    @Test
    void AS_1_1_forwardsEventWithApiKeyAndSourceMessageId() {
        respond(42, 202);
        SQSBatchResponse r = processor(Duration.ofSeconds(5)).process(List.of(msg("m-1", 42)), () -> 30_000);
        assertThat(failed(r)).isEmpty();
        api.verify(postRequestedFor(urlEqualTo(PATH))
                .withHeader("X-Trigger-Api-Key", equalTo("secret"))
                .withRequestBody(equalToJson("""
                        {"eventType":"APPLICATION_VERSION_PENDING_REVIEW","loanApplicationId":"LA-42",
                         "applicationVersionId":42,"documentId":null,"sourceMessageId":"m-1"}""")));
    }

    @Test
    void AS_1_3_200AlreadyStartedIsAcked() {
        respond(1, 200);
        assertThat(failed(processor(Duration.ofSeconds(5)).process(List.of(msg("m-1", 1)), () -> 30_000))).isEmpty();
    }

    @Test
    void AS_1_5_5xxFailsOnlyThatItem() {
        respond(1, 202);
        respond(2, 503);
        respond(3, 202);
        SQSBatchResponse r = processor(Duration.ofSeconds(5)).process(List.of(msg("a", 1), msg("b", 2), msg("c", 3)), () -> 30_000);
        assertThat(failed(r)).containsExactly("b");
    }

    @Test
    void AS_1_5_timeoutFailsOnlyThatItem() {
        respond(1, 202);
        api.stubFor(post(urlEqualTo(PATH)).withRequestBody(matchingJsonPath("$.applicationVersionId", equalTo("2")))
                .willReturn(aResponse().withStatus(202).withFixedDelay(1500)));
        SQSBatchResponse r = processor(Duration.ofMillis(300)).process(List.of(msg("a", 1), msg("b", 2)), () -> 30_000);
        assertThat(failed(r)).containsExactly("b");
    }

    @Test
    void AS_1_6_409IsAcked() {
        respond(1, 409);
        assertThat(failed(processor(Duration.ofSeconds(5)).process(List.of(msg("m", 1)), () -> 30_000))).isEmpty();
    }

    @Test
    void AS_1_7_401FailsItem() {
        respond(1, 401);
        assertThat(failed(processor(Duration.ofSeconds(5)).process(List.of(msg("m", 1)), () -> 30_000))).containsExactly("m");
    }

    @Test
    void malformedBodyIsAckedNotRetried() {
        respond(1, 400);
        SQSBatchResponse r = processor(Duration.ofSeconds(5)).process(
                List.of(raw("bad-json", "{not json"), raw("no-version", "{\"event_type\":\"X\"}"), msg("api-400", 1)), () -> 30_000);
        assertThat(failed(r)).isEmpty();
    }

    @Test
    void recordsAreSentConcurrently() {
        List<SQSEvent.SQSMessage> batch = new ArrayList<>();
        for (int i = 1; i <= 10; i++) {
            api.stubFor(post(urlEqualTo(PATH)).withRequestBody(matchingJsonPath("$.applicationVersionId", equalTo(Integer.toString(i))))
                    .willReturn(aResponse().withStatus(202).withFixedDelay(400)));
            batch.add(msg("m-" + i, i));
        }
        long start = System.nanoTime();
        SQSBatchResponse r = processor(Duration.ofSeconds(5)).process(batch, () -> 30_000);
        long millis = (System.nanoTime() - start) / 1_000_000;
        assertThat(failed(r)).isEmpty();
        assertThat(millis).as("10 x 400 ms sent one after another would take 4 s").isLessThan(2500);
    }

    @Test
    void remainingTimeCutoffReportsUnsentRecordAsFailure() {
        respond(1, 202);
        // Less time left than one HTTP timeout: don't send, let SQS redeliver.
        SQSBatchResponse r = processor(Duration.ofSeconds(5)).process(List.of(msg("m", 1)), () -> 2_000);
        assertThat(failed(r)).containsExactly("m");
        api.verify(0, postRequestedFor(urlEqualTo(PATH)));
    }
}
