package com.funding.trigger;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

class StartupGuardTest {
    private static final AwsSettings LOCAL = new AwsSettings("https://127.0.0.1:9297", "us-west-2", null);

    @Test
    void localNeedsLoopbackEndpoint() {
        assertThatCode(() -> StartupGuard.check(List.of("local"), LOCAL, true, true, true)).doesNotThrowAnyException();
        assertThatThrownBy(() -> StartupGuard.check(List.of("local"), new AwsSettings(null, "us-west-2", null), true, true, true))
                .hasMessageContaining("AWS_ENDPOINT_URL");
        assertThatThrownBy(() -> StartupGuard.check(List.of("local"), new AwsSettings("https://sqs.us-west-2.amazonaws.com", "us-west-2", null), true, true, true))
                .hasMessageContaining("loopback");
    }

    @Test
    void awsForbidsBootstrapSeedAndLocalRunner() {
        AwsSettings aws = new AwsSettings(null, "us-west-2", null);
        assertThatCode(() -> StartupGuard.check(List.of("aws"), aws, false, false, false)).doesNotThrowAnyException();
        assertThatThrownBy(() -> StartupGuard.check(List.of("aws"), aws, false, true, false)).hasMessageContaining("aws");
        assertThatThrownBy(() -> StartupGuard.check(List.of("aws"), aws, false, false, true)).hasMessageContaining("aws");
    }

    @Test
    void profilesAreMutuallyExclusive() {
        assertThatThrownBy(() -> StartupGuard.check(List.of("local", "aws"), LOCAL, false, false, false))
                .hasMessageContaining("mutually exclusive");
    }
}
