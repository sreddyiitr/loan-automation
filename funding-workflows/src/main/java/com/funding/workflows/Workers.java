package com.funding.workflows;

import io.temporal.worker.Worker;
import io.temporal.worker.WorkerFactory;

/** Registers workflows and activities on their task queues (plan "Task queues"). */
public final class Workers {
    private Workers() {}

    public static void register(WorkerFactory factory, TaskQueues q, FundingActivities db,
                                DisbursementActivities disbursement, ActivityOptionsConfig options) {
        Worker orchestration = factory.newWorker(q.orchestration());
        orchestration.registerWorkflowImplementationFactory(FundingWorkflow.class, () -> new FundingWorkflowImpl(options));

        Worker validation = factory.newWorker(q.validation());
        validation.registerWorkflowImplementationFactory(TilaCheckWorkflow.class, () -> new TilaCheckWorkflowImpl(options));
        validation.registerWorkflowImplementationFactory(PriceParityWorkflow.class, () -> new PriceParityWorkflowImpl(options));
        validation.registerWorkflowImplementationFactory(VinConsistencyWorkflow.class, () -> new VinConsistencyWorkflowImpl(options));
        validation.registerWorkflowImplementationFactory(GapValidationWorkflow.class, () -> new GapValidationWorkflowImpl(options));
        validation.registerActivitiesImplementations(db);

        Worker external = factory.newWorker(q.external());
        external.registerActivitiesImplementations(disbursement);
    }
}
