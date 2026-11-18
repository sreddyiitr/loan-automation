package com.funding.seed.mock;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.funding.domain.DocumentType;
import com.funding.domain.ExternalApi;
import com.funding.domain.money.Money;
import com.funding.seed.Fixture;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Builds WireMock stubs from scenario {@code externals:} intents and the per-API catalogs
 * (constitution P-5.2). Status codes, bodies, delays and retry counts are all computed here from
 * {@link MockBudget} and the live variance threshold; nothing is typed by hand.
 */
public final class MockStubGenerator {

    public static final String DISBURSEMENT_PATH = "/api/v1/external/disbursements";
    public static final String INCOME_PATH = "/api/v1/external/verify-income";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String VERIFIED_AT = "2026-11-01T00:00:00Z";

    private final String env;
    private final MockBudget budget;
    private final BigDecimal threshold;

    public MockStubGenerator(String env, MockBudget budget, BigDecimal varianceThreshold) {
        this.env = env;
        this.budget = budget;
        this.threshold = varianceThreshold;
    }

    public List<MockStub> generate(List<Fixture> fixtures, Map<ExternalApi, MockCatalog> catalogs) {
        List<MockStub> out = new ArrayList<>();
        MockCatalog disb = catalogs.get(ExternalApi.DISBURSEMENT);
        MockCatalog income = catalogs.get(ExternalApi.INCOME_VERIFICATION);
        for (Fixture f : fixtures) {
            for (ExternalApi api : List.of(ExternalApi.DISBURSEMENT, ExternalApi.INCOME_VERIFICATION)) {
                String intent = f.externals().get(externalsKey(api));
                if (intent != null && catalogs.containsKey(api)) {
                    out.add(scenarioStub(f, api, intent, catalogs));
                }
            }
        }
        if (disb != null) {
            out.addAll(catalogDisbursement(disb));
        }
        if (income != null) {
            out.addAll(catalogIncome(income));
        }
        return List.copyOf(out);
    }

    /**
     * The scenario's stub for {@code api} answering with {@code intent}: the generator's only way to
     * build a per-scenario stub, used by {@link #generate} and by tests that swap a scenario's
     * intent (T313). Throws for an intent the catalog doesn't define.
     */
    public MockStub scenarioStub(Fixture f, ExternalApi api, String intent, Map<ExternalApi, MockCatalog> catalogs) {
        MockCatalog c = catalogs.get(api);
        if (c == null) {
            throw new IllegalArgumentException("no mock catalog for " + api);
        }
        return switch (api) {
            case DISBURSEMENT -> scenarioDisbursement(f, intent, c);
            case INCOME_VERIFICATION -> scenarioIncome(f, intent, c);
        };
    }

    /** The scenario {@code externals:} key for an API. */
    public static String externalsKey(ExternalApi api) {
        return switch (api) {
            case DISBURSEMENT -> "disbursement";
            case INCOME_VERIFICATION -> "income";
        };
    }

    // ================================================================ income

    /** One stub per scenario, matched on that scenario's token {@code enc:<env>:<scenario>}. */
    public MockStub scenarioIncome(Fixture f, String intent, MockCatalog c) {
        JsonNode spec = c.scenarioIntents().get(intent);
        if (spec == null) {
            throw new IllegalArgumentException(f.scenarioId() + ": income intent '" + intent + "' is not in income-verification.catalog.yaml "
                    + c.scenarioIntents().keySet());
        }
        String token = "enc:" + env + ":" + f.scenarioId();
        String respond = spec.path("respond").asText();
        List<Interaction> calls = switch (respond) {
            case "ok" -> List.of(json(200, verifiedBody(new BigDecimal(spec.path("verified").asText()), "SINGLE")));
            case "down" -> List.of(unavailable());
            case "flaky" -> retryThenOk(budget.maxAttempts() - 1, json(200, verifiedBody(new BigDecimal(spec.path("verified").asText()), "SINGLE")));
            case "not-found" -> List.of(json(200, "{\"status\":\"NOT_FOUND\"}"));
            default -> throw new IllegalArgumentException(f.scenarioId() + ": unknown income respond '" + respond + "'");
        };
        Map<String, String> attrs = new LinkedHashMap<>();
        attrs.put("statedIncome", f.latest().statedIncomeAnnual().toPlainString());
        attrs.put("magnitude", f.latest().statedIncomeAnnual().toPlainString());
        attrs.put("intent", intent);
        String expected = f.intent().expect().check(com.funding.domain.Check.INCOME).name();
        return incomeStub("INCOME_VERIFICATION/scenario/" + f.scenarioId(), "scenario", intent, token, calls, expected, false, attrs);
    }

