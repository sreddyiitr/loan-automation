package com.funding.api.seed;

import com.funding.domain.RulesConfig;
import com.funding.seed.RuleFingerprints;
import com.funding.seed.SeedConfig;
import com.funding.seed.SeedGates;
import com.funding.seed.SeedPaths;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;

/** Seed generator wired to the app's own effective config, so it seeds exactly what the rules expect (P-2.1). */
@Configuration
@Profile("local")
public class SeedSupport {

    @Bean
    public SeedConfig seedConfig(RulesConfig rules, Environment env, @Value("${funding.env}") String fundingEnv) {
        return new SeedConfig(rules, env::getProperty, fundingEnv);
    }

    @Bean
    public SeedGates seedGates(SeedConfig config) {
        return new SeedGates(SeedPaths.discover(), config, RuleFingerprints.fromClasspath());
    }
}
