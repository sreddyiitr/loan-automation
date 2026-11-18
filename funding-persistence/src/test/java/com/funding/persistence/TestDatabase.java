package com.funding.persistence;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.UUID;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * One Testcontainers {@code postgres:18} per test JVM; each call to {@link #fresh()} gets its own
 * freshly migrated database, so test classes never see each other's rows.
 */
public final class TestDatabase {

    private static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>("postgres:18");

    static {
        PG.start();
    }

    private final PGSimpleDataSource dataSource;

    private TestDatabase(PGSimpleDataSource ds) {
        this.dataSource = ds;
    }

    /** A new database, migrated with {@code db/migration} and (when {@code local}) {@code db/local}. */
    public static TestDatabase fresh(boolean local) {
        String name = "t_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        try (Connection c = DriverManager.getConnection(PG.getJdbcUrl(), PG.getUsername(), PG.getPassword())) {
            c.createStatement().execute("CREATE DATABASE " + name);
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
        PGSimpleDataSource ds = new PGSimpleDataSource();
        ds.setUrl(PG.getJdbcUrl().replaceFirst("/[^/?]+(\\?|$)", "/" + name + "$1"));
        ds.setUser(PG.getUsername());
        ds.setPassword(PG.getPassword());
        Flyway.configure().dataSource(ds)
                .locations(local ? new String[] {"classpath:db/migration", "classpath:db/local"} : new String[] {"classpath:db/migration"})
                .load().migrate();
        return new TestDatabase(ds);
    }

    public static TestDatabase fresh() {
        return fresh(false);
    }

    public DataSource dataSource() {
        return dataSource;
    }

    public JdbcClient jdbc() {
        return JdbcClient.create(dataSource);
    }

    public String url() {
        return dataSource.getUrl();
    }

    public String user() {
        return dataSource.getUser();
    }

    public String password() {
        return dataSource.getPassword();
    }
}
