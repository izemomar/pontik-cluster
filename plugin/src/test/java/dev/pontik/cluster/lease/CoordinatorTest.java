// SPDX-License-Identifier: MPL-2.0
package dev.pontik.cluster.lease;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.pontik.cluster.fault.Code;
import dev.pontik.cluster.fault.RecordingSink;
import dev.pontik.cluster.fault.Sink;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.Collections;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.postgresql.PostgreSQLContainer;

class CoordinatorTest {

    private final Node node = new Node("srv-a", "node-a", "inst-a", "0.1.0-test");
    private final RecordingSink sink = new RecordingSink();
    private final RecordingReconciler reconciler = new RecordingReconciler();

    @BeforeEach
    void emptyDatabase() throws SQLException {
        Pg.reset();
    }

    @Test
    void startNeverThrowsAndStaysOffWhenTheDatabaseIsUnreachable() {
        Coordinator c = coordinator(new DbSettings("postgres", "jdbc:postgresql://127.0.0.1:1/engine",
            "pcl_user", "pw"));

        try {
            c.start();

            assertEquals(Coordinator.State.OFF, c.state());
            assertEquals(1, sink.count(Code.PC107));
            assertTrue(sink.fired.get(0).contains("the database could not be reached (SQLState 08001)"),
                sink.fired.get(0));
            assertFalse(sink.fired.get(0).contains("127.0.0.1"));
            assertFalse(sink.fired.get(0).contains("pcl_user"));
            assertNull(c.keeper());
        } finally {
            c.stop();
        }
    }

    @Test
    void aDatabaseThatIsNotPostgresqlGivesPc113() {
        Coordinator c = coordinator(new DbSettings("mysql", "jdbc:mysql://db/engine", "u", "p"));

        try {
            c.start();

            assertEquals(Coordinator.State.OFF, c.state());
            assertEquals(1, sink.count(Code.PC113));
        } finally {
            c.stop();
        }
    }

    @Test
    void aBootstrapRefusalGivesPc107WithWhatToDo() throws SQLException {
        Pg.restrictedRole();
        Coordinator c = coordinator(Pg.restricted());

        try {
            c.start();

            assertEquals(Coordinator.State.OFF, c.state());
            assertEquals(1, sink.count(Code.PC107));
            assertTrue(sink.fired.get(0).contains("Please run the schema script from the README"), sink.fired.get(0));
        } finally {
            c.stop();
        }
    }

    @Test
    void aLoginRefusalGivesPc107ThatNamesNeitherTheUserNorTheUrl() {
        DbSettings good = Pg.settings();
        Coordinator c = coordinator(new DbSettings("postgres", good.url, good.user, "not-the-password"));

        try {
            c.start();

            assertEquals(Coordinator.State.OFF, c.state());
            assertEquals(1, sink.count(Code.PC107));
            assertEquals("[PONTIK-CLUSTER] " + Code.PC107.label() + " on node-a (srv-a): "
                + c.messages().pc107Failed("the database refused the login (SQLState 28P01)"), sink.fired.get(0));
        } finally {
            c.stop();
        }
    }

    @Test
    void aSetupStatementPastItsLimitGivesPc107ThatNamesNoCauseItCannotKnow() throws SQLException {
        try (Connection other = Pg.admin()) {
            // Another session holds the setup lock, so the first setup statement waits out its limit.
            other.setAutoCommit(false);
            try (PreparedStatement lock = other.prepareStatement("SELECT pg_advisory_xact_lock(?)")) {
                lock.setLong(1, Bootstrap.LOCK_KEY);
                lock.execute();
            }
            Coordinator c = coordinator(Pg.settings());

            try {
                c.start();

                assertEquals(Coordinator.State.OFF, c.state());
                assertEquals("[PONTIK-CLUSTER] " + Code.PC107.label() + " on node-a (srv-a): "
                    + c.messages().pc107Canceled(), sink.fired.get(0));
            } finally {
                c.stop();
                other.rollback();
            }
        }
    }

    @Test
    void startRegistersTheNodeAndLoadsTheConfigBeforeItReturnsAndItsLineCarriesNoSecrets() throws SQLException {
        Pg.execute("DO $$ BEGIN IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'pcl_owner_user') THEN "
            + "CREATE ROLE pcl_owner_user LOGIN PASSWORD 'pcl-secret-pw'; END IF; END $$",
            "GRANT CREATE ON SCHEMA public TO pcl_owner_user");
        DbSettings db = new DbSettings("postgres", Pg.url(), "pcl_owner_user", "pcl-secret-pw");
        Pg.bootstrap(Pg.settings());
        Pg.execute("GRANT SELECT, INSERT, UPDATE, DELETE ON pcl_node, pcl_lease, pcl_channel_config TO pcl_owner_user");
        try (Link link = new Link(Connections.second(Pg.settings()))) {
            new ConfigWriter(link).setMode("ch-1", Store.SINGLE_OWNER, false, "test");
        }
        Coordinator c = coordinator(db);

        c.start();
        try {
            assertEquals(Coordinator.State.RUNNING, c.state());
            assertNotNull(c.keeper().readBack().rows.get("ch-1"));
            assertEquals("node-a", Pg.text("SELECT node_name FROM pcl_node WHERE node_id = 'srv-a'"));

            String line = sink.info.get(0);
            assertTrue(line.startsWith("Pontik Cluster 0.1.0-test on node-a (server ID srv-a, instance inst-a): "
                + "RUNNING;"), line);
            assertTrue(line.contains("lease 30000 ms, heartbeat 10000 ms, fence margin 6000 ms"), line);
            assertFalse(line.contains("pcl_owner_user"));
            assertFalse(line.contains("pcl-secret-pw"));
            assertFalse(line.contains("jdbc:"));
        } finally {
            c.stop();
        }
    }

