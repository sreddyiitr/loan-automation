package com.funding.domain;

import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Every stip code (spec 002 D-19). The checks raise the first eight; funders may also add a stip
 * with any code in {@link #catalog()}, including the manual-only codes no check raises, and with no
 * other code (spec 001 AS-10.8, 400 {@code UNKNOWN_STIP_CODE}).
 */
public final class StipCodes {
    public static final String TILA_MATH_DISCREPANCY = "STIP_TILA_MATH_DISCREPANCY";
    public static final String EXTRACTION_REVIEW_REQUIRED = "STIP_EXTRACTION_REVIEW_REQUIRED";
    public static final String PRICE_MISMATCH = "STIP_PRICE_MISMATCH";
    public static final String VIN_MISMATCH = "STIP_VIN_MISMATCH";
    public static final String GAP_FORM_MISMATCH = "STIP_GAP_FORM_MISMATCH";
    public static final String LENDER_MISMATCH = "STIP_LENDER_MISMATCH";
    public static final String INCOME_VARIANCE_EXCEEDED = "STIP_INCOME_VARIANCE_EXCEEDED";
    public static final String PROOF_OF_INCOME = "STIP_PROOF_OF_INCOME";

    /** Manual-only (D-19): no check raises them and no specialist owns them, so they're GENERAL work (D-17). */
    public static final String DEALER_LETTER = "STIP_DEALER_LETTER";
    public static final String OTHER = "STIP_OTHER";

    /** Whether a stip with this code can be waived or satisfied by hand ({@link com.funding.domain.status.StipWaivers}). */
    public enum Waivable {
        ALWAYS,
        NEVER,
        /** A field review can't be waived when it names a RISC field (AS-8.10). */
        UNLESS_RISC_FIELD
    }

    /** One code: plain words for it, the checks that raise it (none for manual-only codes) and whether it can be waived. */
    public record StipCode(String code, String description, Set<Check> raisedBy, Waivable waivable) {
        public StipCode {
            raisedBy = Set.copyOf(raisedBy);
        }

        public boolean manualOnly() {
            return raisedBy.isEmpty();
        }
    }

    private static final List<StipCode> CATALOG = List.of(
            new StipCode(TILA_MATH_DISCREPANCY, "TILA math doesn't add up", Set.of(Check.TILA), Waivable.NEVER),
            new StipCode(EXTRACTION_REVIEW_REQUIRED, "A field was read with low confidence and needs review",
                    Set.of(Check.values()), Waivable.UNLESS_RISC_FIELD),
            new StipCode(PRICE_MISMATCH, "Price differs between the RISC and the Buyer's Order", Set.of(Check.PRICE), Waivable.ALWAYS),
            new StipCode(VIN_MISMATCH, "VIN or vehicle details differ between documents", Set.of(Check.VIN), Waivable.ALWAYS),
            new StipCode(GAP_FORM_MISMATCH, "GAP form isn't valid for the state or date", Set.of(Check.GAP), Waivable.ALWAYS),
            new StipCode(LENDER_MISMATCH, "Lender name doesn't match", Set.of(Check.GAP), Waivable.ALWAYS),
            new StipCode(INCOME_VARIANCE_EXCEEDED, "Stated income is too far from verified income", Set.of(Check.INCOME), Waivable.ALWAYS),
            new StipCode(PROOF_OF_INCOME, "Proof of income needed", Set.of(Check.INCOME), Waivable.ALWAYS),
            new StipCode(DEALER_LETTER, "A letter from the dealer is needed", Set.of(), Waivable.ALWAYS),
            new StipCode(OTHER, "Something else; the description says what", Set.of(), Waivable.ALWAYS));

    private StipCodes() {}

    /** Every code, in this order: the checks' codes, then the manual-only ones. */
    public static List<StipCode> catalog() {
        return CATALOG;
    }

    public static Optional<StipCode> find(String code) {
        return CATALOG.stream().filter(c -> c.code().equals(code)).findFirst();
    }
}
