package com.funding.domain.routing;

import static com.funding.domain.routing.Permissions.NOT_ASSIGNED_TO_YOU;
import static com.funding.domain.routing.Permissions.NOT_YOUR_SPECIALTY;
import static com.funding.domain.routing.Permissions.ROLE_NOT_ALLOWED;
import static org.assertj.core.api.Assertions.assertThat;

import com.funding.domain.Check;
import com.funding.domain.Role;
import com.funding.domain.StipCodes;
import com.funding.domain.routing.Permissions.Action;
import com.funding.domain.routing.Permissions.Decision;
import com.funding.domain.routing.WorkRouting.Deal;
import com.funding.domain.routing.WorkRouting.PaymentState;
import com.funding.domain.routing.WorkRouting.PendingStip;
import com.funding.domain.status.VersionStatus;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

/** Spec 002 T202: every cell of AS-11.2, plus AS-11.5 and AS-11.6, on top of {@link WorkRouting}. */
class PermissionsTest {

    static final String ME = "me-1", OTHER = "other-1";

    static Deal stipulated(PendingStip... stips) {
        return new Deal(VersionStatus.STIPULATED, List.of(stips), List.of(), false, PaymentState.NOT_STARTED);
    }

    /** One deal per specialist: routed to exactly that role. */
    static final Map<Role, Deal> OWN_DEAL = Map.of(
            Role.GAP_SPECIALIST, stipulated(PendingStip.of(StipCodes.GAP_FORM_MISMATCH, Check.GAP)),
            Role.INCOME_SPECIALIST, stipulated(PendingStip.of(StipCodes.INCOME_VARIANCE_EXCEEDED, Check.INCOME)),
            Role.RISC_SPECIALIST, stipulated(PendingStip.of(StipCodes.TILA_MATH_DISCREPANCY, Check.TILA)),
            Role.DOCUMENTS_SPECIALIST, stipulated(PendingStip.of(StipCodes.PRICE_MISMATCH, Check.PRICE)),
            Role.PAYMENT_SPECIALIST, new Deal(VersionStatus.APPROVED, List.of(), List.of(), false, PaymentState.FAILED),
            Role.GENERAL_FUNDER, stipulated(PendingStip.of("STIP_DEALER_LETTER", null)));
    static final Deal FUNDED = new Deal(VersionStatus.FUNDED, List.of(), List.of(), false, PaymentState.PAID);
    static final Set<Role> SPECIALISTS = OWN_DEAL.keySet();
    static final Set<Action> WRITES = EnumSet.of(Action.RESOLVE_STIP, Action.ADD_STIP, Action.RERUN, Action.APPROVE,
            Action.REJECT, Action.RETRY_PAYMENT);

    static Decision check(Role role, Action action, Deal deal, String assignee) {
        return Permissions.check(role, ME, action, deal, assignee);
    }

    static Stream<Arguments> specialistsAndWrites() {
        return SPECIALISTS.stream().flatMap(r -> WRITES.stream().map(a -> Arguments.of(r, a)));
    }

    static Stream<Arguments> specialistsAndOthersDeals() {
        return SPECIALISTS.stream().flatMap(r -> SPECIALISTS.stream().filter(o -> o != r).map(o -> Arguments.of(r, OWN_DEAL.get(o))));
    }

    // ---------------------------------------------------------------- the supervisor (AS-11.2, right column)

    @ParameterizedTest
    @EnumSource(Action.class)
    void AS_11_2_theSupervisorMayDoEverythingOnAnyDeal(Action action) {
        for (Deal deal : Stream.concat(OWN_DEAL.values().stream(), Stream.of(FUNDED)).toList()) {
            for (String assignee : new String[] {null, ME, OTHER}) {
                assertThat(check(Role.SUPERVISOR, action, deal, assignee).allowed()).isTrue();
            }
        }
    }

    // ---------------------------------------------------------------- specialists (AS-11.2, left column)

    @ParameterizedTest
    @EnumSource(Role.class)
    void AS_11_1_everyoneMayReadAnyDeal(Role role) {
        assertThat(check(role, Action.VIEW, FUNDED, OTHER).allowed()).isTrue();
        assertThat(check(role, Action.VIEW, OWN_DEAL.get(Role.GAP_SPECIALIST), null).allowed()).isTrue();
    }

    @ParameterizedTest
    @EnumSource(value = Action.class, names = {"REASSIGN", "SUGGEST_ASSIGNMENTS"})
    void AS_11_2_reassigningAndSuggestionsAreTheSupervisorsAlone(Action action) {
        for (Role role : SPECIALISTS) {
            assertThat(check(role, action, OWN_DEAL.get(role), ME)).isEqualTo(new Decision(false, ROLE_NOT_ALLOWED));
        }
    }

    @Test
    void AS_11_2_aSpecialistMayTakeAnUnassignedDealRoutedToThem() {
        for (Role role : SPECIALISTS) {
            assertThat(check(role, Action.TAKE, OWN_DEAL.get(role), null).allowed()).as(role.name()).isTrue();
        }
    }

    @ParameterizedTest
    @MethodSource("specialistsAndWrites")
    void AS_11_2_aSpecialistMayWriteToTheirOwnDealOnlyWhenAssignedToThem(Role role, Action action) {
        Deal own = OWN_DEAL.get(role);
        assertThat(check(role, action, own, ME).allowed()).isTrue();
        assertThat(check(role, action, own, null)).isEqualTo(new Decision(false, NOT_ASSIGNED_TO_YOU));
        assertThat(check(role, action, own, OTHER)).isEqualTo(new Decision(false, NOT_ASSIGNED_TO_YOU));
    }

