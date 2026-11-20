package com.funding.workflows;

import com.funding.domain.Check;
import com.funding.domain.CheckOutcome;
import com.funding.domain.DocumentSnapshot;
import com.funding.domain.DocumentType;
import com.funding.domain.FieldValue;
import com.funding.domain.GapForm;
import java.time.LocalDate;
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
class FakeActivities implements FundingActivities, DisbursementActivities, IncomeActivities {

    final List<String> calls = Collections.synchronizedList(new ArrayList<>());
    final Map<Check, List<CheckOutcome>> recorded = Collections.synchronizedMap(new LinkedHashMap<>());
    final AtomicInteger disburseCalls = new AtomicInteger();
    volatile String status = "PENDING_REVIEW";
    volatile boolean latest = true;
    volatile boolean failRecordOutcome = false;
    final java.util.Set<Check> failRecordOutcomeFor = java.util.concurrent.ConcurrentHashMap.newKeySet();
    volatile java.util.Set<Check> enabled = EnumSet.of(Check.TILA, Check.PRICE, Check.VIN, Check.GAP, Check.INCOME);
    volatile com.funding.domain.income.IncomeVerification income =
            com.funding.domain.income.IncomeVerification.verified(new BigDecimal("100000.00"));
    volatile boolean incomeDown = false;
    volatile boolean pendingManualStip = false;
    /** PENDING stips by owner: a check name, or "MANUAL" for a funder's stip (AS-10.8). */
    final java.util.Set<String> pendingStips = java.util.concurrent.ConcurrentHashMap.newKeySet();
    volatile long submittedAt = 0L;
    volatile java.time.Duration expireAfter = java.time.Duration.ofDays(30);
    volatile boolean disburseDown = false;
    volatile java.time.Duration disburseTakes = java.time.Duration.ZERO;
    final AtomicInteger expireCalls = new AtomicInteger();
    /** Workflow (server) time at which expire was scheduled; 0 until then. */
    volatile long expireScheduledAt = 0L;
    volatile Map<String, String> risc = baselineRisc();
    /** When set, verifyIncome blocks (real time) until it is counted down: holds the INCOME child mid-flight. */
    volatile java.util.concurrent.CountDownLatch incomeGate;

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
        String vin = "4T1C11AK7RU100101";
        Map<String, String> r = new LinkedHashMap<>(risc);
        r.putAll(Map.of("vin", vin, "vehicle_year", "2024", "vehicle_make", "Toyota", "vehicle_model", "Camry",
                "creditor_name", "Summit Auto Finance", "contract_date", "2026-11-02"));
        Snapshot s = new Snapshot(id, "LA-1", 1, vin, new BigDecimal("96000.00"), true, "DLR-100", Map.of(
                DocumentType.RISC, doc(DocumentType.RISC, 7L, r),
                DocumentType.BUYERS_ORDER, doc(DocumentType.BUYERS_ORDER, 8L, Map.of("total_vehicle_cash_price", "31450.00",
                        "vin", vin, "vehicle_year", "2024", "vehicle_make", "Toyota", "vehicle_model", "Camry")),
                DocumentType.GAP_AGREEMENT, doc(DocumentType.GAP_AGREEMENT, 9L, Map.of("form_number", "GAP-TX-2024-01", "state", "TX",
                        "creditor_name", "Summit Auto Finance", "vin", vin, "vehicle_year", "2024", "vehicle_make", "Toyota",
                        "vehicle_model", "Camry"))),
                List.of(new GapForm("TX", "GAP-TX-2024-01", LocalDate.of(2024, 1, 1), null)));
        return new VersionView(s, RulesConfig.defaults(), enabled, status, latest, submittedAt,
                submittedAt == 0L ? 0L : submittedAt + expireAfter.toMillis());
    }

    private static DocumentSnapshot doc(DocumentType t, long id, Map<String, String> fields) {
        Map<String, FieldValue> f = new LinkedHashMap<>();
        fields.forEach((k, v) -> f.put(k, new FieldValue(v, new BigDecimal("0.97"))));
        return new DocumentSnapshot(t, id, f);
    }

    @Override
    public boolean recordOutcome(long id, CheckOutcome outcome, int attempt) {
        if (failRecordOutcome || failRecordOutcomeFor.contains(outcome.check())) {
            throw ApplicationFailure.newFailure("db down", "DbDown");
        }
        calls.add("recordOutcome:" + outcome.check() + ":" + outcome.outcome());
        if (outcome.outcome() == Outcome.FAIL || outcome.outcome() == Outcome.NEEDS_REVIEW) {
            pendingStips.add(outcome.check().name()); // FR-07: one PENDING stip per check, however often it fails
        }
        recorded.computeIfAbsent(outcome.check(), c -> Collections.synchronizedList(new ArrayList<>())).add(outcome);
        return latest;
    }

    @Override
    public String applyOutcome(long id, Map<Check, CheckOutcome> outcomes) {
        calls.add("applyOutcome");
        if (!latest) {
            return "SUPERSEDED";
        }
        if (java.util.Set.of("FUNDED", "REJECTED", "EXPIRED").contains(status)) {
            return status;
        }
        // AS-8.8: a check passing now auto-satisfies its own earlier stip.
        outcomes.values().stream().filter(o -> o.outcome() == Outcome.PASS).forEach(o -> {
            if (pendingStips.remove(o.check().name())) {
                calls.add("autoSatisfied:" + o.check());
            }
        });
        boolean error = outcomes.values().stream().anyMatch(o -> o.outcome() == Outcome.ERROR);
        boolean stip = !pendingStips.isEmpty();
        if ("APPROVED".equals(status)) {
            status = stip ? "STIPULATED" : "APPROVED";
        } else {
            status = error ? "PENDING_REVIEW" : stip ? "STIPULATED" : "APPROVED";
        }
        return status;
    }

    @Override
    public String markFunded(long id) {
        calls.add("markFunded");
        status = "FUNDED";
        return status;
    }

    @Override
    public String expire(long id) {
        calls.add("expire");
        expireCalls.incrementAndGet();
        expireScheduledAt = io.temporal.activity.Activity.getExecutionContext().getInfo().getScheduledTimestamp();
        if (!latest) {
            return "SUPERSEDED";
        }
        if (!java.util.Set.of("FUNDED", "REJECTED", "EXPIRED").contains(status)) {
            status = "EXPIRED";
        }
        return status;
    }

    @Override
    public com.funding.domain.income.IncomeVerification verifyIncome(long id) {
        calls.add("verifyIncome");
        java.util.concurrent.CountDownLatch gate = incomeGate;
        if (gate != null) {
            try {
                gate.await(20, java.util.concurrent.TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        if (incomeDown) {
            throw ApplicationFailure.newFailure("income API: server error (503)", "IncomeUnavailable");
        }
        return income;
    }

    @Override
    public String disburse(long id) {
        calls.add("disburse");
        disburseCalls.incrementAndGet();
        if (!disburseTakes.isZero()) {
            try {
                Thread.sleep(disburseTakes.toMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        if (disburseDown) {
            throw ApplicationFailure.newFailure("disbursement API: server error (503)", "DisbursementUnavailable");
        }
        if (!"APPROVED".equals(status) || pendingManualStip || !pendingStips.isEmpty()) {
            return null;
        }
        return "DSB-1";
    }
}
