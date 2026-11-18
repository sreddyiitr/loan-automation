package com.funding.api.functional;

import static org.assertj.core.api.Assertions.assertThat;

import com.funding.seed.Fixture;
import com.funding.seed.SeedGates;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * T131: every generated scenario, submitted through the real path (DB trigger -> outbox -> emulator
 * SQS -> LocalLambdaRunner + handler -> start API -> Temporal), reaches its declared status and stips.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ScenarioE2EIT extends FunctionalTestBase {

    @Autowired
    private SeedGates gates;

    Stream<Fixture> scenarios() {
        return gates.generate().fixtures().stream();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("scenarios")
    void scenarioReachesExpectedStatusAndStips(Fixture f) throws Exception {
        HttpResponse<String> r = httpPost(base() + "/api/v1/dev/applications/" + f.loanApplicationId() + "/submit", null);
        assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
        long versionId = JSON.readTree(r.body()).path("applicationVersionId").asLong();

        String want = f.intent().expect().status();
        await(Duration.ofSeconds(30), () -> want.equals(status(versionId)), f.scenarioId() + " -> " + want);

        List<String> stips = jdbc.sql("SELECT stip_code FROM stipulations WHERE application_version_id = ? AND status = 'PENDING' ORDER BY stip_code")
                .param(versionId).query(String.class).list();
        assertThat(stips).containsExactlyInAnyOrderElementsOf(f.intent().expect().stips());
        if ("FUNDED".equals(want)) {
            assertThat(jdbc.sql("SELECT count(*) FROM disbursements WHERE application_version_id = ? AND status = 'COMPLETED'")
                    .param(versionId).query(Integer.class).single()).isEqualTo(1);
        }
    }

    @org.junit.jupiter.api.Test
    void LA_1031_multiPageSectionAndBoundingBoxesArePersisted() {
        var rows = jdbc.sql("""
                SELECT s.start_page, s.end_page, f.field_key, f.page_number, f.bounding_box::text AS box
                  FROM documents d JOIN application_versions v USING (application_version_id)
                  JOIN document_sections s USING (document_id) JOIN extracted_fields f USING (section_id)
                 WHERE v.loan_application_id = 'LA-1031' AND d.document_type = 'RISC'
                   AND f.field_key IN ('cash_price', 'amount_financed') ORDER BY f.field_key""").query().listOfRows();
        assertThat(rows).hasSize(2).allSatisfy(r -> {
            assertThat(r.get("start_page")).isEqualTo(3);
            assertThat(r.get("end_page")).isEqualTo(4);
            assertThat((String) r.get("box")).startsWith("[");
        });
        assertThat(rows).extracting(r -> r.get("field_key") + "@" + r.get("page_number"))
                .containsExactly("amount_financed@4", "cash_price@3");
    }

    @AfterAll
    void P_5_7_noUnmatchedRequests() throws Exception {
        assertThat(unmatchedForThisEnv()).isZero();
    }
}
