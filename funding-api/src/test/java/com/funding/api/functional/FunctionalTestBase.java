package com.funding.api.functional;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.funding.api.FundingApplication;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.function.BooleanSupplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** The whole app under {@code local} against the shared emulator, Postgres, WireMock and Temporal. */
@SpringBootTest(classes = FundingApplication.class, webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT)
@ActiveProfiles("local")
abstract class FunctionalTestBase {

    protected static final ObjectMapper JSON = new ObjectMapper();
    protected static final HttpClient HTTP = HttpClient.newHttpClient();

    @Autowired
    protected JdbcClient jdbc;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry r) {
        FunctionalEnvironment.springProperties().forEach((k, v) -> r.add(k, () -> v));
    }

    protected static String base() {
        return "http://localhost:" + FunctionalEnvironment.PORT;
    }

    protected static HttpResponse<String> httpPost(String url, String body) throws Exception {
        return HTTP.send(HttpRequest.newBuilder(URI.create(url)).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body == null ? "" : body)).build(), HttpResponse.BodyHandlers.ofString());
    }

    protected static JsonNode getJson(String url, String apiKey) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url)).GET();
        if (apiKey != null) {
            b.header("X-Trigger-Api-Key", apiKey);
        }
        HttpResponse<String> r = HTTP.send(b.build(), HttpResponse.BodyHandlers.ofString());
        return r.statusCode() == 200 ? JSON.readTree(r.body()) : null;
    }

    protected String status(long versionId) {
        return jdbc.sql("SELECT version_status FROM application_versions WHERE application_version_id = ?")
                .param(versionId).query(String.class).single();
    }

    protected static void await(Duration timeout, BooleanSupplier condition, String what) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(200);
        }
        throw new AssertionError("timed out after " + timeout + " waiting for " + what);
    }

    /** P-5.7: no request from this env went unanswered by a generated stub (shared WireMock: filter by env). */
    protected static long unmatchedForThisEnv() throws Exception {
        HttpResponse<String> r = HTTP.send(HttpRequest.newBuilder(URI.create(FunctionalEnvironment.WIREMOCK_URL + "/__admin/requests/unmatched")).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        long n = 0;
        for (JsonNode req : JSON.readTree(r.body()).path("requests")) {
            if (req.toString().contains(FunctionalEnvironment.ENV)) {
                n++;
            }
        }
        return n;
    }
}