    @ParameterizedTest
    @MethodSource("specialistsAndOthersDeals")
    void AS_11_6_aSpecialistCantTakeOrWorkADealRoutedToSomeoneElse(Role role, Deal othersDeal) {
        assertThat(check(role, Action.TAKE, othersDeal, null)).isEqualTo(new Decision(false, NOT_YOUR_SPECIALTY));
        for (Action write : WRITES) {
            // even if it's assigned to them: the routing decides (AS-11.5)
            assertThat(check(role, write, othersDeal, ME)).isEqualTo(new Decision(false, NOT_YOUR_SPECIALTY));
        }
    }

    @Test
    void AS_11_2_aDealNobodyNeedsToWorkIsTheSupervisorsOnly() {
        for (Role role : SPECIALISTS) {
            assertThat(check(role, Action.TAKE, FUNDED, null)).isEqualTo(new Decision(false, NOT_YOUR_SPECIALTY));
            assertThat(check(role, Action.REJECT, FUNDED, ME)).isEqualTo(new Decision(false, NOT_YOUR_SPECIALTY));
        }
    }

    @ParameterizedTest
    @EnumSource(value = Action.class, names = "VIEW", mode = EnumSource.Mode.EXCLUDE)
    void theOldGeneralistRoleMayNoLongerWrite(Action action) {
        @SuppressWarnings("deprecation")
        Role funder = Role.FUNDER;
        assertThat(check(funder, action, OWN_DEAL.get(Role.GAP_SPECIALIST), ME)).isEqualTo(new Decision(false, ROLE_NOT_ALLOWED));
    }

    // ---------------------------------------------------------------- the spec's examples

    @Test
    void AS_11_6_onADealWithTwoKindsOnlyTheIncomeSpecialistMayAct() {
        Deal gapAndPrice = stipulated(PendingStip.of(StipCodes.GAP_FORM_MISMATCH, Check.GAP),
                PendingStip.of(StipCodes.PRICE_MISMATCH, Check.PRICE));
        assertThat(check(Role.GAP_SPECIALIST, Action.RESOLVE_STIP, gapAndPrice, ME)).isEqualTo(new Decision(false, NOT_YOUR_SPECIALTY));
        assertThat(check(Role.DOCUMENTS_SPECIALIST, Action.RESOLVE_STIP, gapAndPrice, ME)).isEqualTo(new Decision(false, NOT_YOUR_SPECIALTY));
        assertThat(check(Role.INCOME_SPECIALIST, Action.RESOLVE_STIP, gapAndPrice, ME).allowed()).isTrue();
        assertThat(check(Role.INCOME_SPECIALIST, Action.TAKE, gapAndPrice, null).allowed()).isTrue();
    }

    @Test
    void AS_11_5_whenTheRoutingChangesTheAssigneeKeepsTheDealButCantActAndItNeedsReassignment() {
        // the income specialist works a deal with a GAP stip and an income stip ...
        Deal before = stipulated(PendingStip.of(StipCodes.GAP_FORM_MISMATCH, Check.GAP),
                PendingStip.of(StipCodes.INCOME_VARIANCE_EXCEEDED, Check.INCOME));
        assertThat(check(Role.INCOME_SPECIALIST, Action.RESOLVE_STIP, before, ME).allowed()).isTrue();
        assertThat(Permissions.needsReassignment(before, Role.INCOME_SPECIALIST)).isFalse();
        // ... and clears the income stip: the deal is now the GAP specialist's
        Deal after = stipulated(PendingStip.of(StipCodes.GAP_FORM_MISMATCH, Check.GAP));
        assertThat(check(Role.INCOME_SPECIALIST, Action.RESOLVE_STIP, after, ME)).isEqualTo(new Decision(false, NOT_YOUR_SPECIALTY));
        assertThat(check(Role.INCOME_SPECIALIST, Action.VIEW, after, ME).allowed()).isTrue();          // still readable
        assertThat(Permissions.needsReassignment(after, Role.INCOME_SPECIALIST)).isTrue();
        assertThat(check(Role.SUPERVISOR, Action.REASSIGN, after, ME).allowed()).isTrue();
    }

    @Test
    void needsReassignmentOnlyWhenTheAssigneeIsNotWhoItsRoutedTo() {
        Deal gap = OWN_DEAL.get(Role.GAP_SPECIALIST);
        assertThat(Permissions.needsReassignment(gap, Role.GAP_SPECIALIST)).isFalse();
        assertThat(Permissions.needsReassignment(gap, Role.RISC_SPECIALIST)).isTrue();
        assertThat(Permissions.needsReassignment(gap, Role.SUPERVISOR)).isFalse();      // a supervisor can work anything
        assertThat(Permissions.needsReassignment(gap, null)).isFalse();                 // nobody has it: it's unassigned, not misassigned
        assertThat(Permissions.needsReassignment(FUNDED, Role.GAP_SPECIALIST)).isFalse(); // nothing left to do
    }

    @Test
    void allGivesEveryActionsDecisionForTheOverview() {
        Map<Action, Decision> all = Permissions.all(Role.GAP_SPECIALIST, ME, OWN_DEAL.get(Role.GAP_SPECIALIST), null);
        assertThat(all).containsOnlyKeys(Action.values());
        assertThat(all.get(Action.TAKE).allowed()).isTrue();
        assertThat(all.get(Action.RESOLVE_STIP)).isEqualTo(new Decision(false, NOT_ASSIGNED_TO_YOU));
        assertThat(all.get(Action.REASSIGN)).isEqualTo(new Decision(false, ROLE_NOT_ALLOWED));
    }
}
