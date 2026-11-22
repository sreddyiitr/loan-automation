package com.funding.domain.routing;

import static com.funding.domain.routing.WorkKind.DOCUMENTS;
import static com.funding.domain.routing.WorkKind.GAP;
import static com.funding.domain.routing.WorkKind.GENERAL;
import static com.funding.domain.routing.WorkKind.INCOME;
import static com.funding.domain.routing.WorkKind.PAYMENT;
import static com.funding.domain.routing.WorkKind.RISC;
import static org.assertj.core.api.Assertions.assertThat;

import com.funding.domain.Check;
import com.funding.domain.Outcome;
import com.funding.domain.Role;
import com.funding.domain.StipCodes;
import com.funding.domain.routing.WorkRouting.CurrentResult;
import com.funding.domain.routing.WorkRouting.Deal;
import com.funding.domain.routing.WorkRouting.PaymentState;
import com.funding.domain.routing.WorkRouting.PendingStip;
import com.funding.domain.status.VersionStatus;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Spec 002 T201: the kinds-of-work table (spec §3) cell by cell, then routing (D-15, D-17). The seeded
 * scenarios' routing, through the real rules, is {@code ScenarioRoutingTest} in funding-seed.
 */
class WorkRoutingTest {

    static Deal stipulated(PendingStip... stips) {
        return new Deal(VersionStatus.STIPULATED, List.of(stips), List.of(), false, PaymentState.NOT_STARTED);
    }

    static Deal underReview(CurrentResult... results) {
        return new Deal(VersionStatus.PENDING_REVIEW, List.of(), List.of(results), false, PaymentState.NOT_STARTED);
    }

    static PendingStip review(Check check, String... fields) {
        return new PendingStip(StipCodes.EXTRACTION_REVIEW_REQUIRED, check, List.of(fields));
    }

    // ---------------------------------------------------------------- the table, row by row

    static Stream<Arguments> stipRows() {
        return Stream.of(
                // GAP: the GAP check's stips
                Arguments.of(PendingStip.of(StipCodes.GAP_FORM_MISMATCH, Check.GAP), GAP),
                Arguments.of(PendingStip.of(StipCodes.LENDER_MISMATCH, Check.GAP), GAP),
                // INCOME: the INCOME check's stips (proof of income may be added by hand)
                Arguments.of(PendingStip.of(StipCodes.INCOME_VARIANCE_EXCEEDED, Check.INCOME), INCOME),
                Arguments.of(PendingStip.of(StipCodes.PROOF_OF_INCOME, Check.INCOME), INCOME),
                Arguments.of(PendingStip.of(StipCodes.PROOF_OF_INCOME, null), INCOME),
                // RISC: the TILA check's stip
                Arguments.of(PendingStip.of(StipCodes.TILA_MATH_DISCREPANCY, Check.TILA), RISC),
                // DOCUMENTS: the PRICE and VIN checks' stips
                Arguments.of(PendingStip.of(StipCodes.PRICE_MISMATCH, Check.PRICE), DOCUMENTS),
                Arguments.of(PendingStip.of(StipCodes.VIN_MISMATCH, Check.VIN), DOCUMENTS),
                // a field review counts by the document the field is on, whichever check raised it
                Arguments.of(review(Check.PRICE, "RISC.cash_price"), RISC),
                Arguments.of(review(Check.PRICE, "BUYERS_ORDER.total_vehicle_cash_price"), DOCUMENTS),
                Arguments.of(review(Check.GAP, "GAP_AGREEMENT.form_number"), GAP),
                Arguments.of(review(Check.TILA, "RISC.amount_financed"), RISC),
                // ... and by its check when no field names a document
                Arguments.of(review(Check.VIN), DOCUMENTS),
                Arguments.of(review(Check.INCOME, "application.stated_income_annual"), INCOME),
                // a manual stip counts toward the check it names ...
                Arguments.of(PendingStip.of("STIP_DEALER_LETTER", Check.GAP), GAP),
                Arguments.of(PendingStip.of("STIP_DEALER_LETTER", Check.TILA), RISC),
                // ... and is GENERAL when it names none, like any code the table doesn't map (D-17)
                Arguments.of(PendingStip.of("STIP_DEALER_LETTER", null), GENERAL),
                Arguments.of(PendingStip.of("STIP_SOMETHING_NEW", null), GENERAL));
    }

    @ParameterizedTest
    @MethodSource("stipRows")
    void eachPendingStipHasItsKind(PendingStip stip, WorkKind kind) {
        assertThat(WorkRouting.kinds(stipulated(stip))).containsExactly(kind);
    }

    @Test
    void aFieldReviewOverTwoDocumentsIsTwoKinds() {
        assertThat(WorkRouting.kindsOf(review(Check.PRICE, "RISC.cash_price", "BUYERS_ORDER.total_vehicle_cash_price")))
                .containsExactlyInAnyOrder(RISC, DOCUMENTS);
    }

    @ParameterizedTest
    @EnumSource(Check.class)
    void aCheckInErrorIsItsChecksKind(Check check) {
        WorkKind expected = switch (check) {
            case GAP -> GAP;
            case INCOME -> INCOME;
            case TILA -> RISC;
            case PRICE, VIN -> DOCUMENTS;
        };
        assertThat(WorkRouting.kinds(underReview(new CurrentResult(check, Outcome.ERROR)))).containsExactly(expected);
    }

