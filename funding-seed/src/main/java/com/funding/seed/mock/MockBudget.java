package com.funding.seed.mock;

import com.funding.seed.SeedConfig;
import java.time.Duration;

/** The activity budgets stubs are derived from (P-5.2): retry attempts and the external start-to-close timeout. */
public record MockBudget(int maxAttempts, Duration startToClose, int minPerBand) {

    public static MockBudget from(SeedConfig c) {
        return new MockBudget(
                Integer.parseInt(c.property("funding.activity.retry.max-attempts")),
                Duration.parse(c.property("funding.activity.start-to-close.external")),
                Integer.parseInt(c.property("funding.mocks.min-per-band")));
    }

    /** A delay guaranteed to exceed the activity's start-to-close timeout. */
    public long pastTimeoutMillis() {
        return startToClose.toMillis() + 1000;
    }
}
