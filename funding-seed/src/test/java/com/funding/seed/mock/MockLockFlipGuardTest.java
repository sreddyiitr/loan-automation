package com.funding.seed.mock;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** T310: P-5.5, an expected-outcome flip needs ACCEPT_OUTCOME_CHANGES=<RULE_ID> and a dated RULE-CHANGES.md entry. */
class MockLockFlipGuardTest {

    private static MocksLock.Entry e(String id, String expect, String rules) {
        return new MocksLock.Entry(id, "BAND", "key", "hash", expect, rules);
    }

    private static final String ID = "INCOME_VERIFICATION/catalog/BOUNDARY_PASS/t/96000.00";
    private static final Map<String, MocksLock.Entry> WAS = Map.of(ID, e(ID, "PASS", "INCOME_VARIANCE@aaa,INCOME_API@bbb"));
    private static final Map<String, MocksLock.Entry> NOW = Map.of(ID, e(ID, "FAIL", "INCOME_VARIANCE@ccc,INCOME_API@bbb"));
    private static final String DOC = """
            # Rule changes
            ## INCOME_VARIANCE
            Date: 2026-11-17
            Author: owner
            What changed: threshold comparison became strict
            """;

    @Test
    void anOutcomeFlipIsRefusedWithoutAcceptance() {
        List<MockLockGate.Flip> flips = MockLockGate.flips(WAS, NOW);
        assertThat(flips).singleElement().satisfies(f -> {
            assertThat(f.before()).isEqualTo("PASS");
            assertThat(f.after()).isEqualTo("FAIL");
        });
        assertThat(MockLockGate.refusals(flips, Set.of(), DOC)).singleElement().asString()
                .contains("PASS -> FAIL").contains("ACCEPT_OUTCOME_CHANGES=INCOME_VARIANCE|INCOME_API");
    }

    @Test
    void acceptanceWithoutADatedEntryIsRefused() {
        assertThat(MockLockGate.refusals(MockLockGate.flips(WAS, NOW), Set.of("INCOME_VARIANCE"), "## INCOME_VARIANCE\nno date here\n"))
                .singleElement().asString().contains("has no dated '## INCOME_VARIANCE' entry");
        assertThat(MockLockGate.refusals(MockLockGate.flips(WAS, NOW), Set.of("INCOME_VARIANCE"), null)).hasSize(1);
    }

    @Test
    void acceptanceForAnUnrelatedRuleIsRefused() {
        assertThat(MockLockGate.refusals(MockLockGate.flips(WAS, NOW), Set.of("TILA_SUM"), DOC)).hasSize(1);
    }

    @Test
    void acceptedAndDocumentedFlipPasses() {
        assertThat(MockLockGate.refusals(MockLockGate.flips(WAS, NOW), Set.of("INCOME_VARIANCE"), DOC)).isEmpty();
    }

    @Test
    void stubsNoRuleConsumesAreOwnedByTheirApi() {
        String d = "DISBURSEMENT/catalog/SUCCESS/28450.00";
        var flips = MockLockGate.flips(Map.of(d, e(d, "FUNDED", "none")), Map.of(d, e(d, "APPROVED", "none")));
        assertThat(MockLockGate.refusals(flips, Set.of(), DOC)).singleElement().asString().contains("ACCEPT_OUTCOME_CHANGES=DISBURSEMENT");
        assertThat(MockLockGate.refusals(flips, Set.of("DISBURSEMENT"), "## DISBURSEMENT\nDate: 2026-11-17\n")).isEmpty();
    }

    @Test
    void unchangedOutcomesAreNotFlips() {
        assertThat(MockLockGate.flips(WAS, WAS)).isEmpty();
    }
}
