package com.funding.trigger;

import java.net.URI;
import java.util.Collection;
import java.util.Set;

/**
 * Fail-fast checks (plan "StartupGuard", ported from homehub): {@code local} requires a loopback
 * {@code AWS_ENDPOINT_URL} and never falls back to real AWS; {@code aws} forbids bootstrapping,
 * seeding and the local Lambda runner.
 */
public final class StartupGuard {

    private static final Set<String> LOOPBACK = Set.of("127.0.0.1", "localhost", "::1", "[::1]");

    private StartupGuard() {}

    public static void check(Collection<String> profiles, AwsSettings aws, boolean bootstrap, boolean seed, boolean localRunner) {
        boolean local = profiles.contains("local");
        boolean onAws = profiles.contains("aws");
        if (local && onAws) {
            throw new IllegalStateException("profiles 'local' and 'aws' are mutually exclusive");
        }
        if (onAws && (bootstrap || seed || localRunner)) {
            throw new IllegalStateException("funding.bootstrap-infra, funding.seed-data and funding.trigger.local-runner.enabled "
                    + "must be false under the 'aws' profile");
        }
        if (local) {
            if (!aws.hasEndpoint()) {
                throw new IllegalStateException("profile 'local' requires AWS_ENDPOINT_URL (source scripts/emulator-env.sh); "
                        + "refusing to fall back to real AWS");
            }
            String host = URI.create(aws.endpoint()).getHost();
            if (host == null || !LOOPBACK.contains(host)) {
                throw new IllegalStateException("profile 'local' needs a loopback AWS endpoint, got " + host);
            }
        }
    }
}
