package com.funding.seed.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.funding.domain.DocumentType;
import com.funding.domain.ExternalApi;
import com.funding.seed.Fixture;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Builds WireMock stubs from scenario {@code externals:} intents and the per-API catalog (constitution
 * P-5.2). Status codes, bodies, delays and retry counts are all computed here from {@link MockBudget}.
 */
public final class MockStubGenerator {

    public static final String DISBURSEMENT_PATH = "/api/v1/external/disbursements";
    private static final ObjectMapper JSON = new ObjectMapper();

    private final String env;
    private final MockBudget budget;

    public MockStubGenerator(String env, MockBudget budget) {
        this.env = env;
        this.budget = budget;
    }

    public List<MockStub> generate(List<Fixture> fixtures, Map<ExternalApi, MockCatalog> catalogs) {
        List<MockStub> out = new ArrayList<>();
        for (Fixture f : fixtures) {
            String intent = f.externals().get("disbursement");
            if (intent != null) {
                out.add(scenarioDisbursement(f, intent, catalogs.get(ExternalApi.DISBURSEMENT)));
            }
        }
        MockCatalog d = catalogs.get(ExternalApi.DISBURSEMENT);
        if (d != null) {
            out.addAll(catalogDisbursement(d));
        }
        return List.copyOf(out);
    }

    // ---------------------------------------------------------------- disbursement

    private MockStub scenarioDisbursement(Fixture f, String cls, MockCatalog catalog) {
        if (catalog == null || !catalog.classes().containsKey(cls)) {
            throw new IllegalArgumentException(f.scenarioId() + ": disbursement intent '" + cls
                    + "' is not a class in disbursement.catalog.yaml");
        }
        if ("RETRY_THEN_OK".equals(cls)) {
            throw new IllegalArgumentException(f.scenarioId() + ": scenario intents can't use RETRY_THEN_OK (needs k)");
        }
        String id = "DISBURSEMENT/scenario/" + f.scenarioId();
        Fixture.Version v = f.latest();
        String amount = v.latestDocuments().stream().filter(d -> d.type() == DocumentType.RISC).findFirst()
                .map(d -> d.value("amount_financed")).orElse("0.00");
        ObjectNode example = disbursementRequest(1, f.loanApplicationId(), v.dealershipId(), amount);
        String keyPattern = "funding-" + env + "-[0-9]+";
        return new MockStub(id, ExternalApi.DISBURSEMENT, "scenario", cls,
                "Idempotency-Key~" + keyPattern + " loanApplicationId=" + f.loanApplicationId(),
                "POST", DISBURSEMENT_PATH,
                new MockStub.RequestMatch(Map.of(), Map.of("Idempotency-Key", keyPattern),
                        Map.of("loanApplicationId", f.loanApplicationId())),
                Map.of("Idempotency-Key", "funding-" + env + "-1"),
                example,
                disbursementInteractions(cls, reference(id)),
                catalog.classes().get(cls).expect(),
                catalog.classes().get(cls).violatesContract());
    }

    private List<MockStub> catalogDisbursement(MockCatalog c) {
        List<MockStub> out = new ArrayList<>();
        for (Map.Entry<String, MockCatalog.ClassSpec> e : c.classes().entrySet()) {
            String cls = e.getKey();
            List<Integer> ks = "retryBudget".equals(e.getValue().k())
                    ? range(1, budget.maxAttempts() - 1) : Collections.singletonList(null);
            for (String amount : c.amounts()) {
                for (Integer k : ks) {
                    String id = "DISBURSEMENT/catalog/" + cls + "/" + amount + (k == null ? "" : "/k" + k);
                    String key = "catalog-" + env + "-" + slug(id);
                    List<Interaction> calls = k == null
                            ? disbursementInteractions(cls, reference(id))
                            : retryThenOk(k, created(reference(id)));
                    out.add(new MockStub(id, ExternalApi.DISBURSEMENT, "catalog", cls,
                            "Idempotency-Key=" + key, "POST", DISBURSEMENT_PATH,
                            new MockStub.RequestMatch(Map.of("Idempotency-Key", key), Map.of(), Map.of("amount", amount)),
                            Map.of("Idempotency-Key", key),
                            disbursementRequest(1, "CAT-" + slug(id), "DLR-100", amount),
                            calls, e.getValue().expect(), e.getValue().violatesContract()));
                }
            }
        }
        return out;
    }

    private List<Interaction> disbursementInteractions(String cls, String reference) {
        Interaction ok = created(reference);
        return switch (cls) {
            case "SUCCESS" -> List.of(ok);
            case "IDEMPOTENT_REPLAY" -> List.of(ok, json(200, body(reference)));
            case "TIMEOUT_BUT_PROCESSED" -> List.of(
                    new Interaction(201, "application/json", body(reference), budget.pastTimeoutMillis()),
                    json(200, body(reference)));
            case "EXHAUSTED" -> List.of(unavailable());
            case "KEY_CONFLICT" -> List.of(problem(409, "Idempotency key conflict", "IDEMPOTENCY_KEY_CONFLICT"));
            case "DEALER_INVALID" -> List.of(problem(422, "Dealer account invalid", "DEALER_ACCOUNT_INVALID"));
            case "MALFORMED" -> List.of(new Interaction(201, "application/json",
                    "{\"status\":\"COMPLETED\",\"reference\":", 0));
            default -> throw new IllegalArgumentException("unknown disbursement class " + cls);
        };
    }

