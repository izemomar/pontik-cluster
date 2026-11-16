// SPDX-License-Identifier: MPL-2.0
package dev.pontik.cluster.lease;

import static dev.pontik.cluster.lease.Pg.bootstrap;
import static dev.pontik.cluster.lease.Pg.restricted;
import static dev.pontik.cluster.lease.Pg.restrictedRole;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class BootstrapTest {

    private static final String GRANT =
        "GRANT SELECT, INSERT, UPDATE ON pcl_node, pcl_lease, pcl_channel_config TO restricted";
    private static final String GRANT_DELETE = "GRANT DELETE ON pcl_channel_config TO restricted";

    // The README's schema script is its sql block that opens with the comment on the time columns.
    private static final Pattern SCHEMA_SCRIPT =
        Pattern.compile("```sql\n(-- Keep timestamp without time zone.*?)```", Pattern.DOTALL);

    @BeforeEach
    void emptyDatabase() throws SQLException {
        Pg.reset();
        restrictedRole();
    }

    @Test
    void threeConcurrentBootstrapsLeaveTheThreeTablesAndNoIndex() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(3);
        CyclicBarrier together = new CyclicBarrier(3);
        List<Future<?>> runs = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            runs.add(pool.submit(() -> {
                try (Connection c = Connections.open(Connections.second(Pg.settings()))) {
                    together.await();
                    Bootstrap.run(c);
                }
                return null;
            }));
        }
        for (Future<?> run : runs) {
            run.get();
        }
        pool.shutdown();

        assertEquals(3, count("SELECT count(*) FROM pg_tables WHERE tablename IN "
            + "('pcl_node', 'pcl_lease', 'pcl_channel_config')"));
        assertEquals(0, count("SELECT count(*) FROM pg_indexes WHERE tablename LIKE 'pcl%' "
            + "AND indexname NOT LIKE '%pkey'"));
    }

    @Test
    void aSecondBootstrapChangesNothing() throws SQLException {
        bootstrap(Pg.settings());
        String before = columns();
        bootstrap(Pg.settings());
        assertEquals(before, columns());
    }

    @Test
    void aUserWithoutCreateUsesTheTablesADbaMadeAndCreatesNothing() throws SQLException {
        Pg.execute(Sql.CREATE_NODE, Sql.CREATE_LEASE, Sql.CREATE_CHANNEL_CONFIG, GRANT, GRANT_DELETE);

        // The user may not create in the schema, so any CREATE we sent would fail with 42501.
        bootstrap(restricted());
    }

    @Test
    void aMissingTableTheUserMayNotCreateFailsWithTheTableNamed() throws SQLException {
        Pg.execute(Sql.CREATE_NODE, Sql.CREATE_LEASE,
            "GRANT SELECT, INSERT, UPDATE, DELETE ON pcl_node, pcl_lease TO restricted");

        BootstrapException e = assertThrows(BootstrapException.class, () -> bootstrap(restricted()));

        assertEquals(BootstrapException.Kind.CANNOT_CREATE, e.kind);
        assertEquals("pcl_channel_config", e.subject);
        assertEquals("42501", e.getSQLState());
    }

    @Test
    void aMissingPrivilegeFailsWithTheTableAndPrivilegeNamed() throws SQLException {
        Pg.execute(Sql.CREATE_NODE, Sql.CREATE_LEASE, Sql.CREATE_CHANNEL_CONFIG, GRANT);

        BootstrapException e = assertThrows(BootstrapException.class, () -> bootstrap(restricted()));

        assertEquals(BootstrapException.Kind.MISSING_PRIVILEGE, e.kind);
        assertEquals("DELETE on pcl_channel_config", e.subject);
    }

    @Test
    void theReadmeSchemaScriptMakesTheSameTablesAndLetsAUserWithoutCreateStart() throws Exception {
        bootstrap(Pg.settings());
        String made = columns();
        Pg.reset();

        Pg.execute(readmeSchemaScript().replace("engine_user", "restricted"));

        assertEquals(made, columns());
        bootstrap(restricted());
    }

    @Test
    void aTimestamptzOrMissingColumnIsRefusedAndTimestamp3Passes() throws SQLException {
        bootstrap(Pg.settings());

        Pg.execute("ALTER TABLE pcl_lease ALTER COLUMN acquired_at TYPE timestamptz");
        BootstrapException zoned = assertThrows(BootstrapException.class, () -> bootstrap(Pg.settings()));
        assertEquals(BootstrapException.Kind.WRONG_COLUMN, zoned.kind);
        assertEquals("pcl_lease.acquired_at", zoned.subject);
        assertEquals("timestamp with time zone", zoned.detail);

        Pg.execute("ALTER TABLE pcl_lease ALTER COLUMN acquired_at TYPE timestamp(3)");
        bootstrap(Pg.settings());

        Pg.execute("ALTER TABLE pcl_lease DROP COLUMN acquired_at");
        BootstrapException missing = assertThrows(BootstrapException.class, () -> bootstrap(Pg.settings()));
        assertEquals("pcl_lease.acquired_at", missing.subject);
        assertEquals("missing", missing.detail);
    }

    @Test
    void anEngineUrlWithItsOwnSchemaGetsTheTablesInThatSchema() throws SQLException {
        Pg.execute("DROP SCHEMA IF EXISTS engine_schema CASCADE", "CREATE SCHEMA engine_schema");
        try {
            DbSettings db = new DbSettings("postgres", Pg.url() + "&currentSchema=engine_schema", "test", "test");
            bootstrap(db);

            assertEquals(3, count("SELECT count(*) FROM pg_tables WHERE schemaname = 'engine_schema'"));
            assertEquals(0,
                count("SELECT count(*) FROM pg_tables WHERE schemaname = 'public' AND tablename LIKE 'pcl%'"));
        } finally {
            Pg.execute("DROP SCHEMA engine_schema CASCADE");
        }
    }

    private static String readmeSchemaScript() throws IOException {
        String readme = new String(Files.readAllBytes(Paths.get("../README.md")), StandardCharsets.UTF_8);
        Matcher m = SCHEMA_SCRIPT.matcher(readme);
        assertTrue(m.find(), "README.md has no schema script");
        return m.group(1);
    }

    private static String columns() throws SQLException {
        StringBuilder all = new StringBuilder();
        try (Connection c = Pg.admin(); Statement s = c.createStatement();
                ResultSet rs = s.executeQuery("SELECT table_name, column_name, data_type, column_default, "
                    + "character_maximum_length, is_nullable "
                    + "FROM information_schema.columns WHERE table_name LIKE 'pcl%' ORDER BY 1, 2")) {
            while (rs.next()) {
                all.append(rs.getString(1)).append('.').append(rs.getString(2)).append(' ')
                    .append(rs.getString(3)).append(' ').append(rs.getString(4)).append(' ')
                    .append(rs.getString(5)).append(' ').append(rs.getString(6)).append('\n');
            }
        }
        return all.toString();
    }

    private static long count(String sql) throws SQLException {
        try (Connection c = Pg.admin(); Statement s = c.createStatement(); ResultSet rs = s.executeQuery(sql)) {
            rs.next();
            return rs.getLong(1);
        }
    }
}
