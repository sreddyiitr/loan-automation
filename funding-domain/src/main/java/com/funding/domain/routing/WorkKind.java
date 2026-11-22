package com.funding.domain.routing;

import com.funding.domain.Role;

/** What a deal needs a human for (spec 002 §3, D-15, D-17); each kind has its own specialist. */
public enum WorkKind {
    GAP(Role.GAP_SPECIALIST),
    INCOME(Role.INCOME_SPECIALIST),
    RISC(Role.RISC_SPECIALIST),
    DOCUMENTS(Role.DOCUMENTS_SPECIALIST),
    PAYMENT(Role.PAYMENT_SPECIALIST),
    /** Anything no specialist owns: a manual stip that names no check, or a code the table doesn't map. */
    GENERAL(Role.GENERAL_FUNDER);

    private final Role specialist;

    WorkKind(Role specialist) {
        this.specialist = specialist;
    }

    public Role specialist() {
        return specialist;
    }
}
