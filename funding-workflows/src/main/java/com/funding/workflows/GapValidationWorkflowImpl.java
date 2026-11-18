package com.funding.workflows;

import com.funding.domain.CheckOutcome;

public class GapValidationWorkflowImpl extends CheckWorkflowImpl implements GapValidationWorkflow {
    public GapValidationWorkflowImpl(ActivityOptionsConfig options) {
        super(options);
    }

    @Override
    public CheckOutcome run(CheckInput input) {
        return evaluateAndRecord(input);
    }
}
