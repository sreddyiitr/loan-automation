package com.funding.workflows;

import com.funding.domain.Check;
import com.funding.domain.RulesConfig;
import com.funding.domain.Snapshot;

/** Input to one check child. The snapshot carries no SSN/ITIN (FR-13). */
public record CheckInput(Check check, int attempt, Snapshot snapshot, RulesConfig config) {}
