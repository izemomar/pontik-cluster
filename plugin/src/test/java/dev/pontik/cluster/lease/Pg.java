// SPDX-License-Identifier: MPL-2.0
package dev.pontik.cluster.lease;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.Properties;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * The PostgreSQL server the database tests share: one image, pinned by digest, started once per
 * test run and reached through the engine's own driver.
 */
final class Pg {

    static final String IMAGE =
        "postgres:17-alpine@sha256:b0f9560a2de083e2cc7382e75f808c7381a32852a7ec49117deedb300e552b24";

    private static PostgreSQLContainer shared;

    private Pg() {
    }

    static synchronized PostgreSQLContainer container() {
        if (shared == null) {
            PostgreSQLContainer started = start();
            shared = started;
        }

        return shared;
    }

    /** A server of its own, for a test that stops it. */
    static PostgreSQLContainer start() {
        PostgreSQLContainer container =
            new PostgreSQLContainer(DockerImageName.parse(IMAGE).asCompatibleSubstituteFor("postgres"));
        container.start();
        return container;
    }

    static String url() {
        return container().getJdbcUrl();
    }

    static Connection admin() throws SQLException {
        PostgreSQLContainer c = container();
        return DriverManager.getConnection(c.getJdbcUrl(), c.getUsername(), c.getPassword());
    }

    static DbSettings settings() {
        PostgreSQLContainer c = container();
        return new DbSettings("postgres", c.getJdbcUrl(), c.getUsername(), c.getPassword());
    }

    /**
     * The keeper connection's properties with a 10 second login. A loaded test host can take longer
     * than the production second to start a session, and these tests are not about that budget;
     * ConnectionsTest checks the production values.
     */
    static Properties keeper(DbSettings db) throws SQLException {
        Properties p = Connections.keeper(db, Timing.STANDARD);
        p.setProperty("loginTimeout", "10");
        p.setProperty("connectTimeout", "10");
        return p;
    }

    static Properties keeper() throws SQLException {
        return keeper(settings());
    }

    static void execute(String... statements) throws SQLException {
        try (Connection c = admin(); Statement s = c.createStatement()) {
            for (String statement : statements) {
                s.execute(statement);
            }
        }
    }

    /** Drops our tables, so each test starts from an empty database. */
    static void reset() throws SQLException {
        execute("DROP TABLE IF EXISTS pcl_node, pcl_lease, pcl_channel_config");
    }

    /** Creates the tables the way a starting node does, on a second connection. */
    static void bootstrap(DbSettings db) throws SQLException {
        try (Connection c = Connections.open(Connections.second(db))) {
            Bootstrap.run(c);
        }
    }

    /** Creates a login that may use the public schema but not create in it, like an engine user a DBA set up. */
    static void restrictedRole() throws SQLException {
        execute("DO $$ BEGIN IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'restricted') THEN "
            + "CREATE ROLE restricted LOGIN PASSWORD 'restricted'; END IF; END $$");
    }

    /** Returns the settings of that login. */
    static DbSettings restricted() {
        return new DbSettings("postgres", url(), "restricted", "restricted");
    }

    static LocalDateTime time(String sql) throws SQLException {
        return (LocalDateTime) one(sql, LocalDateTime.class);
    }

    static String text(String sql) throws SQLException {
        return (String) one(sql, String.class);
    }

    static boolean bool(String sql) throws SQLException {
        return (Boolean) one(sql, Boolean.class);
    }

    static long number(String sql) throws SQLException {
        return ((Number) one(sql, Long.class)).longValue();
    }

    /** Reads the first column of the first row on an admin connection. */
    private static Object one(String sql, Class<?> type) throws SQLException {
        try (Connection c = admin(); PreparedStatement s = c.prepareStatement(sql); ResultSet rs = s.executeQuery()) {
            assertTrue(rs.next(), sql);
            return rs.getObject(1, type);
        }
    }
}
