package com.funding.trigger;

import java.net.URI;
import java.nio.file.Path;
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;
import software.amazon.awssdk.core.client.config.SdkAdvancedClientOption;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.SqsClientBuilder;

/**
 * SQS client (homehub {@code AwsClientConfig} pattern): endpoint override and the emulator's per-run
 * CA trusted only by this client's HTTP stack, never JVM-wide. Host-prefix injection is disabled so
 * requests stay on 127.0.0.1.
 */
public final class SqsClients {
    private SqsClients() {}

    public static SqsClient create(AwsSettings s) {
        UrlConnectionHttpClient.Builder http = UrlConnectionHttpClient.builder();
        if (s.hasCaBundle()) {
            Path ca = Path.of(s.caBundle());
            http.tlsTrustManagersProvider(() -> PemTrustManagers.fromPem(ca));
        }
        SqsClientBuilder b = SqsClient.builder()
                .region(Region.of(s.region()))
                .httpClientBuilder(http)
                .overrideConfiguration(ClientOverrideConfiguration.builder()
                        .putAdvancedOption(SdkAdvancedClientOption.DISABLE_HOST_PREFIX_INJECTION, true).build());
        if (s.hasEndpoint()) {
            b.endpointOverride(URI.create(s.endpoint()));
        }
        return b.build();
    }
}
