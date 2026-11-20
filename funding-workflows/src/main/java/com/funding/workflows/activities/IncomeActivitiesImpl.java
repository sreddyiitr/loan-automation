package com.funding.workflows.activities;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.funding.domain.income.IncomeApiResponse;
import com.funding.domain.income.IncomeVerification;
import com.funding.workflows.IncomeActivities;
import io.temporal.failure.ApplicationFailure;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * US-5 activity. Loads the encrypted SSN/ITIN from the DB by version id (FR-13) and sends it as-is
 * (AS-5.4); the token never appears in a log line, an exception message or the result. Every
 * response goes through {@link IncomeApiResponse#classify}, the same function the seed gates use.
 */
public class IncomeActivitiesImpl implements IncomeActivities {

    public static final String PATH = "/api/v1/external/verify-income";
    private static final Logger LOG = LoggerFactory.getLogger(IncomeActivitiesImpl.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    private final String env;
    private final JdbcClient jdbc;
    private final HttpClient http;
    private final URI uri;
    private final Duration timeout;
    private final Duration maxRetryDelay;

    /**
     * @param timeout       per-call HTTP timeout (the activity's start-to-close)
     * @param maxRetryDelay cap for a server's {@code Retry-After} (the retry policy's maximum interval, C-13)
     */
    public IncomeActivitiesImpl(String env, JdbcClient jdbc, String baseUrl, Duration timeout, Duration maxRetryDelay) {
        this.env = env;
        this.jdbc = jdbc;
        this.uri = URI.create(baseUrl.replaceAll("/+$", "") + PATH);
        this.timeout = timeout;
        this.maxRetryDelay = maxRetryDelay;
        this.http = HttpClient.newBuilder().connectTimeout(timeout).build();
    }

    @Override
    public IncomeVerification verifyIncome(long versionId) {
        String token = jdbc.sql("SELECT ssn_itin_encrypted FROM application_versions WHERE application_version_id = ?")
                .param(versionId).query(String.class).optional().orElse(null);
        if (token == null || token.isBlank()) {
            throw ApplicationFailure.newNonRetryableFailure("version " + versionId + " has no SSN/ITIN token", "IncomeNoToken");
        }
        ObjectNode body = JSON.createObjectNode();
        body.put("ssnItinEncrypted", token);
        body.put("applicationVersionId", versionId);

        HttpResponse<String> resp;
        try {
            resp = http.send(HttpRequest.newBuilder(uri).timeout(timeout).header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body.toString())).build(), HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            // e.toString() names the failure type (timeout, reset); it never contains the request body.
            LOG.warn("income API call for version {} failed: {}", versionId, e.getClass().getSimpleName());
            throw ApplicationFailure.newFailure("income API unreachable or timed out: " + e.getClass().getSimpleName(), "IncomeUnavailable");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw ApplicationFailure.newFailure("interrupted", "Interrupted");
        }

        IncomeApiResponse r = IncomeApiResponse.classify(resp.statusCode(), resp.body(), resp.headers().firstValue("Retry-After").orElse(null));
        if (r instanceof IncomeApiResponse.Ok ok) {
            LOG.info("income verified for version {}: {}", versionId, ok.verification().status());
            return ok.verification();
        }
        if (r instanceof IncomeApiResponse.Retry retry) {
            LOG.warn("income API for version {}: {}; will retry", versionId, retry.reason());
            ApplicationFailure.Builder b = ApplicationFailure.newBuilder().setMessage("income API: " + retry.reason()).setType("IncomeUnavailable");
            if (retry.retryAfter() != null) {
                b.setNextRetryDelay(retry.retryAfter().compareTo(maxRetryDelay) > 0 ? maxRetryDelay : retry.retryAfter());
            }
            throw b.build();
        }
        IncomeApiResponse.Fatal fatal = (IncomeApiResponse.Fatal) r;
        LOG.warn("income API for version {}: {}; not retrying", versionId, fatal.reason());
        throw ApplicationFailure.newNonRetryableFailure("income API: " + fatal.reason(), "IncomeRejected");
    }
}
