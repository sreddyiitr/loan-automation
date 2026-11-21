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

    private static final java.util.Map<String, String> TOKENS = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * A real access token for {@code userId} from the shared mock OIDC provider (spec 002 plan §4):
     * {@code client_credentials} with {@code client_id=<user id>} gives {@code sub=<user id>} and
     * {@code aud=funding-api}. Cached per user; tokens last an hour, longer than any run.
     */
    protected static String token(String userId) {
        return TOKENS.computeIfAbsent(userId, u -> tokenFrom(FunctionalEnvironment.OIDC_ISSUER_URI, u, "funding-api"));
    }

    /** A token from any issuer path of the mock provider, for any audience ({@code scope}); uncached. */
    protected static String tokenFrom(String issuerUri, String userId, String audience) {
        try {
            HttpResponse<String> r = HTTP.send(HttpRequest.newBuilder(URI.create(issuerUri + "/token"))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString("grant_type=client_credentials&client_id=" + userId
                            + "&client_secret=unused&scope=" + audience)).build(), HttpResponse.BodyHandlers.ofString());
            if (r.statusCode() != 200) {
                throw new IllegalStateException("token for " + userId + ": HTTP " + r.statusCode() + " " + r.body());
            }
            return JSON.readTree(r.body()).path("access_token").asText();
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    /** The {@code Authorization} header value for the functional tests' user. */
    protected static String bearer() {
        return "Bearer " + token(FunctionalEnvironment.TEST_USER);
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
