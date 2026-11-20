package com.funding.api.functional;

import static org.assertj.core.api.Assertions.assertThat;

import com.funding.seed.SeedConfig;
import com.funding.seed.SeedPaths;
import com.funding.seed.VolumeSet;
import com.funding.workflows.FundingState;
import com.funding.workflows.FundingWorkflow;
import com.funding.workflows.WorkflowIds;
import io.temporal.client.WorkflowClient;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.test.context.TestPropertySource;

/**
 * T604, NFR-01: the volume seed (T603, one application per mock catalog entry) submitted through
 * the real trigger path all at once. Every application must reach its derived status, stips and
 * five current results, and the validation time (submission to the first status the workflow
 * sets) of the healthy-API population must be under 30 s at p95. Writes
 * {@code target/volume-report.md}. Run with {@code make test-functional-volume}; the normal
 * functional run excludes it.
 */
@TestPropertySource(properties = "funding.seed.volume=true")
class FundingVolumeIT extends FunctionalTestBase {

    private static final Duration NFR_01 = Duration.ofSeconds(30);

    @Autowired private SeedConfig config;
    @Autowired private WorkflowClient client;
    @Value("${funding.env}") private String env;

    @Test
    void NFR_01_everyVolumeApplicationSettlesAsDerivedAndHealthyP95IsUnder30s() throws Exception {
        List<VolumeSet.Case> cases = new VolumeSet(config, SeedPaths.discover()).cases();
        assertThat(cases).hasSizeGreaterThanOrEqualTo(200);

        long started = System.nanoTime();
        Map<String, VolumeSet.Case> byLoan = new LinkedHashMap<>();
        Map<String, Long> versions = new HashMap<>();
        for (VolumeSet.Case c : cases) {
            String loan = c.fixture().loanApplicationId();
            HttpResponse<String> r = httpPost(base() + "/api/v1/dev/applications/" + loan + "/submit", null);
            assertThat(r.statusCode()).as(loan + " " + r.body()).isEqualTo(200);
            byLoan.put(loan, c);
            versions.put(loan, JSON.readTree(r.body()).path("applicationVersionId").asLong());
        }

        Map<String, String> pending = new TreeMap<>();
        byLoan.keySet().forEach(l -> pending.put(l, "not settled"));
        long deadline = System.nanoTime() + Duration.ofMinutes(8).toNanos();
        while (!pending.isEmpty() && System.nanoTime() < deadline) {
            for (String loan : new ArrayList<>(pending.keySet())) {
                String why = unsettled(byLoan.get(loan), versions.get(loan));
                if (why == null) {
                    pending.remove(loan);
                } else {
                    pending.put(loan, why);
                }
            }
            if (!pending.isEmpty()) {
                Thread.sleep(1000);
            }
        }
        Duration wall = Duration.ofNanos(System.nanoTime() - started);
        assertThat(pending).as("volume applications not at their derived status, stips and results").isEmpty();

        // Validation time: submitted_at (stamped by the DB on submit) to the first status the workflow set.
        Map<String, Double> seconds = new HashMap<>();
        jdbc.sql("""
                SELECT v.loan_application_id, EXTRACT(EPOCH FROM (min(a.created_at) - v.submitted_at)) AS s
                  FROM application_versions v
                  JOIN audit_log a ON a.entity_type = 'APPLICATION_VERSION' AND a.entity_id = v.application_version_id::text
                                  AND a.action = 'STATUS_CHANGED' AND a.actor LIKE 'system:%'
                 WHERE v.loan_application_id LIKE 'LA-5%' OR v.loan_application_id LIKE 'LA-6%'
                 GROUP BY v.loan_application_id, v.submitted_at""")
                .query((rs, i) -> Map.entry(rs.getString(1), rs.getDouble(2))).list()
                .forEach(e -> seconds.put(e.getKey(), e.getValue()));
        List<Double> healthy = byLoan.values().stream().filter(VolumeSet.Case::healthy)
                .map(c -> seconds.get(c.fixture().loanApplicationId())).filter(java.util.Objects::nonNull).sorted().toList();
        List<Double> all = seconds.values().stream().sorted().toList();
        assertThat(healthy).as("healthy-API applications with a measured validation time").hasSizeGreaterThanOrEqualTo(50);
        double p95 = percentile(healthy, 95);

        report(cases, healthy, all, wall);
        assertThat(p95).as("NFR-01: p95 validation time with healthy APIs (s)").isLessThan(NFR_01.toSeconds());
        assertThat(unmatchedForThisEnv()).isZero(); // P-5.7
    }

