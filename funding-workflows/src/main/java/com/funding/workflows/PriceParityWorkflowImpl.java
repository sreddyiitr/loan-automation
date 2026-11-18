package com.funding.workflows;

import com.funding.domain.CheckOutcome;

public class PriceParityWorkflowImpl extends CheckWorkflowImpl implements PriceParityWorkflow {
    public PriceParityWorkflowImpl(ActivityOptionsConfig options) {
        super(options);
    }

    @Override
    public CheckOutcome run(CheckInput input) {
        return evaluateAndRecord(input);
    }
}
