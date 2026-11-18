package com.funding.workflows;

import com.funding.domain.Check;
import com.funding.domain.RulesConfig;
import com.funding.domain.Snapshot;
import java.util.Set;

/**
 * What {@code loadVersionSnapshot} returns: latest documents (no SSN), the rule config and enabled
 * checks to evaluate with (recorded in history, so replay is deterministic), and the version's state.
 * {@code expiresAtEpochMillis} is {@code submitted_at + funding.workflow.expire-after} (AS-8.6), 0
 * when the version was never submitted; it is 0 in histories recorded before slice S4.
 */
public record VersionView(Snapshot snapshot, RulesConfig config, Set<Check> enabledChecks, String status,
                          boolean latest, long submittedAtEpochMillis, long expiresAtEpochMillis) {}
