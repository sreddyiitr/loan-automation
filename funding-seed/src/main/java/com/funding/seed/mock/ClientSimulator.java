package com.funding.seed.mock;

import com.funding.domain.income.IncomeApiResponse;
import com.funding.domain.income.IncomeVerification;
import java.util.List;

/**
 * Replays a stub's call sequence the way the app's activity sees it under the retry budget: each
 * attempt consumes the next interaction (WireMock scenario state advances even when the client
 * times out), and the last interaction repeats. Uses the app's own response classifier.
 */
public final class ClientSimulator {
    private ClientSimulator() {}

    /** The income API result after retries, or {@code null} when the activity ends in ERROR. */
    public static IncomeVerification income(List<Interaction> calls, MockBudget budget) {
        for (int attempt = 0; attempt < budget.maxAttempts(); attempt++) {
            Interaction it = calls.get(Math.min(attempt, calls.size() - 1));
            if (it.fault() != null || it.delayMillis() > budget.startToClose().toMillis()) {
                continue; // connection failure or timeout: retryable
            }
            IncomeApiResponse r = IncomeApiResponse.classify(it.status(), it.body(), it.headers().get("Retry-After"));
            if (r instanceof IncomeApiResponse.Ok ok) {
                return ok.verification();
            }
            if (r instanceof IncomeApiResponse.Fatal) {
                return null;
            }
        }
        return null;
    }

    /** Disbursement: FUNDED once a call returns 200/201 with a reference, APPROVED (held) otherwise. */
    public static String disbursement(List<Interaction> calls, MockBudget budget) {
        for (int attempt = 0; attempt < budget.maxAttempts(); attempt++) {
            Interaction it = calls.get(Math.min(attempt, calls.size() - 1));
            if (it.fault() != null || it.delayMillis() > budget.startToClose().toMillis() || it.status() >= 500 || it.status() == 429) {
                continue;
            }
            boolean ok = (it.status() == 200 || it.status() == 201) && it.body() != null && it.body().matches("(?s)\\{.*\"reference\"\\s*:\\s*\"[^\"]+\".*}");
            return ok ? "FUNDED" : "APPROVED";
        }
        return "APPROVED";
    }
}
