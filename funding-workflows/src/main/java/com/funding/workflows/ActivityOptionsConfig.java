package com.funding.workflows;

import io.temporal.activity.ActivityOptions;
import io.temporal.common.RetryOptions;
import java.time.Duration;
import java.util.function.Function;

/**
 * Activity budgets (NFR-03, NFR-01): from {@code funding.activity.*}. Passed to the workflow
 * implementations through their factory, so the same values apply on replay.
 */
public record ActivityOptionsConfig(Duration dbStartToClose, Duration externalStartToClose,
                                    Duration initialInterval, double backoff, Duration maximumInterval, int maxAttempts) {

    public static ActivityOptionsConfig from(Function<String, String> props) {
        return new ActivityOptionsConfig(
                Duration.parse(props.apply("funding.activity.start-to-close.db")),
                Duration.parse(props.apply("funding.activity.start-to-close.external")),
                Duration.parse(props.apply("funding.activity.retry.initial-interval")),
                Double.parseDouble(props.apply("funding.activity.retry.backoff-coefficient")),
                Duration.parse(props.apply("funding.activity.retry.maximum-interval")),
                Integer.parseInt(props.apply("funding.activity.retry.max-attempts")));
    }

    /** Fast budgets for TestWorkflowEnvironment tests. */
    public static ActivityOptionsConfig forTests() {
        return new ActivityOptionsConfig(Duration.ofSeconds(5), Duration.ofSeconds(5), Duration.ofSeconds(1), 2.0,
                Duration.ofSeconds(10), 3);
    }

    public RetryOptions retry() {
        return RetryOptions.newBuilder()
                .setInitialInterval(initialInterval)
                .setBackoffCoefficient(backoff)
                .setMaximumInterval(maximumInterval)
                .setMaximumAttempts(maxAttempts)
                .build();
    }

    public ActivityOptions db(String taskQueue) {
        return ActivityOptions.newBuilder().setTaskQueue(taskQueue).setStartToCloseTimeout(dbStartToClose).setRetryOptions(retry()).build();
    }

    public ActivityOptions external(String taskQueue) {
        return ActivityOptions.newBuilder().setTaskQueue(taskQueue).setStartToCloseTimeout(externalStartToClose).setRetryOptions(retry()).build();
    }
}
