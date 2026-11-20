package com.funding.persistence;

/**
 * A new application version can't be created (C-24): the latest version is FUNDED, or its
 * disbursement was requested or completed. Each version pays under its own idempotency key (its
 * workflow ID), so a second version of a deal that may already be paid could pay the dealer twice.
 */
public class ResubmissionRefusedException extends IllegalStateException {

    private final String reason;

    public ResubmissionRefusedException(String reason, String message) {
        super(message);
        this.reason = reason;
    }

    /** {@code VERSION_FUNDED} or {@code DISBURSEMENT_STARTED}. */
    public String reason() {
        return reason;
    }
}
