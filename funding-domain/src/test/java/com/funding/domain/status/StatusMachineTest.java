package com.funding.domain.status;

import static com.funding.domain.status.VersionStatus.APPROVED;
import static com.funding.domain.status.VersionStatus.DRAFT;
import static com.funding.domain.status.VersionStatus.EXPIRED;
import static com.funding.domain.status.VersionStatus.FUNDED;
import static com.funding.domain.status.VersionStatus.PENDING_REVIEW;
import static com.funding.domain.status.VersionStatus.REJECTED;
import static com.funding.domain.status.VersionStatus.STIPULATED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/** T401: spec §6 table, transcribed independently of the implementation. */
class StatusMachineTest {

    /** Every row of the §6 table ("From | To | Trigger"). */
    private static final Set<List<VersionStatus>> SPEC = Set.of(
            List.of(DRAFT, PENDING_REVIEW),
            List.of(PENDING_REVIEW, APPROVED), List.of(PENDING_REVIEW, STIPULATED),
            List.of(STIPULATED, APPROVED),
            List.of(STIPULATED, PENDING_REVIEW),
            List.of(APPROVED, STIPULATED),
            List.of(APPROVED, FUNDED),
            List.of(PENDING_REVIEW, REJECTED), List.of(STIPULATED, REJECTED), List.of(APPROVED, REJECTED),
            List.of(PENDING_REVIEW, EXPIRED), List.of(STIPULATED, EXPIRED), List.of(APPROVED, EXPIRED));

    static Stream<Arguments> allowed() {
        return SPEC.stream().map(p -> Arguments.of(p.get(0), p.get(1)));
    }

    static Stream<Arguments> rejected() {
        List<Arguments> out = new ArrayList<>();
        for (VersionStatus from : VersionStatus.values()) {
            for (VersionStatus to : VersionStatus.values()) {
                if (!SPEC.contains(List.of(from, to))) {
                    out.add(Arguments.of(from, to));
                }
            }
        }
        return out.stream();
    }

    @ParameterizedTest(name = "{0} -> {1}")
    @MethodSource("allowed")
    void FR_09_everySpecTransitionIsAllowed(VersionStatus from, VersionStatus to) {
        assertThatCode(() -> VersionStatus.requireTransition(from, to)).doesNotThrowAnyException();
    }

    @ParameterizedTest(name = "{0} -> {1}")
    @MethodSource("rejected")
    void FR_09_everyOtherPairIsRejected(VersionStatus from, VersionStatus to) {
        assertThat(from.canMoveTo(to)).isFalse();
        assertThatThrownBy(() -> VersionStatus.requireTransition(from, to))
                .isInstanceOf(VersionStatus.IllegalTransitionException.class).hasMessageContaining(from + " -> " + to);
    }

    @Test
    void FR_09_fundedRejectedAndExpiredAreTerminal() {
        assertThat(Stream.of(VersionStatus.values()).filter(VersionStatus::terminal)).containsExactlyInAnyOrder(FUNDED, REJECTED, EXPIRED);
        assertThat(FUNDED.next()).isEmpty();
        assertThat(REJECTED.next()).isEmpty();
        assertThat(EXPIRED.next()).isEmpty();
    }
}
