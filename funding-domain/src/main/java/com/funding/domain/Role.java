package com.funding.domain;

/**
 * The dashboard user's role (spec 002 US-11, D-15, D-17). Five specialists, one per kind of work
 * ({@link com.funding.domain.routing.WorkKind}), a catch-all for work no specialist owns, and the
 * supervisor, who can do everything. What each may do is AS-11.2.
 *
 * <p>{@code FUNDER} is the pre-D2 generalist; it goes when the user directory moves to the specialists
 * (T203).
 */
public enum Role {
    GAP_SPECIALIST, INCOME_SPECIALIST, RISC_SPECIALIST, DOCUMENTS_SPECIALIST, PAYMENT_SPECIALIST,
    GENERAL_FUNDER, SUPERVISOR,
    /** @deprecated pre-D2 generalist, removed in T203. */
    @Deprecated FUNDER
}
