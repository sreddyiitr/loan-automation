package com.funding.domain.income;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.funding.domain.money.Money;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.Optional;

/**
 * Classifies one Income Verification API response (US-5, C-12, C-13). The production activity and
 * the seed gates' client simulator use this same function, so the mocks are judged exactly as the
 * app would judge them.
 *
 * <pre>
 *   200 VERIFIED + totalVerifiedAnnualIncome   -> Ok(verified)
 *   200 NOT_FOUND                              -> Ok(notFound)              (C-12)
 *   200 invalid JSON / missing or bad total    -> Fatal  (not retried)
 *   429                                        -> Retry, honouring Retry-After (C-13)
 *   5xx                                        -> Retry
 *   other 4xx                                  -> Fatal  (not retried)
 * </pre>
 * Timeouts and connection failures never produce a response; callers treat them as Retry.
 */
public sealed interface IncomeApiResponse {

    record Ok(IncomeVerification verification) implements IncomeApiResponse {}

    record Retry(String reason, Duration retryAfter) implements IncomeApiResponse {}

    record Fatal(String reason) implements IncomeApiResponse {}

    ObjectMapper JSON = new ObjectMapper();

    static IncomeApiResponse classify(int status, String body, String retryAfterHeader) {
        if (status == 429) {
            return new Retry("rate limited (429)", parseRetryAfter(retryAfterHeader));
        }
        if (status >= 500) {
            return new Retry("server error (" + status + ")", null);
        }
        if (status != 200) {
            return new Fatal("client error (" + status + ")");
        }
        JsonNode n;
        try {
            n = JSON.readTree(body == null ? "" : body);
        } catch (IOException e) {
            return new Fatal("malformed response: not JSON");
        }
        if (n == null || !n.isObject()) {
            return new Fatal("malformed response: not a JSON object");
        }
        String s = n.path("status").asText("");
        if (IncomeVerification.NOT_FOUND.equals(s)) {
            return new Ok(IncomeVerification.notFound());
        }
        if (!IncomeVerification.VERIFIED.equals(s)) {
            return new Fatal("schema-invalid response: status '" + s + "'");
        }
        Optional<BigDecimal> total = n.hasNonNull("totalVerifiedAnnualIncome")
                ? Money.parse(n.get("totalVerifiedAnnualIncome").asText()) : Optional.empty();
        if (total.isEmpty() || total.get().signum() <= 0) {
            return new Fatal("schema-invalid response: totalVerifiedAnnualIncome missing or invalid");
        }
        return new Ok(IncomeVerification.verified(total.get()));
    }

    private static Duration parseRetryAfter(String h) {
        if (h == null || h.isBlank()) {
            return null;
        }
        try {
            long s = Long.parseLong(h.trim());
            return s < 0 ? null : Duration.ofSeconds(s);
        } catch (NumberFormatException e) {
            return null; // HTTP-date form: fall back to the retry policy's backoff
        }
    }
}
