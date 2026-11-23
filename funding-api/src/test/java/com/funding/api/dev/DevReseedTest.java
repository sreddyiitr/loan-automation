package com.funding.api.dev;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.funding.api.ProblemHandler;
import com.funding.api.seed.SeedDataLoader;
import com.funding.persistence.TestDatabase;
import com.funding.seed.RuleFingerprints;
import com.funding.seed.SeedConfig;
import com.funding.seed.SeedGates;
import com.funding.seed.SeedPaths;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.support.TransactionTemplate;

/** Spec 002 T304: the dev reseed puts one seeded deal back as a fresh database has it, after stopping its runs. */
class DevReseedTest {

    private JdbcClient jdbc;
    private SeedDataLoader seeder;
    private final List<Long> stopped = new ArrayList<>();

    @BeforeEach
    void setUp() {
        TestDatabase db = TestDatabase.fresh(true);
        jdbc = db.jdbc();
        TransactionTemplate tx = new TransactionTemplate(new DataSourceTransactionManager(db.dataSource()));
        SeedConfig config = SeedConfig.fromDefaultsAndSystem("t");
        seeder = new SeedDataLoader(new SeedGates(SeedPaths.discover(), config, RuleFingerprints.fromClasspath()), config, jdbc, tx);
        seeder.load();
    }

    private MockMvc mvc(SeedDataLoader loader) {
        return MockMvcBuilders.standaloneSetup(new DevReseedController(jdbc, loader, stopped::add))
                .setControllerAdvice(new ProblemHandler()).build();
    }

    private List<String> versions(String loan) {
        return jdbc.sql("""
                SELECT version_number || ':' || version_status || ':' || is_latest_version FROM application_versions
                 WHERE loan_application_id = ? ORDER BY version_number""").param(loan).query(String.class).list();
    }

    @Test
    void aFundedDealComesBackAsSeededAfterItsRunsAreStopped() throws Exception {
        long funded = jdbc.sql("SELECT application_version_id FROM application_versions WHERE loan_application_id = 'LA-1006'")
                .query(Long.class).single();
        jdbc.sql("UPDATE application_versions SET version_status = 'FUNDED' WHERE application_version_id = ?").param(funded).update();
        jdbc.sql("UPDATE application_versions SET version_status = 'FUNDED' WHERE loan_application_id = 'LA-1001'").update();

        mvc(seeder).perform(post("/api/v1/dev/applications/LA-1006/reseed"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DRAFT"))
                .andExpect(jsonPath("$.versionNumber").value(1))
                .andExpect(jsonPath("$.replacedVersions").value(1));

        assertThat(stopped).containsExactly(funded);                    // its run is stopped before its rows go
        assertThat(versions("LA-1006")).containsExactly("1:DRAFT:true");
        assertThat(jdbc.sql("SELECT count(*) FROM application_versions WHERE application_version_id = ?").param(funded)
                .query(Integer.class).single()).isZero();
        assertThat(versions("LA-1001")).containsExactly("1:FUNDED:true"); // other deals are untouched
        assertThat(seeder.load().replaced()).isZero();                    // its fingerprint is the seeded one
    }

    @Test
    void aDealWithHistoryGetsEveryVersionBack() throws Exception {
        jdbc.sql("UPDATE application_versions SET version_status = 'FUNDED' WHERE loan_application_id = 'LA-1028' AND is_latest_version").update();
        mvc(seeder).perform(post("/api/v1/dev/applications/LA-1028/reseed")).andExpect(status().isOk())
                .andExpect(jsonPath("$.versionNumber").value(2));
        assertThat(stopped).hasSize(2);
        assertThat(versions("LA-1028")).containsExactly("1:STIPULATED:false", "2:DRAFT:true");
    }

    @Test
    void anIdNoScenarioSeedsIs404AndASeederThatIsOffIs409() throws Exception {
        mvc(seeder).perform(post("/api/v1/dev/applications/LA-404/reseed"))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.reason").value("NOT_FOUND"));
        mvc(null).perform(post("/api/v1/dev/applications/LA-1006/reseed"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.reason").value("SEEDING_OFF"));
    }
}