    private List<MockStub> catalogIncome(MockCatalog c) {
        List<MockStub> out = new ArrayList<>();
        for (Map.Entry<String, MockCatalog.ClassSpec> e : c.classes().entrySet()) {
            String cls = e.getKey();
            MockCatalog.ClassSpec spec = e.getValue();
            if (spec.raw().has("points")) {                         // variance band
                for (String point : spec.list("points")) {
                    for (String mag : c.magnitudes()) {
                        out.add(varianceStub(cls, spec.expect(), point, mag, "SINGLE", "INCOME_VERIFICATION/catalog/" + cls + "/" + point + "/" + mag));
                    }
                }
            } else if ("NOT_FOUND".equals(cls)) {
                for (String mag : c.magnitudes()) {
                    String id = "INCOME_VERIFICATION/catalog/NOT_FOUND/" + mag;
                    out.add(incomeStub(id, "catalog", cls, catalogToken(id), List.of(json(200, "{\"status\":\"NOT_FOUND\"}")),
                            spec.expect(), false, Map.of("magnitude", mag, "statedIncome", mag)));
                }
            } else if (spec.raw().has("shapes")) {                  // response shapes over other bands
                for (String shape : spec.list("shapes")) {
                    for (String from : spec.list("from")) {
                        MockCatalog.ClassSpec src = c.classes().get(from);
                        String point = src.list("points").get(0);
                        for (String mag : spec.list("magnitudes")) {
                            out.add(varianceStub(from, src.expect(), point, mag, shape,
                                    "INCOME_VERIFICATION/catalog/SHAPE_" + shape + "/" + from + "/" + point + "/" + mag));
                        }
                    }
                }
            } else {                                                // fault class
                for (String mag : c.magnitudes()) {
                    out.addAll(faultStubs(cls, spec, mag));
                }
            }
        }
        return out;
    }

    private MockStub varianceStub(String band, String expect, String point, String stated, String shape, String id) {
        BigDecimal v = VariancePoint.resolve(point, threshold);
        BigDecimal verified = VariancePoint.verifiedFor(new BigDecimal(stated), v, "PASS".equals(expect));
        Map<String, String> attrs = new LinkedHashMap<>();
        attrs.put("magnitude", stated);
        attrs.put("statedIncome", stated);
        attrs.put("point", point);
        attrs.put("verified", verified.toPlainString());
        attrs.put("shape", shape);
        return incomeStub(id, "catalog", band, catalogToken(id), List.of(json(200, verifiedBody(verified, shape))), expect, false, attrs);
    }

    private List<MockStub> faultStubs(String cls, MockCatalog.ClassSpec spec, String stated) {
        String base = "INCOME_VERIFICATION/catalog/" + cls + "/" + stated;
        Map<String, String> attrs = new LinkedHashMap<>();
        attrs.put("magnitude", stated);
        attrs.put("statedIncome", stated);
        Interaction ok = null;
        if (spec.raw().hasNonNull("point")) {
            BigDecimal v = VariancePoint.resolve(spec.raw().get("point").asText(), threshold);
            BigDecimal verified = VariancePoint.verifiedFor(new BigDecimal(stated), v, true);
            attrs.put("point", spec.raw().get("point").asText());
            attrs.put("verified", verified.toPlainString());
            ok = json(200, verifiedBody(verified, "SINGLE"));
        }
        BigDecimal anyVerified = new BigDecimal(stated);
        List<MockStub> out = new ArrayList<>();
        switch (cls) {
            case "RETRY_THEN_OK" -> {
                for (int k = 1; k <= budget.maxAttempts() - 1; k++) {
                    String id = base + "/k" + k;
                    out.add(incomeStub(id, "catalog", cls, catalogToken(id), retryThenOk(k, ok), spec.expect(), false, with(attrs, "k", k)));
                }
            }
            case "EXHAUSTED" -> out.add(incomeStub(base, "catalog", cls, catalogToken(base), List.of(unavailable()), spec.expect(), false, attrs));
            case "TIMEOUT_THEN_OK" -> out.add(incomeStub(base, "catalog", cls, catalogToken(base),
                    List.of(delayed(ok), ok), spec.expect(), false, attrs));
            case "TIMEOUT_ALWAYS" -> out.add(incomeStub(base, "catalog", cls, catalogToken(base),
                    List.of(delayed(json(200, verifiedBody(anyVerified, "SINGLE")))), spec.expect(), false, attrs));
            case "RATE_LIMITED" -> {
                int after = spec.raw().path("retryAfterSeconds").asInt(2);
                Interaction limited = new Interaction(429, "application/problem+json",
                        "{\"title\":\"Too many requests\",\"status\":429,\"code\":\"RATE_LIMITED\"}", 0,
                        Map.of("Retry-After", Integer.toString(after)), null);
                out.add(incomeStub(base, "catalog", cls, catalogToken(base), List.of(limited, ok), spec.expect(), false,
                        with(attrs, "retryAfterSeconds", after)));
            }
            case "CONNECTION_RESET" -> out.add(incomeStub(base, "catalog", cls, catalogToken(base),
                    List.of(Interaction.fault("CONNECTION_RESET_BY_PEER"), ok), spec.expect(), false, attrs));
            case "MALFORMED" -> out.add(incomeStub(base, "catalog", cls, catalogToken(base),
                    List.of(json(200, "{\"status\":\"VERIFIED\",\"totalVerifiedAnnualIncome\":")), spec.expect(), true, attrs));
            case "SCHEMA_INVALID" -> out.add(incomeStub(base, "catalog", cls, catalogToken(base),
                    List.of(json(200, "{\"status\":\"VERIFIED\",\"verifiedAt\":\"" + VERIFIED_AT + "\"}")), spec.expect(), true, attrs));
            case "CLIENT_ERROR" -> {
                for (String status : spec.list("statuses")) {
                    String id = base + "/" + status;
                    int st = Integer.parseInt(status);
                    out.add(incomeStub(id, "catalog", cls, catalogToken(id), List.of(problem(st, "Client error", "CLIENT_ERROR_" + st)),
                            spec.expect(), false, with(attrs, "status", st)));
                }
            }
            default -> throw new IllegalArgumentException("unknown income catalog class " + cls);
        }
        return out;
    }

