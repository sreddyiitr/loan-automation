package com.funding.api.dev;

import com.funding.api.ApiProblem;
import com.funding.api.seed.SeedDataLoader;
import com.funding.workflows.WorkflowIds;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowNotFoundException;
import java.util.List;
import java.util.Map;
import java.util.function.LongConsumer;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Dev-only (FR-16), {@code local} profile only: {@code POST /api/v1/dev/applications/{id}/reseed}
 * puts one seeded scenario back as a fresh database has it (every version as seeded, the latest
 * DRAFT; submit it to run it again). Spec 002 T304: the browser tests that fund or re-stipulate a
 * seeded deal (LA-1006, LA-1030) put it back this way, so the next run, and AS-10.3, find it as
 * seeded. A FUNDED deal can't be resubmitted (C-24), so this is the only way back for it.
 *
 * <p>Each version's open run is terminated first, so nothing writes to the rows being replaced.
 * 404 {@code NOT_FOUND} when no scenario seeds that id; 409 {@code SEEDING_OFF} when the seeder
 * isn't enabled ({@code funding.seed-data}).
 */
@RestController
@Profile("local")
@RequestMapping("/api/v1/dev/applications")
public class DevReseedController {

    private final JdbcClient jdbc;
    private final SeedDataLoader seeder;
    private final LongConsumer stopRun;

    @Autowired
    public DevReseedController(JdbcClient jdbc, ObjectProvider<SeedDataLoader> seeder, WorkflowClient client,
                               @Value("${funding.env}") String env) {
        this(jdbc, seeder.getIfAvailable(), versionId -> {
            try {
                client.newUntypedWorkflowStub(WorkflowIds.parent(env, versionId)).terminate("reseeded (dev)");
            } catch (WorkflowNotFoundException closedOrNeverStarted) {
                // nothing to stop
            }
        });
    }

    DevReseedController(JdbcClient jdbc, SeedDataLoader seeder, LongConsumer stopRun) {
        this.jdbc = jdbc;
        this.seeder = seeder;
        this.stopRun = stopRun;
    }

    @PostMapping("/{loanApplicationId}/reseed")
    public Map<String, Object> reseed(@PathVariable String loanApplicationId) {
        if (seeder == null) {
            throw new ApiProblem(HttpStatus.CONFLICT, "SEEDING_OFF", "the seeder is off (funding.seed-data=false)");
        }
        List<Long> versions = jdbc.sql("SELECT application_version_id FROM application_versions WHERE loan_application_id = ?")
                .param(loanApplicationId).query(Long.class).list();
        versions.forEach(stopRun::accept);
        try {
            seeder.reseed(loanApplicationId);
        } catch (IllegalArgumentException e) {
            throw new ApiProblem(HttpStatus.NOT_FOUND, "NOT_FOUND", e.getMessage());
        }
        Map<String, Object> latest = jdbc.sql("""
                SELECT application_version_id, version_number, version_status FROM application_versions
                 WHERE loan_application_id = ? AND is_latest_version""").param(loanApplicationId).query().singleRow();
        return Map.of("loanApplicationId", loanApplicationId, "applicationVersionId", latest.get("application_version_id"),
                "versionNumber", latest.get("version_number"), "status", latest.get("version_status"),
                "replacedVersions", versions.size());
    }
}
