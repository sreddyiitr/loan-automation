package com.funding.domain.rules;

import com.funding.domain.Check;
import com.funding.domain.CheckOutcome;
import com.funding.domain.Comparison;
import com.funding.domain.Outcome;
import com.funding.domain.RulesConfig;
import com.funding.domain.Snapshot;
import com.funding.domain.StipCodes;
import com.funding.domain.StipRequest;
import com.funding.domain.income.IncomeVariance;
import com.funding.domain.income.IncomeVerification;
import com.funding.domain.money.Money;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * US-5. Rule ids {@code INCOME_VARIANCE} (AS-5.1/5.2: {@code |I_s − I_v| / I_v ≤ t}, two-sided per
 * C-3, compared exactly per FR-15) and {@code INCOME_API} (the API's answer: C-12 NOT_FOUND is
 * NEEDS_REVIEW + {@code STIP_PROOF_OF_INCOME}; retries exhausted are ERROR, decided by the activity).
 * The verification comes from the income activity through {@link Snapshot#incomeVerification()}.
 */
public final class IncomeRule implements CheckRule {

    @Override
    public Check check() {
        return Check.INCOME;
    }

    @Override
    public CheckOutcome evaluate(Snapshot s, RulesConfig config) {
        // FR-14: a missing required application input is treated like a missing field.
        List<String> missing = new ArrayList<>();
        if (s.statedIncomeAnnual() == null || s.statedIncomeAnnual().signum() <= 0) {
            missing.add("APPLICATION.stated_income_annual");
        }
        if (!s.ssnTokenPresent()) {
            missing.add("APPLICATION.ssn_itin_encrypted");
        }
        if (!missing.isEmpty()) {
            return RuleSupport.needsReview(Check.INCOME, missing);
        }
        IncomeVerification v = s.incomeVerification();
        if (v == null) {
            return CheckOutcome.error(Check.INCOME, "no income verification result");
        }
        if (IncomeVerification.NOT_FOUND.equals(v.status())) {
            return new CheckOutcome(Check.INCOME, Outcome.NEEDS_REVIEW,
                    List.of(new Comparison("payroll record", "found", "NOT_FOUND", false)), List.of(),
                    List.of(new StipRequest(StipCodes.PROOF_OF_INCOME, "No payroll record found: proof of income required")), null);
        }
        BigDecimal stated = s.statedIncomeAnnual();
        BigDecimal verified = v.totalVerifiedAnnualIncome();
        BigDecimal variance = IncomeVariance.of(stated, verified);
        BigDecimal t = config.incomeVarianceThreshold();
        boolean ok = IncomeVariance.withinThreshold(variance, t);
        List<Comparison> c = List.of(
                new Comparison("stated vs verified income", Money.format(verified), Money.format(stated), ok),
                new Comparison("|variance| <= threshold", IncomeVariance.percent(t), IncomeVariance.percentAgainst(variance, t), ok));
        if (ok) {
            return RuleSupport.pass(Check.INCOME, c);
        }
        return RuleSupport.fail(Check.INCOME, c, List.of(new StipRequest(StipCodes.INCOME_VARIANCE_EXCEEDED,
                "Income variance " + IncomeVariance.percentAgainst(variance, t) + " exceeds " + IncomeVariance.percent(t)
                        + ": stated " + Money.format(stated) + ", verified " + Money.format(verified))));
    }
}
