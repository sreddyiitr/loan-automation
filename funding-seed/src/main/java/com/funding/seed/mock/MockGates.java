package com.funding.seed.mock;

import com.atlassian.oai.validator.OpenApiInteractionValidator;
import com.atlassian.oai.validator.model.SimpleRequest;
import com.atlassian.oai.validator.model.SimpleResponse;
import com.atlassian.oai.validator.report.ValidationReport;
import com.funding.domain.Check;
import com.funding.domain.ExternalApi;
import com.funding.domain.Outcome;
import com.funding.domain.RuleEngine;
import com.funding.domain.RulesConfig;
import com.funding.domain.Snapshot;
import com.funding.domain.income.IncomeVerification;
import java.math.BigDecimal;
import com.funding.seed.GateResult;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** P-5 gates over generated stubs: contract (P-5.1), catalog coverage (P-5.6) and lock (P-5.5). */
public final class MockGates {

    /** Fault classes whose stubs may deliberately break the contract (P-5.1). */
    static final Set<String> FAULT_CLASSES = Set.of(
            "MALFORMED", "SCHEMA_INVALID", "EXHAUSTED", "KEY_CONFLICT", "DEALER_INVALID", "RETRY_THEN_OK",
            "TIMEOUT_BUT_PROCESSED", "IDEMPOTENT_REPLAY", "TIMEOUT_THEN_OK", "TIMEOUT_ALWAYS", "RATE_LIMITED",
            "CONNECTION_RESET", "CLIENT_ERROR");

    private MockGates() {}

    public static GateResult contract(List<MockStub> stubs, Map<ExternalApi, MockCatalog> catalogs, Path contractsDir) {
        List<String> failures = new ArrayList<>();
        Map<ExternalApi, OpenApiInteractionValidator> validators = new HashMap<>();
        for (MockStub s : stubs) {
            MockCatalog c = catalogs.get(s.api());
            if (c == null) {
                failures.add(s.id() + ": no catalog/contract for " + s.api());
                continue;
            }
            OpenApiInteractionValidator v = validators.computeIfAbsent(s.api(), a ->
                    OpenApiInteractionValidator.createForSpecificationUrl(contractsDir.resolve(c.contract()).toUri().toString()).build());
            if (s.violatesContract() && !FAULT_CLASSES.contains(s.band())) {
                failures.add(s.id() + ": violatesContract is only allowed on fault classes, not " + s.band());
            }
            SimpleRequest.Builder rb = new SimpleRequest.Builder(s.method(), s.path())
                    .withContentType("application/json")
                    .withBody(s.exampleRequest().toString());
            s.exampleHeaders().forEach(rb::withHeader);
            SimpleRequest req = rb.build();
            ValidationReport requestReport = v.validateRequest(req);
            if (requestReport.hasErrors()) {
                failures.add(s.id() + ": request violates " + c.contract() + ": " + messages(requestReport));
            }
            boolean anyViolation = false;
            for (int i = 0; i < s.interactions().size(); i++) {
                Interaction it = s.interactions().get(i);
                if (it.fault() != null) {
                    continue; // a dropped connection has no response to validate
                }
                SimpleResponse.Builder rbld = SimpleResponse.Builder.status(it.status())
                        .withContentType(it.contentType()).withBody(it.body());
                it.headers().forEach(rbld::withHeader);
                ValidationReport r = v.validateResponse(s.path(), com.atlassian.oai.validator.model.Request.Method.valueOf(s.method()), rbld.build());
                if (r.hasErrors()) {
                    anyViolation = true;
                    if (!s.violatesContract()) {
                        failures.add(s.id() + " response #" + (i + 1) + " violates " + c.contract() + ": " + messages(r));
                    }
                }
            }
            if (s.violatesContract() && !anyViolation) {
                failures.add(s.id() + ": marked violatesContract but every response validates");
            }
        }
        return new GateResult("mock-contract", failures);
    }

    /**
     * P-5.6: every class/band and fault class has at least {@code minPerBand} catalog entries and
     * covers every magnitude; every listed response shape appears; stub ids are unique.
     */
    public static GateResult coverage(List<MockStub> stubs, Map<ExternalApi, MockCatalog> catalogs, MockBudget budget) {
        List<String> failures = new ArrayList<>();
        for (MockCatalog c : catalogs.values()) {
            List<MockStub> mine = stubs.stream().filter(s -> s.api() == c.api() && "catalog".equals(s.source())).toList();
            for (Map.Entry<String, MockCatalog.ClassSpec> e : c.classes().entrySet()) {
                String cls = e.getKey();
                if (e.getValue().raw().has("shapes")) {
                    for (String shape : e.getValue().list("shapes")) {
                        if (mine.stream().noneMatch(s -> shape.equals(s.attributes().get("shape")))) {
                            failures.add(c.api() + " shape " + shape + ": no stub");
                        }
                    }
                    continue;
                }
                List<MockStub> inClass = mine.stream().filter(s -> s.band().equals(cls)).toList();
                if (inClass.size() < budget.minPerBand()) {
                    failures.add(c.api() + " " + cls + ": " + inClass.size() + " stubs, needs >= " + budget.minPerBand());
                }
                Set<String> mags = inClass.stream().map(s -> s.attributes().get("magnitude")).collect(Collectors.toSet());
                c.magnitudes().stream().filter(a -> !mags.contains(a))
                        .forEach(a -> failures.add(c.api() + " " + cls + ": no stub for magnitude " + a));
            }
        }
        Set<String> ids = new java.util.HashSet<>();
        stubs.stream().map(MockStub::id).filter(id -> !ids.add(id))
                .forEach(id -> failures.add("duplicate stub id " + id));
        return new GateResult("mock-coverage", failures);
    }

    /**
     * Band-oracle conformance (P-5 "Breadth"): replay each stub through the app's own response
     * classifier under the retry budget, run the real rule on the result, and compare with the
     * stub's independent expected outcome.
     */
    public static GateResult bandOracle(List<MockStub> stubs, RulesConfig config, MockBudget budget) {
        List<String> failures = new ArrayList<>();
        RuleEngine engine = new RuleEngine();
        for (MockStub s : stubs) {
            String actual = switch (s.api()) {
                case INCOME_VERIFICATION -> incomeOutcome(s, config, budget, engine);
                case DISBURSEMENT -> ClientSimulator.disbursement(s.interactions(), budget);
            };
            if (!actual.equals(s.expected())) {
                failures.add(s.id() + " [" + s.band() + "]: band expects " + s.expected() + " but the client + rule give " + actual
                        + describe(s));
            }
        }
        return new GateResult("mock-band-oracle", failures);
    }

    public static String incomeOutcome(MockStub s, RulesConfig config, MockBudget budget, RuleEngine engine) {
        IncomeVerification v = ClientSimulator.income(s.interactions(), budget);
        if (v == null) {
            return Outcome.ERROR.name();
        }
        Snapshot snap = new Snapshot(1, "ORACLE", 1, "4T1C11AK7RU100101", new BigDecimal(s.attributes().get("statedIncome")),
                true, "DLR-100", Map.of(), List.of(), v);
        return engine.evaluate(Check.INCOME, snap, config).outcome().name();
    }

    private static String describe(MockStub s) {
        return s.attributes().containsKey("verified")
                ? " (stated " + s.attributes().get("statedIncome") + ", verified " + s.attributes().get("verified") + ")" : "";
    }

    private static String messages(ValidationReport r) {
        return r.getMessages().stream().map(ValidationReport.Message::getMessage).collect(Collectors.joining("; "));
    }
}
