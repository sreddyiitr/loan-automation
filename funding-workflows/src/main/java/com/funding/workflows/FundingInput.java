package com.funding.workflows;

/** {@code mode} is VALIDATE (from the trigger) or CONTINUE (only from continueAsNew, S4). */
public record FundingInput(long applicationVersionId, String mode, TaskQueues taskQueues) {
    public static final String VALIDATE = "VALIDATE";
}
