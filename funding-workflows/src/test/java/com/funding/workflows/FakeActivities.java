package com.funding.workflows;

import com.funding.domain.Check;
import com.funding.domain.CheckOutcome;
import com.funding.domain.DocumentSnapshot;
import com.funding.domain.DocumentType;
import com.funding.domain.FieldValue;
import com.funding.domain.Outcome;
import com.funding.domain.RulesConfig;
import com.funding.domain.Snapshot;
import io.temporal.failure.ApplicationFailure;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/** In-memory activities for workflow tests: records every call, statuses follow the real rules. */
class FakeActivities implements FundingActivities, DisbursementActivities {

    final List<String> calls = Collections.synchronizedList(new ArrayList<>());
    final Map<Check, List<CheckOutcome>> recorded = Collections.synchronizedMap(new LinkedHashMap<>());
    final AtomicInteger disburseCalls = new AtomicInteger();
    volatile String status = "PENDING_REVIEW";
    volatile boolean latest = true;
    volatile boolean failRecordOutcome = false;
    volatile boolean pendingManualStip = false;
    volatile Map<String, String> risc = baselineRisc();

    static Map<String, String> baselineRisc() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("cash_price", "31450.00");
        m.put("amount_financed", "28450.00");
        m.put("finance_charge", "5342.60");
        m.put("total_of_payments", "33792.60");
        m.put("apr", "6.99");
        m.put("term_months", "60");
        m.put("monthly_payment", "563.21");
        return m;
    }

    @Override
    public VersionView loadVersionSnapshot(long id) {
        calls.add("loadVersionSnapshot");
        Map<String, FieldValue> f = new LinkedHashMap<>();
        risc.forEach((k, v) -> f.put(k, new FieldValue(v, new BigDecimal("0.97"))));
        Snapshot s = new Snapshot(id, "LA-1", 1, "4T1C11AK7RU100101", new BigDecimal("96000.00"), true, "DLR-100",
                Map.of(DocumentType.RISC, new DocumentSnapshot(DocumentType.RISC, 7L, f)));
        return new VersionView(s, RulesConfig.defaults(), EnumSet.of(Check.TILA), status, latest, 0L);
    }

    @Override
    public boolean recordOutcome(long id, CheckOutcome outcome, int attempt) {
        if (failRecordOutcome) {
            throw ApplicationFailure.newFailure("db down", "DbDown");
        }
        calls.add("recordOutcome:" + outcome.check() + ":" + outcome.outcome());
        recorded.computeIfAbsent(outcome.check(), c -> Collections.synchronizedList(new ArrayList<>())).add(outcome);
        return latest;
    }

    @Override
    public String applyOutcome(long id, Map<Check, CheckOutcome> outcomes) {
        calls.add("applyOutcome");
        if (!latest) {
            return "SUPERSEDED";
        }
        boolean error = outcomes.values().stream().anyMatch(o -> o.outcome() == Outcome.ERROR);
        boolean stip = outcomes.values().stream().anyMatch(o -> o.outcome() == Outcome.FAIL || o.outcome() == Outcome.NEEDS_REVIEW);
        status = error ? "PENDING_REVIEW" : stip ? "STIPULATED" : "APPROVED";
        return status;
    }

    @Override
    public String markFunded(long id) {
        calls.add("markFunded");
        status = "FUNDED";
        return status;
    }

    @Override
    public String disburse(long id) {
        calls.add("disburse");
        disburseCalls.incrementAndGet();
        if (!"APPROVED".equals(status) || pendingManualStip) {
            return null;
        }
        return "DSB-1";
    }
}
