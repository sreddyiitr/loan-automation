package com.funding.workflows;

/**
 * Task queues per environment (plan.md "Temporal Design", NFR-08): the parent, the children + DB
 * activities, and external API activities are separate pools so each can be rate-limited.
 */
public record TaskQueues(String orchestration, String validation, String external) {
    public static TaskQueues forEnv(String env) {
        return new TaskQueues("funding-orchestration-" + env, "funding-validation-" + env, "funding-external-" + env);
    }
}
