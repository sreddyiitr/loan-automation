package com.funding.workflows;

import com.funding.domain.CheckOutcome;

public class TilaCheckWorkflowImpl extends CheckWorkflowImpl implements TilaCheckWorkflow {
    public TilaCheckWorkflowImpl(ActivityOptionsConfig options) {
        super(options);
    }

    @Override
    public CheckOutcome run(CheckInput input) {
        return evaluateAndRecord(input);
    }
}
