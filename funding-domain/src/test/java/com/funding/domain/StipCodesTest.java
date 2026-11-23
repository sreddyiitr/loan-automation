package com.funding.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.funding.domain.StipCodes.StipCode;
import com.funding.domain.StipCodes.Waivable;
import com.funding.domain.routing.WorkKind;
import com.funding.domain.routing.WorkRouting;
import com.funding.domain.routing.WorkRouting.PendingStip;
import com.funding.domain.status.StipWaivers;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/** Spec 002 D-19: the stip-code list behind GET /stip-codes and the manual-stip check (spec 001 AS-10.8). */
class StipCodesTest {

    static List<StipCode> catalog() {
        return StipCodes.catalog();
    }

    @Test
    void everyCodeConstantIsListedOnceAndNothingElseIs() throws IllegalAccessException {
        List<String> constants = new java.util.ArrayList<>();
        for (Field f : StipCodes.class.getDeclaredFields()) {
            if (Modifier.isStatic(f.getModifiers()) && f.getType() == String.class) {
                constants.add((String) f.get(null));
            }
        }
        List<String> listed = catalog().stream().map(StipCode::code).toList();
        assertThat(listed).doesNotHaveDuplicates().containsExactlyInAnyOrderElementsOf(constants);
    }

    @Test
    void theManualOnlyCodesComeLastAndAreTheCatchAllsWork() {
        List<StipCode> manual = catalog().stream().filter(StipCode::manualOnly).toList();
        assertThat(manual).extracting(StipCode::code).containsExactly(StipCodes.DEALER_LETTER, StipCodes.OTHER);
        assertThat(catalog().subList(catalog().size() - manual.size(), catalog().size())).isEqualTo(manual);
        for (StipCode c : manual) {
            // a funder's stip names no check (validation_check = NULL), so routing sees it that way
            assertThat(WorkRouting.kindsOf(PendingStip.of(c.code(), null))).containsExactly(WorkKind.GENERAL);
        }
    }

    @Test
    void aFieldReviewCanComeFromAnyCheck() {
        assertThat(StipCodes.find(StipCodes.EXTRACTION_REVIEW_REQUIRED).orElseThrow().raisedBy())
                .isEqualTo(Set.of(Check.values()));
    }

    @ParameterizedTest
    @MethodSource("catalog")
    void waivableAgreesWithStipWaivers(StipCode c) {
        boolean plain = StipWaivers.waivable(c.code(), "a description");
        boolean onRisc = StipWaivers.waivable(c.code(), "RISC.cash_price was read with low confidence");
        switch (c.waivable()) {
            case ALWAYS -> assertThat(Arrays.asList(plain, onRisc)).containsOnly(true);
            case NEVER -> assertThat(Arrays.asList(plain, onRisc)).containsOnly(false);
            case UNLESS_RISC_FIELD -> assertThat(Arrays.asList(plain, onRisc)).containsExactly(true, false);
        }
    }

    @ParameterizedTest
    @MethodSource("catalog")
    void everyCodeHasWords(StipCode c) {
        assertThat(c.description()).isNotBlank().doesNotContain("STIP_");
    }

    @Test
    void anUnlistedCodeIsNotFound() {
        assertThat(StipCodes.find("STIP_SOMETHING_NEW")).isEmpty();
        assertThat(StipCodes.find(StipCodes.PRICE_MISMATCH)).map(StipCode::waivable).contains(Waivable.ALWAYS);
    }
}
