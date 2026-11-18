package com.funding.workflows;

/**
 * {@code mode} is VALIDATE (from the trigger) or CONTINUE (only from continueAsNew, plan step 7),
 * which carries the run's state in {@code carried} and skips straight to the signal loop.
 */
public record FundingInput(long applicationVersionId, String mode, TaskQueues taskQueues, FundingCarry carried) {
    public static final String VALIDATE = "VALIDATE";
    public static final String CONTINUE = "CONTINUE";

    public FundingInput(long applicationVersionId, String mode, TaskQueues taskQueues) {
        this(applicationVersionId, mode, taskQueues, null);
    }
}