    private MockStub incomeStub(String id, String source, String band, String token, List<Interaction> calls, String expected,
                                boolean violates, Map<String, String> attrs) {
        ObjectNode example = JSON.createObjectNode();
        example.put("ssnItinEncrypted", token);
        example.put("applicationVersionId", 1);
        Map<String, String> a = new LinkedHashMap<>(attrs);
        a.put("token", token);
        return new MockStub(id, ExternalApi.INCOME_VERIFICATION, source, band,
                "ssnItinEncrypted=" + token.replace(env, "{env}"), "POST", INCOME_PATH,
                new MockStub.RequestMatch(Map.of(), Map.of(), Map.of("ssnItinEncrypted", token)),
                Map.of(), example, calls, expected, violates, a);
    }

    private String catalogToken(String id) {
        return "enc:" + env + ":cat-" + slug(id.replace("INCOME_VERIFICATION/catalog/", ""));
    }

    static String verifiedBody(BigDecimal verified, String shape) {
        ObjectNode n = JSON.createObjectNode();
        n.put("status", "VERIFIED");
        n.put("totalVerifiedAnnualIncome", Money.format(verified));
        switch (shape) {
            case "SINGLE", "EXTRA_FIELDS" -> {
                ArrayNode emp = n.putArray("employers");
                emp.addObject().put("name", "Lone Star Logistics").put("annualIncome", Money.format(verified));
                n.put("verifiedAt", VERIFIED_AT);
                if ("EXTRA_FIELDS".equals(shape)) {
                    n.put("payrollProvider", "PayCo");      // unknown to the contract: must be ignored
                    n.putObject("meta").put("source", "w2").put("year", 2025);
                }
            }
            case "MULTI_EMPLOYER" -> {
                BigDecimal first = Money.round(verified.multiply(new BigDecimal("0.6")));
                ArrayNode emp = n.putArray("employers");
                emp.addObject().put("name", "Lone Star Logistics").put("annualIncome", Money.format(first));
                emp.addObject().put("name", "Gulf Coast Retail").put("annualIncome", Money.format(verified.subtract(first)));
                n.put("verifiedAt", VERIFIED_AT);
            }
            case "OPTIONAL_ABSENT" -> { /* only the required fields */ }
            default -> throw new IllegalArgumentException("unknown shape " + shape);
        }
        return n.toString();
    }

    // ================================================================ disbursement

    private MockStub scenarioDisbursement(Fixture f, String cls, MockCatalog catalog) {
        if (!catalog.classes().containsKey(cls)) {
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
                "Idempotency-Key~funding-{env}-[0-9]+ loanApplicationId=" + f.loanApplicationId(),
                "POST", DISBURSEMENT_PATH,
                new MockStub.RequestMatch(Map.of(), Map.of("Idempotency-Key", keyPattern),
                        Map.of("loanApplicationId", f.loanApplicationId())),
                Map.of("Idempotency-Key", "funding-" + env + "-1"),
                example,
                disbursementInteractions(cls, reference(id)),
                catalog.classes().get(cls).expect(),
                catalog.classes().get(cls).violatesContract(),
                Map.of("magnitude", amount));
    }

