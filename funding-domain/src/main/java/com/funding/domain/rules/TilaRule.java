package com.funding.domain.rules;

import static com.funding.domain.DocumentType.RISC;

import com.funding.domain.Check;
import com.funding.domain.CheckOutcome;
import com.funding.domain.Comparison;
import com.funding.domain.Outcome;
import com.funding.domain.RulesConfig;
import com.funding.domain.Snapshot;
import com.funding.domain.StipCodes;
import com.funding.domain.StipRequest;
import com.funding.domain.money.Amortization;
import com.funding.domain.money.Money;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * US-2 TILA math. Rule ids {@code TILA_SUM} (AS-2.1/2.2) and {@code TILA_PAYMENT} (AS-2.3, C-9).
 * Tolerances: {@code funding.tila.sum-tolerance} (C-2: exact) and {@code funding.tila.payment-tolerance}.
 */
public final class TilaRule implements CheckRule {

    static final String SUM = "amount_financed + finance_charge = total_of_payments";
    static final String PAYMENT = "amortized monthly_payment";
    static final String SCHEDULE = "payment schedule = total_of_payments";

    @Override
    public Check check() {
        return Check.TILA;
    }

    @Override
    public CheckOutcome evaluate(Snapshot s, RulesConfig config) {
        FieldReader r = new FieldReader(s);
        BigDecimal amountFinanced = r.money(RISC, "amount_financed");
        BigDecimal financeCharge = r.money(RISC, "finance_charge");
        BigDecimal totalOfPayments = r.money(RISC, "total_of_payments");
        BigDecimal apr = r.percent(RISC, "apr");
        Integer term = r.positiveInt(RISC, "term_months");
        BigDecimal monthlyPayment = r.money(RISC, "monthly_payment");
        Optional<BigDecimal> finalPayment = r.optionalMoney(RISC, "final_payment");

        if (!r.ok()) {
            return needsReview(r.problems());
        }

        BigDecimal sumTol = config.tilaSumTolerance();
        BigDecimal pmtTol = config.tilaPaymentTolerance();

        BigDecimal expectedTotal = Money.round(amountFinanced.add(financeCharge));
        BigDecimal expectedPayment = Amortization.monthlyPayment(amountFinanced, apr, term);
        BigDecimal scheduled = Amortization.scheduledTotal(monthlyPayment, term, finalPayment.orElse(null));

        List<Comparison> comparisons = List.of(
                compare(SUM, expectedTotal, totalOfPayments, sumTol),
                compare(PAYMENT, expectedPayment, monthlyPayment, pmtTol),
                compare(SCHEDULE, scheduled, totalOfPayments, sumTol));

        List<Comparison> failed = comparisons.stream().filter(c -> !c.ok()).toList();
        if (failed.isEmpty()) {
            return new CheckOutcome(Check.TILA, Outcome.PASS, comparisons, List.of(), List.of(), null);
        }
        String description = "TILA math discrepancy: " + failed.stream()
                .map(c -> c.name() + " (expected " + c.expected() + ", actual " + c.actual() + ")")
                .collect(Collectors.joining("; "));
        return new CheckOutcome(Check.TILA, Outcome.FAIL, comparisons, List.of(),
                List.of(new StipRequest(StipCodes.TILA_MATH_DISCREPANCY, description)), null);
    }

    private static Comparison compare(String name, BigDecimal expected, BigDecimal actual, BigDecimal tolerance) {
        boolean ok = expected.subtract(actual).abs().compareTo(tolerance) <= 0;
        return new Comparison(name, Money.format(expected), Money.format(actual), ok);
    }

    static CheckOutcome needsReview(List<String> fields) {
        String description = "Extraction review required: " + String.join(", ", fields);
        return new CheckOutcome(Check.TILA, Outcome.NEEDS_REVIEW, List.of(), fields,
                List.of(new StipRequest(StipCodes.EXTRACTION_REVIEW_REQUIRED, description)), null);
    }
}
