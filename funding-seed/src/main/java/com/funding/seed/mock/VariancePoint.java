package com.funding.seed.mock;

import com.funding.domain.money.Money;
import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * A variance point relative to the threshold {@code t}: {@code 0}, {@code t}, {@code -t}, {@code t/4},
 * {@code 2t}, {@code t-0.0001}, {@code -(t+0.0001)}, {@code -0.5}. Resolved against live config, so
 * a changed threshold moves every point with it (P-5.5: no outcome flips).
 */
public final class VariancePoint {
    private VariancePoint() {}

    public static BigDecimal resolve(String expr, BigDecimal t) {
        String e = expr.replace(" ", "");
        if (e.startsWith("-(") && e.endsWith(")")) {
            return resolve(e.substring(2, e.length() - 1), t).negate();
        }
        if (e.startsWith("-") && !e.equals("-")) {
            return resolve(e.substring(1), t).negate();
        }
        int op = Math.max(e.indexOf('+', 1), e.indexOf('-', 1));
        if (op > 0) {
            BigDecimal left = resolve(e.substring(0, op), t);
            BigDecimal right = new BigDecimal(e.substring(op + 1));
            return e.charAt(op) == '+' ? left.add(right) : left.subtract(right);
        }
        if (e.startsWith("t/")) {
            return t.divide(new BigDecimal(e.substring(2)), Money.MC);
        }
        if (e.endsWith("t")) {
            String k = e.substring(0, e.length() - 1);
            return k.isEmpty() ? t : t.multiply(new BigDecimal(k));
        }
        return new BigDecimal(e);
    }

    /**
     * Verified income for a fixed stated income at variance {@code v} ({@code s = verified × (1 + v)}),
     * rounded to cents <b>toward the band's side</b>: inside the threshold for a PASS band, outside it
     * for a FAIL band, so cent rounding can never move an entry across the boundary.
     */
    public static BigDecimal verifiedFor(BigDecimal stated, BigDecimal v, boolean passSide) {
        if (v.signum() == 0) {
            return Money.round(stated);
        }
        BigDecimal exact = stated.divide(BigDecimal.ONE.add(v), Money.MC);
        // Moving verified toward stated shrinks |variance|; away from it grows it.
        boolean towardStated = passSide;
        boolean up = (exact.compareTo(stated) < 0) == towardStated;
        return exact.setScale(2, up ? RoundingMode.CEILING : RoundingMode.FLOOR);
    }
}
