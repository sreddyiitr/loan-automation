package com.funding.domain.rules;

import static com.funding.domain.DocumentType.RISC;

import com.funding.domain.RulesConfig;
import com.funding.domain.Snapshot;
import java.util.List;

/**
 * AS-8.10 (owner decision B5): a version may become APPROVED only if its latest RISC exists and has
 * every required RISC field (spec §8) present, parseable and not flagged. Returns the problem
 * fields ({@code RISC.key}); empty means complete. The caller also requires TILA = PASS.
 */
public final class RiscCompleteness {
    private RiscCompleteness() {}

    public static List<String> problems(Snapshot s, RulesConfig config) {
        if (s.document(RISC).isEmpty()) {
            return List.of("RISC");
        }
        FieldReader r = new FieldReader(s, config);
        for (String k : List.of("cash_price", "amount_financed", "finance_charge", "total_of_payments", "monthly_payment")) {
            r.money(RISC, k);
        }
        r.percent(RISC, "apr");
        r.positiveInt(RISC, "term_months");
        r.exact(RISC, "vin");
        r.year(RISC, "vehicle_year");
        r.text(RISC, "vehicle_make");
        r.text(RISC, "vehicle_model");
        r.text(RISC, "creditor_name");
        r.date(RISC, "contract_date");
        r.optionalMoney(RISC, "final_payment");
        return r.problems();
    }
}
