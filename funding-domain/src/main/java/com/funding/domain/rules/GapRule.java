package com.funding.domain.rules;

import static com.funding.domain.DocumentType.GAP_AGREEMENT;
import static com.funding.domain.DocumentType.RISC;

import com.funding.domain.Check;
import com.funding.domain.CheckOutcome;
import com.funding.domain.Comparison;
import com.funding.domain.GapForm;
import com.funding.domain.RulesConfig;
import com.funding.domain.Snapshot;
import com.funding.domain.StipCodes;
import com.funding.domain.StipRequest;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * US-6 GAP form and lender. Rule ids {@code GAP_FORM} (AS-6.1: the form is in the registry for the
 * GAP {@code state} and effective on the RISC {@code contract_date}), {@code GAP_LENDER} (AS-6.2:
 * both creditor names equal the primary lender, case-insensitive and trimmed) and
 * {@code GAP_OPTIONAL} (AS-6.4: no GAP Agreement is PASS, {@code not_applicable}). The two
 * sub-checks are independent (AS-6.3).
 */
public final class GapRule implements CheckRule {

    public static final String NOT_APPLICABLE = "not_applicable";

    @Override
    public Check check() {
        return Check.GAP;
    }

    @Override
    public CheckOutcome evaluate(Snapshot s, RulesConfig config) {
        if (s.document(GAP_AGREEMENT).isEmpty()) {
            return RuleSupport.pass(Check.GAP, List.of(new Comparison("GAP Agreement", NOT_APPLICABLE, "absent", true)));
        }
        FieldReader r = new FieldReader(s, config);
        String form = r.text(GAP_AGREEMENT, "form_number");
        String state = r.text(GAP_AGREEMENT, "state");
        String gapCreditor = r.text(GAP_AGREEMENT, "creditor_name");
        String riscCreditor = r.text(RISC, "creditor_name");
        LocalDate contractDate = r.date(RISC, "contract_date");
        if (!r.ok()) {
            return RuleSupport.needsReview(Check.GAP, r.problems());
        }

        String st = state.toUpperCase(Locale.ROOT);
        boolean formOk = s.gapForms().stream().anyMatch(g ->
                g.stateCode().equalsIgnoreCase(st) && g.formNumber().equals(form) && g.effectiveOn(contractDate));
        String lender = config.primaryLenderName().trim();
        boolean gapLenderOk = gapCreditor.equalsIgnoreCase(lender);
        boolean riscLenderOk = riscCreditor.equalsIgnoreCase(lender);

        List<Comparison> c = List.of(
                new Comparison("GAP form_number registered for " + st + " on " + contractDate, "effective registry form", form, formOk),
                new Comparison("GAP creditor_name = primary lender", lender, gapCreditor, gapLenderOk),
                new Comparison("RISC creditor_name = primary lender", lender, riscCreditor, riscLenderOk));
        List<StipRequest> stips = new ArrayList<>();
        if (!formOk) {
            stips.add(new StipRequest(StipCodes.GAP_FORM_MISMATCH, "GAP form " + form + " is not registered for " + st
                    + " or not effective on " + contractDate + " (" + registered(s, st) + ")"));
        }
        if (!gapLenderOk || !riscLenderOk) {
            stips.add(new StipRequest(StipCodes.LENDER_MISMATCH, "Creditor must be " + lender + ": GAP " + gapCreditor
                    + ", RISC " + riscCreditor));
        }
        return stips.isEmpty() ? RuleSupport.pass(Check.GAP, c) : RuleSupport.fail(Check.GAP, c, stips);
    }

    private static String registered(Snapshot s, String state) {
        List<String> forms = s.gapForms().stream().filter(g -> g.stateCode().equalsIgnoreCase(state))
                .map(GapForm::formNumber).distinct().toList();
        return forms.isEmpty() ? "no forms registered for " + state : "registered: " + String.join(", ", forms);
    }
}
