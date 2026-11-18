package com.funding.workflows;

import com.funding.domain.Check;
import com.funding.domain.RulesConfig;
import com.funding.domain.Snapshot;

/**
 * Input to one check child. The snapshot carries no SSN/ITIN (FR-13). {@code externalTaskQueue}
 * is where external API activities run (null in histories recorded before slice S3).
 */
public record CheckInput(Check check, int attempt, Snapshot snapshot, RulesConfig config, String externalTaskQueue) {
    public CheckInput(Check check, int attempt, Snapshot snapshot, RulesConfig config) {
        this(check, attempt, snapshot, config, null);
    }
}
