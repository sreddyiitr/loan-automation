package com.funding.workflows;

import com.funding.domain.CheckOutcome;
import com.funding.domain.Snapshot;
import com.funding.domain.income.IncomeVerification;
import io.temporal.workflow.Workflow;

/**
 * US-5 income child: call the Income Verification API (external task queue), then evaluate the
 * pure rule on the answer. Retries exhausted make the activity, and so this child, fail; the
 * parent records that as ERROR and holds the version (AS-5.3, AS-8.4). Without a stated income or
 * SSN token the API is not called and the rule returns NEEDS_REVIEW (FR-14).
 */
public class IncomeVerificationWorkflowImpl extends CheckWorkflowImpl implements IncomeVerificationWorkflow {

    private final ActivityOptionsConfig options;

    public IncomeVerificationWorkflowImpl(ActivityOptionsConfig options) {
        super(options);
        this.options = options;
    }

    @Override
    public CheckOutcome run(CheckInput input) {
        Snapshot s = input.snapshot();
        if (s.ssnTokenPresent() && s.statedIncomeAnnual() != null && s.statedIncomeAnnual().signum() > 0) {
            String queue = input.externalTaskQueue() != null ? input.externalTaskQueue() : Workflow.getInfo().getTaskQueue();
            IncomeActivities api = Workflow.newActivityStub(IncomeActivities.class, options.external(queue));
            IncomeVerification v = api.verifyIncome(s.applicationVersionId());
            s = s.withIncome(v);
        }
        return evaluateAndRecord(new CheckInput(input.check(), input.attempt(), s, input.config(), input.externalTaskQueue()));
    }
}
