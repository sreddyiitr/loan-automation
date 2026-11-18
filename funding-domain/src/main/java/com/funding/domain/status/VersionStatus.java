package com.funding.domain.status;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * Application version status machine (spec §6, FR-09). Every status write goes through
 * {@link #requireTransition}; any pair not listed in §6 is rejected. Staying in the same status is
 * not a transition (callers skip the write).
 */
public enum VersionStatus {
    DRAFT, PENDING_REVIEW, STIPULATED, APPROVED, FUNDED, REJECTED, EXPIRED;

    private static final Map<VersionStatus, Set<VersionStatus>> ALLOWED = Map.of(
            DRAFT, EnumSet.of(PENDING_REVIEW),
            PENDING_REVIEW, EnumSet.of(APPROVED, STIPULATED, REJECTED, EXPIRED),
            // STIPULATED -> PENDING_REVIEW: a re-run started (doc update or funder re-run).
            STIPULATED, EnumSet.of(APPROVED, PENDING_REVIEW, REJECTED, EXPIRED),
            // APPROVED -> STIPULATED: manual stip before disbursement (AS-10.8).
            APPROVED, EnumSet.of(STIPULATED, FUNDED, REJECTED, EXPIRED),
            FUNDED, EnumSet.noneOf(VersionStatus.class),
            REJECTED, EnumSet.noneOf(VersionStatus.class),
            EXPIRED, EnumSet.noneOf(VersionStatus.class));

    /** {@code FUNDED}, {@code REJECTED} and {@code EXPIRED} accept no further transition or funder write. */
    public boolean terminal() {
        return ALLOWED.get(this).isEmpty() && this != DRAFT;
    }

    public boolean canMoveTo(VersionStatus next) {
        return ALLOWED.get(this).contains(next);
    }

    public Set<VersionStatus> next() {
        return Set.copyOf(ALLOWED.get(this));
    }

    /** @throws IllegalTransitionException when §6 doesn't allow {@code from -> to} */
    public static void requireTransition(VersionStatus from, VersionStatus to) {
        if (!from.canMoveTo(to)) {
            throw new IllegalTransitionException(from, to);
        }
    }

    public static void requireTransition(String from, String to) {
        requireTransition(valueOf(from), valueOf(to));
    }

    /** A status change §6 doesn't allow: a bug in the caller, never retried. */
    public static final class IllegalTransitionException extends IllegalStateException {
        public IllegalTransitionException(VersionStatus from, VersionStatus to) {
            super("illegal status transition " + from + " -> " + to + " (FR-09); allowed from " + from + ": " + ALLOWED.get(from));
        }
    }
}