    private List<Interaction> retryThenOk(int k, Interaction then) {
        List<Interaction> calls = new ArrayList<>();
        for (int i = 0; i < k; i++) {
            calls.add(unavailable());
        }
        calls.add(then);
        return calls;
    }

    // ---------------------------------------------------------------- helpers

    private static Interaction created(String reference) {
        return json(201, body(reference));
    }

    private static String body(String reference) {
        return "{\"status\":\"COMPLETED\",\"reference\":\"" + reference + "\"}";
    }

    private static Interaction json(int status, String body) {
        return new Interaction(status, "application/json", body, 0);
    }

    private static Interaction unavailable() {
        return problem(503, "Service unavailable", "UNAVAILABLE");
    }

    private static Interaction problem(int status, String title, String code) {
        return new Interaction(status, "application/problem+json",
                "{\"title\":\"" + title + "\",\"status\":" + status + ",\"code\":\"" + code + "\"}", 0);
    }

    private static ObjectNode disbursementRequest(long versionId, String loanId, String dealer, String amount) {
        ObjectNode n = JSON.createObjectNode();
        n.put("applicationVersionId", versionId);
        n.put("loanApplicationId", loanId);
        n.put("dealershipId", dealer);
        n.put("amount", amount);
        return n;
    }

    /** Env-independent, so mocks.lock is the same on every machine. */
    static String reference(String stubId) {
        return "DSB-" + UUID.nameUUIDFromBytes(stubId.getBytes(StandardCharsets.UTF_8)).toString().substring(0, 8).toUpperCase();
    }

    static String slug(String id) {
        return id.toLowerCase().replaceAll("[^a-z0-9]+", "-").replaceAll("(^-|-$)", "");
    }

    private static List<Integer> range(int from, int to) {
        List<Integer> r = new ArrayList<>();
        for (int i = from; i <= to; i++) {
            r.add(i);
        }
        return r;
    }

    // ---------------------------------------------------------------- WireMock JSON

    /** WireMock mappings for {@code /__admin/mappings/import}. Every mapping carries {@code metadata.env}. */
    public String toWireMockImport(List<MockStub> stubs) {
        List<Object> mappings = new ArrayList<>();
        for (MockStub s : stubs) {
            mappings.addAll(mappings(s));
        }
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("mappings", mappings);
        try {
            return JSON.writerWithDefaultPrettyPrinter().writeValueAsString(root);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    List<Map<String, Object>> mappings(MockStub s) {
        List<Map<String, Object>> out = new ArrayList<>();
        int n = s.interactions().size();
        String scenario = env + ":" + s.id();
        for (int i = 0; i < n; i++) {
            Interaction it = s.interactions().get(i);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", UUID.nameUUIDFromBytes((env + "|" + s.id() + "|" + i).getBytes(StandardCharsets.UTF_8)).toString());
            m.put("name", env + " " + s.id() + (n > 1 ? " #" + (i + 1) : ""));
            m.put("priority", "scenario".equals(s.source()) ? 5 : 3);
            if (n > 1) {
                m.put("scenarioName", scenario);
                m.put("requiredScenarioState", i == 0 ? "Started" : "call-" + (i + 1));
                if (i < n - 1) {
                    m.put("newScenarioState", "call-" + (i + 2));
                }
            }
            m.put("request", request(s));
            Map<String, Object> resp = new LinkedHashMap<>();
            resp.put("status", it.status());
            resp.put("headers", Map.of("Content-Type", it.contentType()));
            resp.put("body", it.body());
            if (it.delayMillis() > 0) {
                resp.put("fixedDelayMilliseconds", it.delayMillis());
            }
            m.put("response", resp);
            m.put("metadata", Map.of("env", env, "api", s.api().name(), "stubId", s.id(),
                    "source", s.source(), "band", s.band(), "generatedBy", "MockStubGenerator"));
            out.add(m);
        }
        return out;
    }

    private static Map<String, Object> request(MockStub s) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("method", s.method());
        r.put("urlPath", s.path());
        Map<String, Object> headers = new LinkedHashMap<>();
        s.requestMatch().headerEquals().forEach((k, v) -> headers.put(k, Map.of("equalTo", v)));
        s.requestMatch().headerMatches().forEach((k, v) -> headers.put(k, Map.of("matches", v)));
        if (!headers.isEmpty()) {
            r.put("headers", headers);
        }
        List<Object> body = new ArrayList<>();
        s.requestMatch().bodyEquals().forEach((k, v) ->
                body.add(Map.of("matchesJsonPath", Map.of("expression", "$." + k, "equalTo", v))));
        if (!body.isEmpty()) {
            r.put("bodyPatterns", body);
        }
        return r;
    }
}
