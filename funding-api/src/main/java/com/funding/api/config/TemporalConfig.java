package com.funding.api.config;

import com.funding.domain.RulesConfig;
import com.funding.workflows.ActivityOptionsConfig;
import com.funding.workflows.TaskQueues;
import com.funding.workflows.Workers;
import com.funding.workflows.activities.DisbursementActivitiesImpl;
import com.funding.workflows.activities.FundingActivitiesImpl;
import com.funding.workflows.activities.IncomeActivitiesImpl;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowClientOptions;
import io.temporal.serviceclient.WorkflowServiceStubs;
import io.temporal.serviceclient.WorkflowServiceStubsOptions;
import io.temporal.worker.WorkerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

/** Temporal client and workers on this env's task queues (plan "Temporal Design"). */
@Configuration
public class TemporalConfig {

    @Bean(destroyMethod = "shutdown")
    public WorkflowServiceStubs workflowServiceStubs(@Value("${funding.temporal.target}") String target) {
        return WorkflowServiceStubs.newServiceStubs(WorkflowServiceStubsOptions.newBuilder().setTarget(target).build());
    }

    @Bean
    public WorkflowClient workflowClient(WorkflowServiceStubs stubs, @Value("${funding.temporal.namespace}") String namespace) {
        return WorkflowClient.newInstance(stubs, WorkflowClientOptions.newBuilder().setNamespace(namespace).build());
    }

    @Bean
    public FundingActivitiesImpl fundingActivities(@Value("${funding.env}") String env, JdbcClient jdbc, TransactionTemplate tx,
                                                   RulesConfig rules,
                                                   @Value("${funding.workflow.expire-after}") java.time.Duration expireAfter) {
        return new FundingActivitiesImpl(env, jdbc, tx, () -> rules, expireAfter);
    }

    @Bean
    public DisbursementActivitiesImpl disbursementActivities(@Value("${funding.env}") String env, JdbcClient jdbc, TransactionTemplate tx,
                                                             @Value("${funding.disbursement.base-url}") String baseUrl,
                                                             ActivityOptionsConfig options) {
        return new DisbursementActivitiesImpl(env, jdbc, tx, baseUrl, options.externalStartToClose());
    }

    @Bean
    public IncomeActivitiesImpl incomeActivities(@Value("${funding.env}") String env, JdbcClient jdbc,
                                                 @Value("${funding.income.base-url}") String baseUrl, ActivityOptionsConfig options) {
        return new IncomeActivitiesImpl(env, jdbc, baseUrl, options.externalStartToClose(), options.maximumInterval());
    }

    @Bean
    public WorkerFactory workerFactory(WorkflowClient client, TaskQueues queues, FundingActivitiesImpl db,
                                       DisbursementActivitiesImpl disbursement, IncomeActivitiesImpl income, ActivityOptionsConfig options) {
        WorkerFactory f = WorkerFactory.newInstance(client);
        Workers.register(f, queues, db, disbursement, income, options);
        return f;
    }

    /** Starts the workers once the context (DB, seed) is ready; stops them first on shutdown. */
    @Bean
    public SmartLifecycle temporalWorkers(WorkerFactory factory) {
        return new SmartLifecycle() {
            private volatile boolean running;
            @Override public void start() { factory.start(); running = true; }
            @Override public void stop() { factory.shutdown(); factory.awaitTermination(10, java.util.concurrent.TimeUnit.SECONDS); running = false; }
            @Override public boolean isRunning() { return running; }
            @Override public int getPhase() { return Integer.MAX_VALUE - 10; }
        };
    }

}
