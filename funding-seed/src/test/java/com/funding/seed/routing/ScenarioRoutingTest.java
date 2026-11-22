package com.funding.seed.routing;

import com.funding.domain.Check;
import com.funding.domain.CheckOutcome;
import com.funding.domain.Snapshot;
import com.funding.domain.routing.WorkRouting;
import com.funding.domain.routing.WorkRouting.CurrentResult;
import com.funding.domain.routing.WorkRouting.Deal;
import com.funding.domain.routing.WorkRouting.PaymentState;
import com.funding.domain.routing.WorkRouting.PendingStip;
import com.funding.domain.rules.RiscCompleteness;
import com.funding.domain.status.VersionStatus;
import com.funding.seed.ExpectedStatus;
import com.funding.seed.Fixture;
import com.funding.seed.FixtureSnapshots;
import com.funding.seed.RuleFingerprints;
import com.funding.seed.SeedConfig;
import com.funding.seed.SeedGates;
import com.funding.seed.SeedPaths;
import static org.assertj.core.api.Assertions.assertThat;

import com.funding.domain.Role;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Spec 002 T201 (AS-11.7): every seeded scenario's routing, from its real check outcomes (the same
 * rule engine and generated fixtures as the seed gates), not from copied expectations. Its status is
 * the one {@link ExpectedStatus} derives; a field review carries its check's flagged fields.
 */
class ScenarioRoutingTest {
    static final SeedConfig CONFIG = SeedConfig.fromDefaultsAndSystem("local");

    /** Where each seeded scenario goes; "-" means no specialist (nothing needs a human). */
    static final Map<String, String> EXPECTED = Map.ofEntries(
            Map.entry("LA-1001", "-"), Map.entry("LA-1002", "RISC_SPECIALIST"), Map.entry("LA-1003", "RISC_SPECIALIST"),
            Map.entry("LA-1004", "-"), Map.entry("LA-1005", "-"), Map.entry("LA-1006", "DOCUMENTS_SPECIALIST"),
            Map.entry("LA-1007", "DOCUMENTS_SPECIALIST"), Map.entry("LA-1008", "DOCUMENTS_SPECIALIST"), Map.entry("LA-1009", "-"),
            Map.entry("LA-1010", "DOCUMENTS_SPECIALIST"), Map.entry("LA-1011", "DOCUMENTS_SPECIALIST"),
            Map.entry("LA-1012", "INCOME_SPECIALIST"), Map.entry("LA-1013", "-"), Map.entry("LA-1014", "INCOME_SPECIALIST"),
            Map.entry("LA-1015", "INCOME_SPECIALIST"),
            Map.entry("LA-1016", "INCOME_SPECIALIST"),              // the income check couldn't finish: no stip, still income work
            Map.entry("LA-1017", "-"), Map.entry("LA-1018", "GAP_SPECIALIST"), Map.entry("LA-1019", "GAP_SPECIALIST"),
            Map.entry("LA-1020", "GAP_SPECIALIST"),
            Map.entry("LA-1021", "GAP_SPECIALIST"),                 // two stips, one kind: stays with the GAP specialist
            Map.entry("LA-1022", "-"),
            Map.entry("LA-1023", "RISC_SPECIALIST"),                // raised by PRICE, but the flagged field is on the RISC
            Map.entry("LA-1024", "-"), Map.entry("LA-1025", "-"),
            Map.entry("LA-1026", "RISC_SPECIALIST"),
            Map.entry("LA-1027", "INCOME_SPECIALIST"),              // TILA + VIN + income: three kinds
            Map.entry("LA-1028", "-"), Map.entry("LA-1029", "-"),
            Map.entry("LA-1030", "PAYMENT_SPECIALIST"),             // approved, payment outcome unknown
            Map.entry("LA-1031", "-"), Map.entry("LA-1032", "DOCUMENTS_SPECIALIST"),
            Map.entry("LA-1033", "RISC_SPECIALIST"), Map.entry("LA-1034", "-"), Map.entry("LA-1035", "INCOME_SPECIALIST"));

    static Map<String, Deal> seededDeals() {
        SeedGates gates = new SeedGates(SeedPaths.discover(), CONFIG, RuleFingerprints.fromClasspath());
        Map<String, Deal> deals = new java.util.TreeMap<>();
        for (Fixture f : gates.generate().fixtures()) {
            Snapshot snap = FixtureSnapshots.of(f);
            Map<Check, CheckOutcome> outcomes = gates.outcomes(f, snap);
            List<String> riscProblems = RiscCompleteness.problems(snap, CONFIG.rules());
            String disbursement = f.externals().get("disbursement");
            VersionStatus status = VersionStatus.valueOf(ExpectedStatus.derive(outcomes.values(), disbursement, riscProblems));
            List<PendingStip> stips = outcomes.values().stream().flatMap(o -> o.stips().stream()
                    .map(s -> new PendingStip(s.code(), o.check(), o.flaggedFields()))).toList();
            List<CurrentResult> results = outcomes.values().stream().map(o -> new CurrentResult(o.check(), o.outcome())).toList();
            PaymentState payment = status == VersionStatus.FUNDED ? PaymentState.PAID
                    : status == VersionStatus.APPROVED && !"SUCCESS".equals(disbursement) ? PaymentState.UNKNOWN   // retries exhausted (C-8)
                    : PaymentState.NOT_STARTED;
            deals.put(f.intent().id(), new Deal(status, stips, results, !riscProblems.isEmpty(), payment));
        }
        return deals;
    }

    @Test
    void AS_11_7_everySeededScenarioIsRoutedToTheRightSpecialist() {
        Map<String, String> actual = new java.util.TreeMap<>();
        seededDeals().forEach((id, deal) -> actual.put(id, WorkRouting.route(deal).map(Enum::name).orElse("-")));
        assertThat(actual).isEqualTo(new java.util.TreeMap<>(EXPECTED));
    }

    @Test
    void AS_11_7_theSeedsCoverEverySpecialistAndTheTwoOrMoreKindsCase() {
        Map<String, Deal> deals = seededDeals();
        Set<Role> routed = deals.values().stream().map(WorkRouting::route).flatMap(java.util.Optional::stream)
                .collect(java.util.stream.Collectors.toCollection(() -> EnumSet.noneOf(Role.class)));
        assertThat(routed).contains(Role.GAP_SPECIALIST, Role.INCOME_SPECIALIST, Role.RISC_SPECIALIST,
                Role.DOCUMENTS_SPECIALIST, Role.PAYMENT_SPECIALIST);
        assertThat(deals.values()).anyMatch(d -> WorkRouting.kinds(d).size() >= 2);
        // GENERAL (a manual stip with no check) isn't seeded: WorkRoutingTest and T204's API tests cover it
        assertThat(routed).doesNotContain(Role.GENERAL_FUNDER);
    }
}
