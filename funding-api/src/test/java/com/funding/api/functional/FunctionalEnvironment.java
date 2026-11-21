package com.funding.api.functional;

import com.funding.trigger.AwsSettings;
import com.funding.trigger.QueueNames;
import com.funding.trigger.SqsClients;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowClientOptions;
import io.temporal.serviceclient.WorkflowServiceStubs;
import io.temporal.serviceclient.WorkflowServiceStubsOptions;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Random;
import software.amazon.awssdk.services.sqs.SqsClient;

/**
 * NFR-08 isolation on the developer's shared services (plan "Isolation on shared services"):
 * {@code FUNDING_ENV=it-<runId>}, its own database {@code funding_it_<runId>} on the same Postgres,
 * its own queues, task queues, workflow IDs and WireMock stubs. Everything is removed afterwards.
 * Needs the env from {@code make test-functional} (emulator-env, db-env, wiremock-env, oidc-env).
 */
final class FunctionalEnvironment {

    static final String RUN_ID = HexFormat.of().formatHex(new byte[] {(byte) new Random().nextInt(), (byte) new Random().nextInt(), (byte) new Random().nextInt()});
    static final String ENV = "it-" + RUN_ID;
    static final String DB_NAME = "funding_it_" + RUN_ID;
    static final int PORT = freePort();
    static final List<String> EXTRA_QUEUES = new ArrayList<>();

    private static final String DB_URL = require("FUNDING_DB_URL");
    private static final String DB_USER = require("FUNDING_DB_USER");
    private static final String DB_PASSWORD = System.getenv().getOrDefault("FUNDING_DB_PASSWORD", "");
    static final String WIREMOCK_URL = require("WIREMOCK_URL");
    /** Spec 002 FR-09: the shared mock OIDC provider. Tokens need no isolation: they name a user, nothing else. */
    static final String OIDC_ISSUER_URI = require("OIDC_ISSUER_URI");
    /** The user the functional tests act as; SUPERVISOR, since supervisors may do everything (spec 002 D-11). */
    static final String TEST_USER = "funder-it";

    static {
        require("AWS_ENDPOINT_URL");
        exec(adminUrl(), "CREATE DATABASE " + DB_NAME);
        Runtime.getRuntime().addShutdownHook(new Thread(FunctionalEnvironment::cleanUp, "functional-cleanup"));
    }

    private FunctionalEnvironment() {}

    static Map<String, String> springProperties() {
        Map<String, String> p = new java.util.HashMap<>(baseProperties());
        p.put("spring.security.oauth2.resourceserver.jwt.issuer-uri", OIDC_ISSUER_URI);
        p.put("funding.auth.users[" + TEST_USER + "]", "SUPERVISOR");
        return Map.copyOf(p);
    }

    private static Map<String, String> baseProperties() {
        return Map.of(
                "spring.datasource.url", dbUrl(),
                "spring.datasource.username", DB_USER,
                "spring.datasource.password", DB_PASSWORD,
                "funding.env", ENV,
                "server.port", Integer.toString(PORT),
                "funding.trigger.api-base-url", "http://localhost:" + PORT,
                "funding.trigger.local-runner.wait-seconds", "1",
                "funding.disbursement.base-url", WIREMOCK_URL,
                "funding.income.base-url", WIREMOCK_URL);
    }

    static String dbUrl() {
        return DB_URL.replaceFirst("/[^/?]+(\\?|$)", "/" + DB_NAME + "$1");
    }

    static AwsSettings aws() {
        return new AwsSettings(System.getenv("AWS_ENDPOINT_URL"), System.getenv().getOrDefault("AWS_REGION", "us-west-2"),
                System.getenv("AWS_CA_BUNDLE"));
    }

    private static void cleanUp() {
        List<Long> versionIds = new ArrayList<>();
        try (Connection c = DriverManager.getConnection(dbUrl(), DB_USER, DB_PASSWORD)) {
            var rs = c.createStatement().executeQuery("SELECT application_version_id FROM application_versions");
            while (rs.next()) {
                versionIds.add(rs.getLong(1));
            }
        } catch (SQLException ignored) {
            // database never got migrated
        }
        terminateWorkflows(versionIds);
        try (SqsClient sqs = SqsClients.create(aws())) {
            List<String> names = new ArrayList<>(EXTRA_QUEUES);
            names.add(QueueNames.forEnv(ENV).queue());
            names.add(QueueNames.forEnv(ENV).dlq());
            for (String n : names) {
                try {
                    sqs.deleteQueue(b -> b.queueUrl(sqs.getQueueUrl(q -> q.queueName(n)).queueUrl()));
                } catch (RuntimeException ignored) {
                    // not created
                }
            }
        } catch (RuntimeException e) {
            System.err.println("functional cleanup: SQS: " + e);
        }
        try {
            HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create(WIREMOCK_URL + "/__admin/mappings/remove-by-metadata"))
                    .POST(HttpRequest.BodyPublishers.ofString("{\"matchesJsonPath\":{\"expression\":\"$.env\",\"equalTo\":\"" + ENV + "\"}}"))
                    .build(), HttpResponse.BodyHandlers.discarding());
        } catch (IOException | InterruptedException e) {
            System.err.println("functional cleanup: WireMock: " + e);
        }
        exec(adminUrl(), "DROP DATABASE IF EXISTS " + DB_NAME + " WITH (FORCE)");
    }

    private static void terminateWorkflows(List<Long> versionIds) {
        if (versionIds.isEmpty()) {
            return;
        }
        WorkflowServiceStubs stubs = WorkflowServiceStubs.newServiceStubs(WorkflowServiceStubsOptions.newBuilder()
                .setTarget(System.getenv().getOrDefault("TEMPORAL_ADDRESS", "localhost:7233")).build());
        try {
            WorkflowClient client = WorkflowClient.newInstance(stubs, WorkflowClientOptions.newBuilder().setNamespace("default").build());
            for (long id : versionIds) {
                try {
                    client.newUntypedWorkflowStub("funding-" + ENV + "-" + id).terminate("functional test cleanup");
                } catch (RuntimeException ignored) {
                    // already closed
                }
            }
        } finally {
            stubs.shutdown();
        }
    }

    private static String adminUrl() {
        return DB_URL.replaceFirst("/[^/?]+(\\?|$)", "/postgres$1");
    }

    private static void exec(String url, String sql) {
        try (Connection c = DriverManager.getConnection(url, DB_USER, DB_PASSWORD)) {
            c.createStatement().execute(sql);
        } catch (SQLException e) {
            throw new IllegalStateException("functional env: " + sql + ": " + e.getMessage(), e);
        }
    }

    private static String require(String name) {
        String v = System.getenv(name);
        if (v == null || v.isBlank()) {
            throw new IllegalStateException(name + " is not set: run functional tests with `make test-functional` "
                    + "(it sources emulator-env.sh, db-env.sh, wiremock-env.sh and oidc-env.sh)");
        }
        return v;
    }

    private static int freePort() {
        try (ServerSocket s = new ServerSocket(0)) {
            return s.getLocalPort();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
