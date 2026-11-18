package com.funding.workflows;

import com.funding.domain.CheckOutcome;
import com.funding.domain.RuleEngine;
import io.temporal.workflow.Workflow;

/**
 * A check child: evaluate the pure rule (deterministic, so it runs in workflow code) and persist the
 * result and stips in one activity. Business FAIL is a return value, so Temporal never retries it.
 */
abstract class CheckWorkflowImpl {

    private static final RuleEngine ENGINE = new RuleEngine();
    private final FundingActivities activities;

    CheckWorkflowImpl(ActivityOptionsConfig options) {
        this.activities = Workflow.newActivityStub(FundingActivities.class,
                options.db(Workflow.getInfo().getTaskQueue()));
    }

    CheckOutcome evaluateAndRecord(CheckInput in) {
        CheckOutcome outcome = ENGINE.evaluate(in.check(), in.snapshot(), in.config());
        activities.recordOutcome(in.snapshot().applicationVersionId(), outcome, in.attempt());
        return outcome;
    }
}
