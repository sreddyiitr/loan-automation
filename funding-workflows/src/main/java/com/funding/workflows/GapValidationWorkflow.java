package com.funding.workflows;

import com.funding.domain.CheckOutcome;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;

@WorkflowInterface
public interface GapValidationWorkflow {
    @WorkflowMethod
    CheckOutcome run(CheckInput input);
}
