package com.funding.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** T506: AS-9.1, a new application version takes the latest flag in one transaction; two concurrent V2s, one wins. */
class VersionCreationTest {

    private TestDatabase db;
    private JdbcClient jdbc;
    private long v1;

    @BeforeEach
    void setUp() {
        db = TestDatabase.fresh();
        jdbc = db.jdbc();
        v1 = new ApplicationWriter(jdbc).insert("LA-1", List.of(RepositoriesTest.version(1, true, List.of(
                RepositoriesTest.risc(1, true, "31450.00", "0.97"))))).get(0);
        jdbc.sql("UPDATE application_versions SET version_status = 'PENDING_REVIEW' WHERE application_version_id = ?").param(v1).update();
        jdbc.sql("UPDATE application_versions SET version_status = 'STIPULATED' WHERE application_version_id = ?").param(v1).update();
    }

    private List<Map<String, Object>> versions() {
        return jdbc.sql("""
                SELECT version_number AS n, is_latest_version AS latest, version_status AS status FROM application_versions
                 WHERE loan_application_id = 'LA-1' ORDER BY version_number""").query().listOfRows();
    }

    @Test
    void AS_9_1_newVersionFlipsLatestInOneTransaction() {
        TransactionTemplate tx = new TransactionTemplate(new DataSourceTransactionManager(db.dataSource()));
        long v2 = tx.execute(s -> new ApplicationWriter(jdbc).addVersion("LA-1", RepositoriesTest.version(0, false, List.of())));
        assertThat(versions()).extracting(r -> r.get("n"), r -> r.get("latest"), r -> r.get("status")).containsExactly(
                org.assertj.core.groups.Tuple.tuple(1, false, "STIPULATED"), // V1 keeps its status (AS-9.2)
                org.assertj.core.groups.Tuple.tuple(2, true, "DRAFT"));
        assertThat(new VersionRepository(jdbc).findLatest("LA-1").orElseThrow().applicationVersionId()).isEqualTo(v2);

        // A failure later in the same transaction leaves V1 latest: the flip and the insert are one unit.
        try {
            tx.executeWithoutResult(s -> {
                new ApplicationWriter(jdbc).addVersion("LA-1", RepositoriesTest.version(0, false, List.of()));
                throw new IllegalStateException("boom");
            });
        } catch (IllegalStateException expected) {
            // rolled back
        }
        assertThat(versions()).hasSize(2);
        assertThat(new VersionRepository(jdbc).findLatest("LA-1").orElseThrow().applicationVersionId()).isEqualTo(v2);
    }

    @Test
    void concurrentCreationOfTwoV2sOneWins() throws Exception {
        TransactionTemplate tx = new TransactionTemplate(new DataSourceTransactionManager(db.dataSource()));
        CountDownLatch bothLocked = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<Long>> calls = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                calls.add(pool.submit(() -> tx.execute(s -> {
                    long id = new ApplicationWriter(jdbc).addVersion("LA-1", RepositoriesTest.version(0, false, List.of()));
                    try {
                        bothLocked.await(15, TimeUnit.SECONDS); // hold the lock so the other call has to wait on it
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    return id;
                })));
            }
            // Release the first only once the second is blocked on V1's row lock: both read "V1 is latest" before either commits.
            // (Released any earlier, the second would simply run after the first and correctly create V3.)
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (jdbc.sql("SELECT count(*) FROM pg_stat_activity WHERE datname = current_database() AND wait_event_type = 'Lock'")
                    .query(Integer.class).single() == 0) {
                assertThat(System.nanoTime()).as("second transaction never blocked on the lock").isLessThan(deadline);
                Thread.onSpinWait();
            }
            bothLocked.countDown();
            int won = 0;
            int lost = 0;
            for (Future<Long> f : calls) {
                try {
                    f.get(20, TimeUnit.SECONDS);
                    won++;
                } catch (java.util.concurrent.ExecutionException e) {
                    lost++;
                }
            }
            assertThat(won).isEqualTo(1);
            assertThat(lost).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
        assertThat(versions()).hasSize(2);
        assertThat(jdbc.sql("SELECT count(*) FROM application_versions WHERE loan_application_id = 'LA-1' AND is_latest_version")
                .query(Integer.class).single()).isEqualTo(1);
    }

    /** C-24 (review fix): no new version once V1 is paid or its payment started, so a dealer is never paid twice. */
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({
            "FUNDED,   COMPLETED, VERSION_FUNDED",
            "APPROVED, REQUESTED, DISBURSEMENT_STARTED",
            "APPROVED, COMPLETED, DISBURSEMENT_STARTED",
            "EXPIRED,  REQUESTED, DISBURSEMENT_STARTED"})
    void C_24_resubmissionRefusedOncePaymentStarted(String status, String disbursement, String reason) {
        jdbc.sql("UPDATE application_versions SET version_status = 'APPROVED' WHERE application_version_id = ?").param(v1).update();
        jdbc.sql("INSERT INTO disbursements (application_version_id, amount, dealership_id, status) VALUES (?, 28450.00, 'DLR-100', ?)")
                .params(v1, disbursement).update();
        if (!"APPROVED".equals(status)) {
            jdbc.sql("UPDATE application_versions SET version_status = ? WHERE application_version_id = ?").params(status, v1).update();
        }
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                        new ApplicationWriter(jdbc).addVersion("LA-1", RepositoriesTest.version(0, false, List.of())))
                .isInstanceOfSatisfying(ResubmissionRefusedException.class, e -> assertThat(e.reason()).isEqualTo(reason));
        assertThat(versions()).hasSize(1);
    }

    @Test
    void C_24_resubmissionAllowedAfterARefusedDisbursement() {
        jdbc.sql("UPDATE application_versions SET version_status = 'APPROVED' WHERE application_version_id = ?").param(v1).update();
        jdbc.sql("INSERT INTO disbursements (application_version_id, amount, dealership_id, status) VALUES (?, 28450.00, 'DLR-100', 'FAILED')")
                .param(v1).update(); // the API refused the payment (4xx): nothing was paid
        new ApplicationWriter(jdbc).addVersion("LA-1", RepositoriesTest.version(0, false, List.of()));
        assertThat(versions()).hasSize(2);
    }
}
