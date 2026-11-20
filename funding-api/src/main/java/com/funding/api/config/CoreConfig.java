package com.funding.api.config;

import com.funding.domain.RulesConfig;
import com.funding.workflows.ActivityOptionsConfig;
import com.funding.workflows.TaskQueues;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.simple.JdbcClient;

@Configuration
public class CoreConfig {

    /** The same funding.* values the seed generator uses (P-2.1), with Spring's overrides applied. */
    @Bean
    public RulesConfig rulesConfig(Environment env) {
        return RulesConfig.from(env::getProperty);
    }

    @Bean
    public ActivityOptionsConfig activityOptionsConfig(Environment env) {
        return ActivityOptionsConfig.from(env::getProperty);
    }

    @Bean
    public TaskQueues taskQueues(@Value("${funding.env}") String fundingEnv) {
        return TaskQueues.forEnv(fundingEnv);
    }

    @Bean
    public JdbcClient jdbcClient(javax.sql.DataSource ds) {
        return JdbcClient.create(ds);
    }
}