    @Test
    void stopReleasesEveryLeaseAndMarksTheNodeGone() throws Exception {
        Coordinator c = runningWithOneLease(Pg.settings());
        try {
            c.stop();

            assertNull(Pg.text("SELECT owner_node_id FROM pcl_lease WHERE channel_id = 'ch-1'"));
            assertTrue(Pg.bool("SELECT last_seen_at < clock_timestamp() AT TIME ZONE 'UTC' "
                + "- interval '29 seconds' FROM pcl_node WHERE node_id = 'srv-a'"));
            assertTrue(sink.info.contains("Released the lease on Orders: shutting down."));
            assertTrue(sink.warnings.isEmpty(), sink.warnings.toString());

            // The engine may stop us twice, and the second stop must change nothing.
            c.stop();
            assertTrue(sink.warnings.isEmpty(), sink.warnings.toString());
            assertNull(Pg.text("SELECT owner_node_id FROM pcl_lease WHERE channel_id = 'ch-1'"));
        } finally {
            c.stop();
        }
    }

    @Test
    void stopReturnsWithinTenSecondsWhenTheDatabaseIsDown() throws Exception {
        PostgreSQLContainer own = Pg.start();
        try {
            DbSettings db = new DbSettings("postgres", own.getJdbcUrl(), own.getUsername(), own.getPassword());
            Coordinator c = runningWithOneLease(db);
            try {
                own.stop();

                long started = System.nanoTime();
                c.stop();

                assertTrue(System.nanoTime() - started < TimeUnit.SECONDS.toNanos(10));
                assertEquals(Collections.singletonList("The release at shutdown failed, so the leases expire on "
                    + "their own within one lease. null"), sink.warnings);
            } finally {
                c.stop();
            }
        } finally {
            own.stop();
        }
    }

    @Test
    void aKeeperThatDoesNotStopInTimeKeepsItsLeases() throws Exception {
        CountDownLatch blocked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try (Link link = new Link(Connections.second(Pg.settings()))) {
            Bootstrap.run(link.get());
            new ConfigWriter(link).setMode("ch-1", Store.SINGLE_OWNER, false, "test");
        }
        Coordinator c = new Coordinator(Pg.settings(), node, Timing.STANDARD, new HoldingSink(sink, blocked, release),
            reconciler);
        c.local().deployed("ch-1", "ch-1");
        c.local().verdict("ch-1", null, true);
        try {
            c.start();
            assertTrue(blocked.await(15, TimeUnit.SECONDS), "the keeper reached its acquisition line");

            long started = System.nanoTime();
            c.stop();
            long tookMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);

            assertEquals("srv-a", Pg.text("SELECT owner_node_id FROM pcl_lease WHERE channel_id = 'ch-1'"));
            assertTrue(sink.warnings.contains(
                "The lease keeper did not stop in time, so its leases expire on their own within one lease. null"),
                sink.warnings.toString());
            // stop() waits the statement budget plus one second for the keeper's thread, then gives up.
            assertTrue(tookMs >= 5_000, tookMs + " ms");
            assertTrue(tookMs < 20_000, tookMs + " ms");
        } finally {
            release.countDown();
            c.stop();
        }
    }

    private Coordinator runningWithOneLease(DbSettings db) throws Exception {
        try (Link link = new Link(Connections.second(db))) {
            Bootstrap.run(link.get());
            new ConfigWriter(link).setMode("ch-1", Store.SINGLE_OWNER, false, "test");
        }
        Coordinator c = coordinator(db);
        c.local().deployed("ch-1", "Orders");
        c.local().verdict("ch-1", null, true);
        c.start();
        try {
            assertEquals(Coordinator.State.RUNNING, c.state());

            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (c.ownership().get("ch-1") == null && System.nanoTime() < deadline) {
                Thread.sleep(50);
            }
            assertNotNull(c.ownership().get("ch-1"), "the keeper acquired ch-1");
        } catch (Throwable t) {
            // The caller never gets the coordinator, so we stop it here.
            c.stop();
            throw t;
        }
        return c;
    }

    private Coordinator coordinator(DbSettings db) {
        return new Coordinator(db, node, Timing.STANDARD, sink, reconciler);
    }

    /** Holds the keeper's thread on its first acquisition line, and ignores every interrupt, until released. */
    private static final class HoldingSink implements Sink {

        private final RecordingSink inner;
        private final CountDownLatch blocked;
        private final CountDownLatch release;

        HoldingSink(RecordingSink inner, CountDownLatch blocked, CountDownLatch release) {
            this.inner = inner;
            this.blocked = blocked;
            this.release = release;
        }

        @Override
        public void fired(Code code, String channelId, String line) {
            inner.fired(code, channelId, line);
        }

        @Override
        public void cleared(String line) {
            inner.cleared(line);
        }

        @Override
        public void info(String line) {
            if (line.startsWith("Acquired the lease")) {
                blocked.countDown();
                while (release.getCount() > 0) {
                    try {
                        release.await();
                    } catch (InterruptedException ignored) {
                        // The test is holding this thread on purpose.
                    }
                }
            }
            inner.info(line);
        }

        @Override
        public void warn(String line, Throwable cause) {
            inner.warn(line, cause);
        }
    }
}
