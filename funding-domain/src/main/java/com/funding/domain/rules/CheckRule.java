package com.funding.domain.rules;

import com.funding.domain.Check;
import com.funding.domain.CheckOutcome;
import com.funding.domain.RulesConfig;
import com.funding.domain.Snapshot;

/** A pure, deterministic check (FR-05). No I/O. */
public interface CheckRule {
    Check check();

    CheckOutcome evaluate(Snapshot snapshot, RulesConfig config);
}
