package com.funding.seed.mock;

import com.fasterxml.jackson.databind.JsonNode;
import com.funding.domain.ExternalApi;
import java.util.List;
import java.util.Map;

/**
 * A generated stub (constitution P-5.2). {@code requestMatch} is what WireMock matches on;
 * {@code exampleRequest} is a concrete request it matches, used by the contract gate.
 *
 * @param source   {@code scenario} or {@code catalog}
 * @param band     band or fault class from the catalog / scenario intent
 * @param expected the band's independent expected outcome (the oracle, P-5 "Breadth")
 */
public record MockStub(
        String id,
        ExternalApi api,
        String source,
        String band,
        String requestKey,
        String method,
        String path,
        RequestMatch requestMatch,
        Map<String, String> exampleHeaders,
        JsonNode exampleRequest,
        List<Interaction> interactions,
        String expected,
        boolean violatesContract) {

    /** Header regex/equality plus JSONPath body predicates. */
    public record RequestMatch(Map<String, String> headerEquals, Map<String, String> headerMatches,
                               Map<String, String> bodyEquals) {}
}
