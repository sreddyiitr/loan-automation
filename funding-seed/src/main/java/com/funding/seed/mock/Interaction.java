package com.funding.seed.mock;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.Map;

/**
 * One response in a stub's call sequence; the last one repeats for every further call.
 * {@code fault} is a WireMock fault (for example {@code CONNECTION_RESET_BY_PEER}) instead of a response.
 */
@JsonInclude(JsonInclude.Include.NON_EMPTY)
public record Interaction(int status, String contentType, String body, long delayMillis, Map<String, String> headers, String fault) {

    public Interaction {
        headers = headers == null ? Map.of() : Map.copyOf(headers);
    }

    public Interaction(int status, String contentType, String body, long delayMillis) {
        this(status, contentType, body, delayMillis, Map.of(), null);
    }

    public static Interaction fault(String fault) {
        return new Interaction(0, null, null, 0, Map.of(), fault);
    }
}
