package com.funding.workflows;

import com.funding.domain.CheckOutcome;

public class VinConsistencyWorkflowImpl extends CheckWorkflowImpl implements VinConsistencyWorkflow {
    public VinConsistencyWorkflowImpl(ActivityOptionsConfig options) {
        super(options);
    }

    @Override
    public CheckOutcome run(CheckInput input) {
        return evaluateAndRecord(input);
    }
}
