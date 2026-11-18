package com.funding.api.config;

import com.funding.api.trigger.StartWorkflowService;
import com.funding.api.trigger.TriggerApiKeyFilter;
import com.funding.trigger.AwsSettings;
import com.funding.trigger.InfraBootstrap;
import com.funding.trigger.LocalLambdaRunner;
import com.funding.trigger.OutboxRelay;
import com.funding.trigger.QueueNames;
import com.funding.trigger.SqsClients;
import com.funding.trigger.StartupGuard;
import com.funding.trigger.lambda.SqsTriggerHandler;
import com.funding.workflows.TaskQueues;
import io.temporal.client.WorkflowClient;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.support.TransactionTemplate;
import software.amazon.awssdk.services.sqs.SqsClient;

/** Trigger path: outbox relay -> SQS (emulator) -> LocalLambdaRunner -> start API (plan "Trigger Path"). */
@Configuration
public class TriggerConfig {

    private static final Logger LOG = LoggerFactory.getLogger(TriggerConfig.class);

    @Bean
    public AwsSettings awsSettings(Environment env,
                                   @Value("${funding.aws.endpoint:}") String endpoint,
                                   @Value("${funding.aws.region}") String region,
                                   @Value("${funding.aws.ca-bundle:}") String ca,
                                   @Value("${funding.bootstrap-infra}") boolean bootstrap,
                                   @Value("${funding.seed-data}") boolean seed,
                                   @Value("${funding.trigger.local-runner.enabled}") boolean runner) {
        AwsSettings s = new AwsSettings(endpoint, region, ca);
        String[] profiles = env.getActiveProfiles().length > 0 ? env.getActiveProfiles() : env.getDefaultProfiles();
        StartupGuard.check(Arrays.asList(profiles), s, bootstrap, seed, runner);
        return s;
    }

    @Bean(destroyMethod = "close")
    public SqsClient sqsClient(AwsSettings s) {
        return SqsClients.create(s);
    }

    @Bean
    public QueueNames queueNames(@Value("${funding.env}") String env) {
        return QueueNames.forEnv(env);
    }

    /** Bootstraps the queue under {@code local} (in-memory emulator), otherwise just looks it up. */
    @Bean
    public InfraBootstrap.Queues queues(SqsClient sqs, QueueNames names,
                                        @Value("${funding.bootstrap-infra}") boolean bootstrap,
                                        @Value("${funding.trigger.queue.visibility-timeout-seconds}") int visibility,
                                        @Value("${funding.trigger.queue.max-receive-count}") int maxReceive) {
        if (bootstrap) {
            InfraBootstrap.Queues q = InfraBootstrap.ensureQueues(sqs, names, visibility, maxReceive);
            LOG.info("queues ready: {} (DLQ {})", q.queueUrl(), q.dlqUrl());
            return q;
        }
        return new InfraBootstrap.Queues(
                sqs.getQueueUrl(b -> b.queueName(names.queue())).queueUrl(),
                sqs.getQueueUrl(b -> b.queueName(names.dlq())).queueUrl());
    }

    @Bean
    public OutboxRelay outboxRelay(JdbcClient jdbc, TransactionTemplate tx, SqsClient sqs, InfraBootstrap.Queues q,
                                   @Value("${funding.relay.batch-size}") int batch) {
        return new OutboxRelay(jdbc, tx, sqs, q.queueUrl(), batch);
    }

    @Bean
    public RelaySchedule relaySchedule(OutboxRelay relay) {
        return new RelaySchedule(relay);
    }

    public static class RelaySchedule {
        private final OutboxRelay relay;
        RelaySchedule(OutboxRelay relay) { this.relay = relay; }

        @Scheduled(fixedDelayString = "${funding.relay.interval-ms}")
        public void relay() {
            try {
                relay.relayOnce();
            } catch (RuntimeException e) {
                LOG.warn("outbox relay pass failed: {}", e.toString());
            }
        }

        @Scheduled(fixedDelay = 3_600_000, initialDelay = 60_000)
        public void purge() {
            relay.purge(7);
        }
    }

    @Bean
    public StartWorkflowService startWorkflowService(JdbcClient jdbc, WorkflowClient client,
                                                     @Value("${funding.env}") String env, TaskQueues queues) {
        return new StartWorkflowService(jdbc, client, env, queues);
    }

    @Bean
    public FilterRegistrationBean<TriggerApiKeyFilter> triggerApiKeyFilter(@Value("${funding.trigger.api-key:}") String key) {
        FilterRegistrationBean<TriggerApiKeyFilter> r = new FilterRegistrationBean<>(new TriggerApiKeyFilter(key));
        r.setUrlPatterns(List.of("/api/v1/funding-workflows", "/api/v1/funding-workflows/*"));
        return r;
    }

    /** {@code local}: the emulator can't run the java21 Lambda (V-2, V-4), so run the same handler here. */
    @Bean(destroyMethod = "close")
    @ConditionalOnProperty(name = "funding.trigger.local-runner.enabled", havingValue = "true")
    public LocalLambdaRunner localLambdaRunner(SqsClient sqs, InfraBootstrap.Queues q,
                                               @Value("${funding.trigger.api-base-url}") String apiBase,
                                               @Value("${funding.trigger.api-key}") String key,
                                               @Value("${funding.trigger.http-timeout}") Duration timeout,
                                               @Value("${funding.trigger.local-runner.wait-seconds}") int wait) {
        return new LocalLambdaRunner(sqs, q.queueUrl(), new SqsTriggerHandler(apiBase, key, timeout), wait);
    }

    @EventListener(ApplicationReadyEvent.class)
    public void startRunner(ApplicationReadyEvent e) {
        e.getApplicationContext().getBeanProvider(LocalLambdaRunner.class).ifAvailable(r -> {
            r.start();
            LOG.info("local Lambda runner polling (stands in for the SQS event source mapping)");
        });
    }
}
