package com.funding.workflows;

import com.funding.domain.Check;
import io.temporal.workflow.QueryMethod;
import io.temporal.workflow.SignalMethod;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;
import java.util.List;

/**
 * Parent workflow, one per application version (FR-03). Funder actions are written to the DB by the
 * API first (guarded and audited), then signalled here (plan "Funder actions"): every signal is a
 * wake-up after which the workflow re-settles the version from the DB.
 */
@WorkflowInterface
public interface FundingWorkflow {

    @WorkflowMethod
    FundingResult run(FundingInput input);

    /** A stip was resolved or a manual stip was added (AS-8.3, AS-10.8). */
    @SignalMethod
    void stipResolved();

    /** The API recorded a manual approve (AS-8.7): disburse. */
    @SignalMethod
    void approve(String actor);

    /** The API recorded a reject (AS-8.5): complete without disbursement. */
    @SignalMethod
    void reject(String actor, String reason);

    /** Re-run these checks with the next attempt number on a freshly loaded snapshot (AS-8.4). */
    @SignalMethod
    void rerunChecks(List<Check> checks);

    /** Try a failed disbursement again (C-8). */
    @SignalMethod
    void retryDisbursement(String actor);

    /**
     * A new latest document arrived while the version was under review (AS-1.8, C-10): re-run every
     * check against the latest documents. Delivered at least once, so a {@code documentId} already
     * handled is ignored.
     */
    @SignalMethod
    void documentsUpdated(Long documentId);

    /**
     * A newer application version was submitted (AS-9.2): cancel any running checks and complete
     * {@code SUPERSEDED}. Results and stips already written stay as history; the status is not touched.
     */
    @SignalMethod
    void supersede();

    @QueryMethod
    FundingState getState();
}