    private List<MockStub> catalogDisbursement(MockCatalog c) {
        List<MockStub> out = new ArrayList<>();
        for (Map.Entry<String, MockCatalog.ClassSpec> e : c.classes().entrySet()) {
            String cls = e.getKey();
            List<Integer> ks = "retryBudget".equals(e.getValue().k())
                    ? range(1, budget.maxAttempts() - 1) : Collections.singletonList(null);
            for (String amount : c.magnitudes()) {
                for (Integer k : ks) {
                    String id = "DISBURSEMENT/catalog/" + cls + "/" + amount + (k == null ? "" : "/k" + k);
                    String key = "catalog-" + env + "-" + slug(id);
                    List<Interaction> calls = k == null
                            ? disbursementInteractions(cls, reference(id))
                            : retryThenOk(k, json(201, disbursementBody(reference(id))));
                    out.add(new MockStub(id, ExternalApi.DISBURSEMENT, "catalog", cls,
                            "Idempotency-Key=" + key.replace(env, "{env}"), "POST", DISBURSEMENT_PATH,
                            new MockStub.RequestMatch(Map.of("Idempotency-Key", key), Map.of(), Map.of("amount", amount)),
                            Map.of("Idempotency-Key", key),
                            disbursementRequest(1, "CAT-" + slug(id), "DLR-100", amount),
                            calls, e.getValue().expect(), e.getValue().violatesContract(),
                            k == null ? Map.of("magnitude", amount, "idempotencyKey", key)
                                    : Map.of("magnitude", amount, "idempotencyKey", key, "k", Integer.toString(k))));
                }
            }
        }
        return out;
    }

    private List<Interaction> disbursementInteractions(String cls, String reference) {
        Interaction ok = json(201, disbursementBody(reference));
        return switch (cls) {
            case "SUCCESS" -> List.of(ok);
            case "IDEMPOTENT_REPLAY" -> List.of(ok, json(200, disbursementBody(reference)));
            case "TIMEOUT_BUT_PROCESSED" -> List.of(delayed(ok), json(200, disbursementBody(reference)));
            case "EXHAUSTED" -> List.of(unavailable());
            case "KEY_CONFLICT" -> List.of(problem(409, "Idempotency key conflict", "IDEMPOTENCY_KEY_CONFLICT"));
            case "DEALER_INVALID" -> List.of(problem(422, "Dealer account invalid", "DEALER_ACCOUNT_INVALID"));
            case "MALFORMED" -> List.of(json(201, "{\"status\":\"COMPLETED\",\"reference\":"));
            default -> throw new IllegalArgumentException("unknown disbursement class " + cls);
        };
    }

    private static String disbursementBody(String reference) {
        return "{\"status\":\"COMPLETED\",\"reference\":\"" + reference + "\"}";
    }

    private static ObjectNode disbursementRequest(long versionId, String loanId, String dealer, String amount) {
        ObjectNode n = JSON.createObjectNode();
        n.put("applicationVersionId", versionId);
        n.put("loanApplicationId", loanId);
        n.put("dealershipId", dealer);
        n.put("amount", amount);
        return n;
    }

    // ================================================================ shared helpers

    private Interaction delayed(Interaction i) {
        return new Interaction(i.status(), i.contentType(), i.body(), budget.pastTimeoutMillis(), i.headers(), i.fault());
    }

    private static List<Interaction> retryThenOk(int k, Interaction then) {
        List<Interaction> calls = new ArrayList<>();
        for (int i = 0; i < k; i++) {
            calls.add(unavailable());
        }
        calls.add(then);
        return calls;
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

    private static Map<String, String> with(Map<String, String> m, String k, Object v) {
        Map<String, String> c = new LinkedHashMap<>(m);
        c.put(k, String.valueOf(v));
        return c;
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

    // ================================================================ WireMock JSON

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
            if (it.fault() != null) {
                resp.put("fault", it.fault());
            } else {
                resp.put("status", it.status());
                Map<String, String> headers = new LinkedHashMap<>(it.headers());
                headers.put("Content-Type", it.contentType());
                resp.put("headers", headers);
                resp.put("body", it.body());
            }
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

    /** The WireMock request pattern a stub matches on (also used to count a stub's calls). */
    public static Map<String, Object> request(MockStub s) {
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
