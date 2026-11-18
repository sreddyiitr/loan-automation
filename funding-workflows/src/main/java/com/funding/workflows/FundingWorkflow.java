package com.funding.workflows;

import io.temporal.workflow.QueryMethod;
import io.temporal.workflow.SignalMethod;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;

/** Parent workflow, one per application version (FR-03). Signals beyond these arrive in slice S4/S5. */
@WorkflowInterface
public interface FundingWorkflow {

    @WorkflowMethod
    FundingResult run(FundingInput input);

    /** Wake-up after a funder resolved a stip; the workflow re-reads state from the DB. */
    @SignalMethod
    void stipResolved();

    @QueryMethod
    FundingState getState();
}
