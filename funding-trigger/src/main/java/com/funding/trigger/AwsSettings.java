package com.funding.trigger;

/**
 * AWS SDK settings from the environment exported by {@code scripts/emulator-env.sh}. Credentials are
 * not configured here: the SDK default chain reads {@code AWS_SHARED_CREDENTIALS_FILE} locally, and
 * the task role on AWS.
 */
public record AwsSettings(String endpoint, String region, String caBundle) {
    public boolean hasEndpoint() {
        return endpoint != null && !endpoint.isBlank();
    }

    public boolean hasCaBundle() {
        return caBundle != null && !caBundle.isBlank();
    }
}
