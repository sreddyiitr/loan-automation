package com.funding.workflows;

import com.funding.domain.Check;
import com.funding.domain.Outcome;
import java.util.Map;

/** {@code getState()} query (plan step 6): outcomes, attempt per check, and what the run waits on. */
public record FundingState(String step, String status, Map<Check, Outcome> outcomes, Map<Check, Integer> attempts) {}
