package com.funding.seed.mock;

import com.atlassian.oai.validator.OpenApiInteractionValidator;
import com.atlassian.oai.validator.model.SimpleRequest;
import com.atlassian.oai.validator.model.SimpleResponse;
import com.atlassian.oai.validator.report.ValidationReport;
import com.funding.domain.ExternalApi;
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
                SimpleResponse resp = SimpleResponse.Builder.status(it.status())
                        .withContentType(it.contentType()).withBody(it.body()).build();
                ValidationReport r = v.validateResponse(s.path(), com.atlassian.oai.validator.model.Request.Method.valueOf(s.method()), resp);
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

    /** Every class/band covered, at least {@code minPerBand} entries each, and every amount present. */
    public static GateResult coverage(List<MockStub> stubs, Map<ExternalApi, MockCatalog> catalogs, MockBudget budget) {
        List<String> failures = new ArrayList<>();
        for (MockCatalog c : catalogs.values()) {
            List<MockStub> mine = stubs.stream().filter(s -> s.api() == c.api() && "catalog".equals(s.source())).toList();
            for (String cls : c.classes().keySet()) {
                List<MockStub> inClass = mine.stream().filter(s -> s.band().equals(cls)).toList();
                if (inClass.size() < budget.minPerBand()) {
                    failures.add(c.api() + " " + cls + ": " + inClass.size() + " stubs, needs >= " + budget.minPerBand());
                }
                Set<String> amounts = inClass.stream().map(s -> s.exampleRequest().path("amount").asText()).collect(Collectors.toSet());
                c.amounts().stream().filter(a -> !amounts.contains(a))
                        .forEach(a -> failures.add(c.api() + " " + cls + ": no stub for amount " + a));
            }
        }
        Set<String> ids = new java.util.HashSet<>();
        stubs.stream().map(MockStub::id).filter(id -> !ids.add(id))
                .forEach(id -> failures.add("duplicate stub id " + id));
        return new GateResult("mock-coverage", failures);
    }

    private static String messages(ValidationReport r) {
        return r.getMessages().stream().map(ValidationReport.Message::getMessage).collect(Collectors.joining("; "));
    }
}