    @Test
    void incomeNeedingReviewIsIncomeWorkEvenWithNoStip() {
        assertThat(WorkRouting.kinds(underReview(new CurrentResult(Check.INCOME, Outcome.NEEDS_REVIEW)))).containsExactly(INCOME);
    }

    @Test
    void passedAndFailedResultsAloneAreNotWork() {
        // a FAIL comes with its stip, which is the work; a PASS is nothing to do
        assertThat(WorkRouting.kinds(underReview(new CurrentResult(Check.PRICE, Outcome.FAIL),
                new CurrentResult(Check.VIN, Outcome.PASS), new CurrentResult(Check.TILA, Outcome.NEEDS_REVIEW)))).isEmpty();
    }

    @Test
    void anIncompleteRiscIsRiscWork() {
        Deal held = new Deal(VersionStatus.PENDING_REVIEW, List.of(), List.of(), true, PaymentState.NOT_STARTED);
        assertThat(WorkRouting.kinds(held)).containsExactly(RISC);
    }

    @ParameterizedTest
    @EnumSource(value = PaymentState.class, names = {"FAILED", "UNKNOWN"})
    void anApprovedDealWhosePaymentFailedOrIsUnknownIsPaymentWork(PaymentState payment) {
        assertThat(WorkRouting.kinds(new Deal(VersionStatus.APPROVED, List.of(), List.of(), false, payment))).containsExactly(PAYMENT);
    }

    @ParameterizedTest
    @EnumSource(value = PaymentState.class, names = {"NOT_STARTED", "IN_FLIGHT", "PAID"})
    void anApprovedDealWhosePaymentIsFineIsNoWork(PaymentState payment) {
        assertThat(WorkRouting.kinds(new Deal(VersionStatus.APPROVED, List.of(), List.of(), false, payment))).isEmpty();
    }

    // ---------------------------------------------------------------- when nothing counts

    @ParameterizedTest
    @EnumSource(value = VersionStatus.class, names = {"DRAFT", "FUNDED", "REJECTED", "EXPIRED"})
    void aFinishedOrUnsubmittedDealNeedsNobody(VersionStatus status) {
        Deal deal = new Deal(status, List.of(PendingStip.of(StipCodes.PRICE_MISMATCH, Check.PRICE)),
                List.of(new CurrentResult(Check.INCOME, Outcome.ERROR)), true, PaymentState.FAILED);
        assertThat(WorkRouting.kinds(deal)).isEmpty();
        assertThat(WorkRouting.route(deal)).isEmpty();
    }

    @Test
    void oldResultsRouteNothingOnceADealHasMovedOn() {
        // approved by a supervisor over an INCOME error: the error isn't income work any more
        Deal approved = new Deal(VersionStatus.APPROVED, List.of(), List.of(new CurrentResult(Check.INCOME, Outcome.ERROR)),
                false, PaymentState.IN_FLIGHT);
        assertThat(WorkRouting.kinds(approved)).isEmpty();
    }

    // ---------------------------------------------------------------- routing

    @ParameterizedTest
    @EnumSource(WorkKind.class)
    void AS_11_2_oneKindGoesToItsSpecialist(WorkKind kind) {
        Role expected = switch (kind) {
            case GAP -> Role.GAP_SPECIALIST;
            case INCOME -> Role.INCOME_SPECIALIST;
            case RISC -> Role.RISC_SPECIALIST;
            case DOCUMENTS -> Role.DOCUMENTS_SPECIALIST;
            case PAYMENT -> Role.PAYMENT_SPECIALIST;
            case GENERAL -> Role.GENERAL_FUNDER;
        };
        assertThat(WorkRouting.route(EnumSet.of(kind))).contains(expected);
    }

    @Test
    void AS_11_2_twoOrMoreKindsGoToTheIncomeSpecialist() {
        // LA-1027's shape: a TILA stip, a VIN stip and an income stip
        Deal multi = stipulated(PendingStip.of(StipCodes.TILA_MATH_DISCREPANCY, Check.TILA),
                PendingStip.of(StipCodes.VIN_MISMATCH, Check.VIN), PendingStip.of(StipCodes.INCOME_VARIANCE_EXCEEDED, Check.INCOME));
        assertThat(WorkRouting.kinds(multi)).containsExactlyInAnyOrder(RISC, DOCUMENTS, INCOME);
        assertThat(WorkRouting.route(multi)).contains(Role.INCOME_SPECIALIST);
        // two kinds without any income work still go to the income specialist (the owner's rule)
        assertThat(WorkRouting.route(Set.of(GAP, DOCUMENTS))).contains(Role.INCOME_SPECIALIST);
        // GENERAL counts like any other kind (D-17)
        assertThat(WorkRouting.route(Set.of(GAP, GENERAL))).contains(Role.INCOME_SPECIALIST);
    }

    @Test
    void twoStipsOfOneKindStayWithThatSpecialist() {
        Deal gapOnly = stipulated(PendingStip.of(StipCodes.GAP_FORM_MISMATCH, Check.GAP), PendingStip.of(StipCodes.LENDER_MISMATCH, Check.GAP));
        assertThat(WorkRouting.route(gapOnly)).contains(Role.GAP_SPECIALIST);
    }

    @Test
    void noKindMeansNoSpecialist() {
        assertThat(WorkRouting.route(Set.of())).isEmpty();
    }
}
