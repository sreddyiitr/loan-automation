package com.funding.domain.status;

import com.funding.domain.StipCodes;

/**
 * AS-8.10: a stip raised against RISC fields can't be WAIVED; only a corrected RISC version that
 * re-runs and passes resolves it (AS-8.8). That is {@code STIP_TILA_MATH_DISCREPANCY}, and
 * {@code STIP_EXTRACTION_REVIEW_REQUIRED} when it names a RISC field ({@code RISC.<key>}, the form
 * the rules write into the description).
 */
public final class StipWaivers {
    private StipWaivers() {}

    public static boolean waivable(String code, String description) {
        if (StipCodes.TILA_MATH_DISCREPANCY.equals(code)) {
            return false;
        }
        return !(StipCodes.EXTRACTION_REVIEW_REQUIRED.equals(code) && description != null && description.contains("RISC."));
    }
}
