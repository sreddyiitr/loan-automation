package com.funding.domain.money;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.Optional;
import java.util.regex.Pattern;

/** Exact money arithmetic (FR-08): DECIMAL128 for intermediates, half-up to 2 dp for money. */
public final class Money {
    public static final MathContext MC = MathContext.DECIMAL128;
    private static final Pattern MONEY = Pattern.compile("^-?\\d+(\\.\\d{1,2})?$");

    private Money() {}

    public static BigDecimal round(BigDecimal v) {
        return v.setScale(2, RoundingMode.HALF_UP);
    }

    /** FR-14: money must match {@code ^-?\d+(\.\d{1,2})?$}; anything else is treated as missing. */
    public static Optional<BigDecimal> parse(String raw) {
        if (raw == null || !MONEY.matcher(raw).matches()) {
            return Optional.empty();
        }
        return Optional.of(round(new BigDecimal(raw)));
    }

    public static String format(BigDecimal v) {
        return round(v).toPlainString();
    }
}
