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

    @AfterAll
    void P_5_7_noUnmatchedRequests() throws Exception {
        assertThat(unmatchedForThisEnv()).isZero();
    }
}