    /** Null when the application is at its derived status, stips and five current results, and its run has settled. */
    private String unsettled(VolumeSet.Case c, long versionId) {
        String status = status(versionId);
        if (!c.expectedStatus().equals(status)) {
            return "status " + status + ", expected " + c.expectedStatus();
        }
        int results = jdbc.sql("SELECT count(*) FROM validation_results WHERE application_version_id = ? AND is_current")
                .param(versionId).query(Integer.class).single();
        if (results != 5) {
            return results + " current results";
        }
        List<String> stips = jdbc.sql("SELECT stip_code FROM stipulations WHERE application_version_id = ? AND status = 'PENDING' ORDER BY stip_code")
                .param(versionId).query(String.class).list();
        if (!stips.equals(c.expectedStips())) {
            return "stips " + stips + ", expected " + c.expectedStips();
        }
        if ("FUNDED".equals(status)) {
            return null; // terminal
        }
        try { // APPROVED, STIPULATED, PENDING_REVIEW: settled once the run waits for a funder
            FundingState s = client.newWorkflowStub(FundingWorkflow.class, WorkflowIds.parent(env, versionId)).getState();
            return "WAITING_FOR_FUNDER".equals(s.step()) ? null : "workflow step " + s.step();
        } catch (RuntimeException e) {
            return "no state: " + e.getMessage();
        }
    }

    private static double percentile(List<Double> sorted, int p) {
        int i = (int) Math.ceil(p / 100.0 * sorted.size()) - 1;
        return sorted.get(Math.max(0, Math.min(i, sorted.size() - 1)));
    }

    private void report(List<VolumeSet.Case> cases, List<Double> healthy, List<Double> all, Duration wall) throws Exception {
        Map<String, Long> byStatus = new TreeMap<>();
        cases.forEach(c -> byStatus.merge(c.expectedStatus(), 1L, Long::sum));
        StringBuilder md = new StringBuilder("# Volume run (T604, NFR-01)\n\n")
                .append("Applications: ").append(cases.size()).append(" (one per mock catalog entry), all submitted at once ")
                .append("through the real trigger path; every one reached its derived status, stips and results in ")
                .append(String.format(Locale.ROOT, "%.1f s", wall.toMillis() / 1000.0)).append(" wall time.\n\n")
                .append("| Status | Applications |\n|---|---:|\n");
        byStatus.forEach((s, n) -> md.append("| ").append(s).append(" | ").append(n).append(" |\n"));
        md.append("\nValidation time: submission to the first status the workflow set.\n\n")
                .append("| Population | n | p50 (s) | p95 (s) | max (s) |\n|---|---:|---:|---:|---:|\n")
                .append(row("Healthy APIs (NFR-01: p95 < 30 s)", healthy))
                .append(row("All (incl. retries, timeouts, rate limits)", all))
                .append("\nAn ERROR hold stays PENDING_REVIEW, so the workflow writes no status for it and it has no time here")
                .append(" (it is still checked above: status, stips and results).\n");
        Path out = Path.of("target", "volume-report.md");
        Files.createDirectories(out.getParent());
        Files.writeString(out, md.toString());
        System.out.println(md);
    }

    private static String row(String name, List<Double> xs) {
        return String.format(Locale.ROOT, "| %s | %d | %.2f | %.2f | %.2f |%n", name, xs.size(),
                percentile(xs, 50), percentile(xs, 95), xs.get(xs.size() - 1));
    }
}
