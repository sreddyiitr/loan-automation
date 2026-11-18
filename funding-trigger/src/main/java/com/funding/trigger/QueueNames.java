package com.funding.trigger;

/** Per-environment queue names (NFR-08). */
public record QueueNames(String queue, String dlq) {
    public static QueueNames forEnv(String env) {
        return new QueueNames("funding-pending-review-" + env, "funding-pending-review-" + env + "-dlq");
    }
}
