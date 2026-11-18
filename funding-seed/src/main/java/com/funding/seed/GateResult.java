package com.funding.seed;

import java.util.List;

public record GateResult(String gate, List<String> failures) {
    public GateResult {
        failures = List.copyOf(failures);
    }

    public boolean passed() {
        return failures.isEmpty();
    }
}
