package com.funding.domain.rules;

import static com.funding.domain.DocumentType.BUYERS_ORDER;
import static com.funding.domain.DocumentType.RISC;

import com.funding.domain.Check;
import com.funding.domain.CheckOutcome;
import com.funding.domain.Comparison;
import com.funding.domain.RulesConfig;
import com.funding.domain.Snapshot;
import com.funding.domain.StipCodes;
import com.funding.domain.StipRequest;
import com.funding.domain.money.Money;
import java.math.BigDecimal;
import java.util.List;

/** US-3, rule {@code PRICE_PARITY}: RISC {@code cash_price} = Buyer's Order {@code total_vehicle_cash_price} within {@code funding.price.tolerance}. */
public final class PriceParityRule implements CheckRule {

    static final String NAME = "RISC cash_price = BUYERS_ORDER total_vehicle_cash_price";

    @Override
    public Check check() {
        return Check.PRICE;
    }

    @Override
    public CheckOutcome evaluate(Snapshot s, RulesConfig config) {
        FieldReader r = new FieldReader(s, config);
        BigDecimal risc = r.money(RISC, "cash_price");
        BigDecimal bo = r.money(BUYERS_ORDER, "total_vehicle_cash_price");
        if (!r.ok()) {
            return RuleSupport.needsReview(Check.PRICE, r.problems());
        }
        boolean ok = risc.subtract(bo).abs().compareTo(config.priceTolerance()) <= 0;
        List<Comparison> c = List.of(new Comparison(NAME, Money.format(risc), Money.format(bo), ok));
        if (ok) {
            return RuleSupport.pass(Check.PRICE, c);
        }
        return RuleSupport.fail(Check.PRICE, c, List.of(new StipRequest(StipCodes.PRICE_MISMATCH,
                "Cash price mismatch: RISC " + Money.format(risc) + ", Buyer's Order " + Money.format(bo))));
    }
}
