package com.funding.seed;

import static org.assertj.core.api.Assertions.assertThat;

import com.funding.domain.Check;
import com.funding.domain.ExternalApi;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/** T603: one volume application per catalog entry, deterministic, with derived expectations. */
class VolumeSetTest {

    private static final SeedConfig CONFIG = SeedConfig.fromDefaultsAndSystem("local");

    private static List<VolumeSet.Case> cases() {
        return new VolumeSet(CONFIG, SeedPaths.discover()).cases();
    }

    @Test
    void oneApplicationPerCatalogEntryWithStableIds() {
        List<VolumeSet.Case> a = cases();
        List<VolumeSet.Case> b = cases();
        assertThat(a).extracting(c -> c.fixture().loanApplicationId())
                .doesNotHaveDuplicates()
                .containsExactlyElementsOf(b.stream().map(c -> c.fixture().loanApplicationId()).toList()); // deterministic
        Map<ExternalApi, Long> perApi = a.stream().collect(Collectors.groupingBy(c -> c.catalogStub().api(), Collectors.counting()));
        assertThat(perApi.get(ExternalApi.INCOME_VERIFICATION)).isGreaterThanOrEqualTo(150);
        assertThat(perApi.get(ExternalApi.DISBURSEMENT)).isGreaterThanOrEqualTo(48);
        assertThat(a).allSatisfy(c -> assertThat(c.fixture().loanApplicationId())
                .matches(c.catalogStub().api() == ExternalApi.INCOME_VERIFICATION ? "LA-5\\d{3}" : "LA-6\\d{3}"));
    }

    @Test
    void expectationsAgreeWithTheCatalogsOwnOutcome() {
        for (VolumeSet.Case c : cases()) {
            if (c.catalogStub().api() == ExternalApi.INCOME_VERIFICATION) {
                // the engine + simulator outcome is the band oracle's (mock-band-oracle gate)
                assertThat(c.outcomes().get(Check.INCOME).outcome().name()).as(c.catalogStub().id()).isEqualTo(c.catalogStub().expected());
                String want = switch (c.catalogStub().expected()) {
                    case "PASS" -> "FUNDED";
                    case "ERROR" -> "PENDING_REVIEW";
                    default -> "STIPULATED";
                };
                assertThat(c.expectedStatus()).as(c.catalogStub().id()).isEqualTo(want);
                assertThat(c.fixture().latest().ssnItinToken()).isEqualTo(c.catalogStub().attributes().get("token"));
            } else {
                // the catalog stub matches loan id AND amount: the disbursed amount is the RISC amount_financed
                assertThat(c.fixture().latest().latestDocuments().stream()
                        .filter(d -> d.type() == com.funding.domain.DocumentType.RISC).findFirst().orElseThrow().value("amount_financed"))
                        .as(c.catalogStub().id()).isEqualTo(c.catalogStub().attributes().get("magnitude"));
                assertThat(c.fixture().loanApplicationId()).isEqualTo(c.catalogStub().attributes().get("loanApplicationId"));
                assertThat(c.expectedStatus()).isEqualTo(c.catalogStub().expected());
                assertThat(c.expectedStips()).isEmpty();
            }
        }
    }

    @Test
    void healthyPopulationIsTheImmediateAnswers() {
        List<VolumeSet.Case> healthy = cases().stream().filter(VolumeSet.Case::healthy).toList();
        assertThat(healthy).isNotEmpty();
        Set<String> bands = healthy.stream().map(c -> c.catalogStub().band()).collect(Collectors.toSet());
        assertThat(bands).doesNotContain("TIMEOUT_ALWAYS", "TIMEOUT_THEN_OK", "RATE_LIMITED", "EXHAUSTED", "TIMEOUT_BUT_PROCESSED");
    }
}
