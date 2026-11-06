// SPDX-License-Identifier: MPL-2.0
package dev.pontik.cluster.lease;

import static dev.pontik.cluster.lease.Pg.bool;
import static dev.pontik.cluster.lease.Pg.number;
import static dev.pontik.cluster.lease.Pg.text;
import static dev.pontik.cluster.lease.Pg.time;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.TimeZone;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class JdbcStoreTest {

    private static final long LEASE = Timing.STANDARD.leaseMs;
    private static final long WINDOW = Timing.STANDARD.peerWindowMs();

    private final Node a = new Node("srv-a", "node-a", "inst-a", "test");
    private final Node b = new Node("srv-b", "node-b", "inst-b", "test");
    private Link linkA;
    private Link linkB;
    private JdbcStore storeA;
    private JdbcStore storeB;

    @BeforeEach
    void tables() throws SQLException {
        Pg.reset();
        Pg.bootstrap(Pg.settings());
        linkA = new Link(Pg.keeper());
        linkB = new Link(Pg.keeper());
        storeA = new JdbcStore(linkA, a, 0);
        storeB = new JdbcStore(linkB, b, 0);
    }

    @AfterEach
    void close() {
        linkA.close();
        linkB.close();
    }

    @Test
    void theRenewalWritesStoppedFromItsThirdArray() throws SQLException {
        protect("ch-1");
        long epoch = acquireOne(storeA, "ch-1");

        renew(storeA, "ch-1", epoch, true);
        assertTrue(bool("SELECT stopped FROM pcl_lease WHERE channel_id = 'ch-1'"));
        renew(storeA, "ch-1", epoch, false);
        assertFalse(bool("SELECT stopped FROM pcl_lease WHERE channel_id = 'ch-1'"));
    }

    @Test
    void theAcquisitionLeavesStoppedAsTheRowHasIt() throws SQLException {
        protect("ch-1");
        Pg.execute("UPDATE pcl_lease SET stopped = true WHERE channel_id = 'ch-1'");

        List<Store.Acquired> got = storeA.acquire(new String[] {"ch-1"}, 1, LEASE);

        assertEquals(1, got.size());
        assertTrue(got.get(0).stopped);
        assertTrue(bool("SELECT stopped FROM pcl_lease WHERE channel_id = 'ch-1'"));
    }

    @Test
    void theReleaseClearsOwnerAcquiredAtAndStoppedAndKeepsTheExpiry() throws SQLException {
        protect("ch-1");
        long epoch = acquireOne(storeA, "ch-1");
        renew(storeA, "ch-1", epoch, true);
        LocalDateTime expiry = time("SELECT lease_expires_at FROM pcl_lease WHERE channel_id = 'ch-1'");

        List<String> released = storeA.release(new String[] {"ch-1"}, new Long[] {epoch});

        assertEquals(Collections.singletonList("ch-1"), released);
        assertNull(text("SELECT owner_node_id FROM pcl_lease WHERE channel_id = 'ch-1'"));
        assertNull(time("SELECT acquired_at FROM pcl_lease WHERE channel_id = 'ch-1'"));
        assertFalse(bool("SELECT stopped FROM pcl_lease WHERE channel_id = 'ch-1'"));
        assertEquals(epoch + 1, number("SELECT epoch FROM pcl_lease WHERE channel_id = 'ch-1'"));
        assertEquals(expiry, time("SELECT lease_expires_at FROM pcl_lease WHERE channel_id = 'ch-1'"));
    }

    @Test
    void theReleaseIsFencedRowByRowInsideOneBatch() throws SQLException {
        protect("ch-1", "ch-2");
        long one = acquireOne(storeA, "ch-1");
        long two = acquireOne(storeA, "ch-2");

        List<String> released = storeA.release(new String[] {"ch-1", "ch-2"}, new Long[] {one, two + 5});
        assertEquals(Collections.singletonList("ch-1"), released);
        assertEquals("srv-a", text("SELECT owner_node_id FROM pcl_lease WHERE channel_id = 'ch-2'"));

        assertEquals(0, storeB.release(new String[] {"ch-2"}, new Long[] {two}).size());
        assertEquals("srv-a", text("SELECT owner_node_id FROM pcl_lease WHERE channel_id = 'ch-2'"));
    }

    @Test
    void theRenewalNeverBringsAnExpiredLeaseBack() throws SQLException {
        protect("ch-1");
        long epoch = acquireOne(storeA, "ch-1");
        Pg.execute("UPDATE pcl_lease SET lease_expires_at = lease_expires_at - interval '1 hour'");
        LocalDateTime expired = time("SELECT lease_expires_at FROM pcl_lease WHERE channel_id = 'ch-1'");

        assertEquals(0, renew(storeA, "ch-1", epoch, false).size());
        assertEquals(expired, time("SELECT lease_expires_at FROM pcl_lease WHERE channel_id = 'ch-1'"));
    }

    @Test
    void theRenewalNeverRenewsUnderAnEpochThisProcessDoesNotHold() throws SQLException {
        protect("ch-1");
        long epoch = acquireOne(storeA, "ch-1");

        assertEquals(0, renew(storeA, "ch-1", epoch + 1, false).size());
        assertEquals(0, renew(storeB, "ch-1", epoch, false).size());
    }

    @Test
    void theReadBackReturnsConfigAndLeaseTogetherAndNullsForAMissingLeaseRow() throws SQLException {
        protect("ch-1");
        long epoch = acquireOne(storeA, "ch-1");
        Pg.execute("INSERT INTO pcl_channel_config VALUES ('ch-2', 'single-owner', true, now(), 'test')");

        Store.ReadBack rb = storeA.readBack();

        Store.Row one = rb.rows.get("ch-1");
        assertEquals("single-owner", one.mode);
        assertEquals("srv-a", one.owner);
        assertEquals(Long.valueOf(epoch), one.epoch);
        assertNotNull(one.acquiredAt);
        assertTrue(one.held(rb.dbNow));

        Store.Row two = rb.rows.get("ch-2");
        assertTrue(two.rereadable);
        assertFalse(two.hasLease());
        assertNull(two.owner);
        assertNull(two.epoch);
        assertNull(two.stopped);
        assertNotNull(rb.dbNow);
    }

    @Test
    void theLeaseInsertRecreatesAMissingRowAndClearsStoppedOnlyOnARowNobodyHolds() throws SQLException {
        storeA.insertLease("ch-1");
        assertEquals(0, number("SELECT epoch FROM pcl_lease WHERE channel_id = 'ch-1'"));
        assertNull(text("SELECT owner_node_id FROM pcl_lease WHERE channel_id = 'ch-1'"));

        Pg.execute("UPDATE pcl_lease SET stopped = true");
        storeA.insertLease("ch-1");
        assertFalse(bool("SELECT stopped FROM pcl_lease WHERE channel_id = 'ch-1'"));

        protect("ch-1");
        acquireOne(storeA, "ch-1");
        Pg.execute("UPDATE pcl_lease SET stopped = true");
        storeB.insertLease("ch-1");
        assertTrue(bool("SELECT stopped FROM pcl_lease WHERE channel_id = 'ch-1'"));
    }

    @Test
    void theNodeUpsertReturnsThePreviousInstance() throws SQLException {
        assertNull(storeA.upsertNode());
        assertEquals("inst-a", storeA.upsertNode());

        JdbcStore restarted = new JdbcStore(linkA, new Node("srv-a", "node-a", "inst-a2", "test"), 0);
        assertEquals("inst-a", restarted.upsertNode());
        assertEquals("inst-a2", storeA.upsertNode());
    }

    @Test
    void thePeersCarryTheirNamesAndTheNodeMarkerTakesANodeOut() throws SQLException {
        storeA.upsertNode();
        storeB.upsertNode();

        List<Store.Peer> peers = storeA.peers(WINDOW);
        assertEquals(1, peers.size());
        assertEquals("srv-b", peers.get(0).nodeId);
        assertEquals("node-b", peers.get(0).nodeName);
        assertEquals(Node.PROTOCOL, peers.get(0).protocol);

        new JdbcStore(linkB, new Node("srv-b", "node-b", "another-instance", "test"), 0).markGone(WINDOW);
        assertEquals(1, storeA.peers(WINDOW).size());

        storeB.markGone(WINDOW);
        assertEquals(0, storeA.peers(WINDOW).size());
    }

    @Test
    void everyStampFallsBetweenSendAndReplyOnTheDatabaseClock() throws SQLException {
        protect("ch-1");

        LocalDateTime before = dbNow();
        long epoch = acquireOne(storeA, "ch-1");
        LocalDateTime after = dbNow();
        assertBetween(before, after, time("SELECT acquired_at FROM pcl_lease"));
        assertBetween(before.plusNanos(LEASE * 1_000_000), after.plusNanos(LEASE * 1_000_000),
            time("SELECT lease_expires_at FROM pcl_lease"));

        before = dbNow();
        renew(storeA, "ch-1", epoch, false);
        after = dbNow();
        assertBetween(before.plusNanos(LEASE * 1_000_000), after.plusNanos(LEASE * 1_000_000),
            time("SELECT lease_expires_at FROM pcl_lease"));

        before = dbNow();
        storeA.upsertNode();
        after = dbNow();
        assertBetween(before, after, time("SELECT last_seen_at FROM pcl_node"));

        before = dbNow();
        storeA.markGone(WINDOW);
        after = dbNow();
        assertBetween(before.minusNanos(WINDOW * 1_000_000), after.minusNanos(WINDOW * 1_000_000),
            time("SELECT last_seen_at FROM pcl_node"));

        before = dbNow();
        storeA.insertLease("ch-2");
        after = dbNow();
        assertBetween(before, after, time("SELECT lease_expires_at FROM pcl_lease WHERE channel_id = 'ch-2'"));

        before = dbNow();
        new ConfigWriter(linkB).setMode("ch-3", Store.SINGLE_OWNER, false, "1");
        after = dbNow();
        assertBetween(before, after, time("SELECT updated_at FROM pcl_channel_config WHERE channel_id = 'ch-3'"));
    }

    @Test
    void theStampsAreInUtcWhateverTheSessionTimeZone() throws SQLException {
        TimeZone zone = TimeZone.getDefault();
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("Pacific/Kiritimati"));
            Pg.execute("ALTER DATABASE test SET timezone = 'Pacific/Kiritimati'");
            try (Link far = new Link(Pg.keeper())) {
                protect("ch-1");
                try (PreparedStatement s = far.get().prepareStatement("SHOW TimeZone");
                        ResultSet rs = s.executeQuery()) {
                    assertTrue(rs.next());
                    assertEquals("Pacific/Kiritimati", rs.getString(1));
                }
                new JdbcStore(far, a, 0).acquire(new String[] {"ch-1"}, 1, LEASE);

                LocalDateTime acquired = time("SELECT acquired_at FROM pcl_lease");
                LocalDateTime utc = LocalDateTime.ofInstant(java.time.Instant.now(), java.time.ZoneOffset.UTC);
                assertTrue(Math.abs(java.time.Duration.between(acquired, utc).getSeconds()) < 120,
                    acquired + " vs " + utc);
            }
        } finally {
            try {
                Pg.execute("ALTER DATABASE test RESET timezone");
            } finally {
                TimeZone.setDefault(zone);
            }
        }
    }

    @Test
    void aTimestampInsideADaylightSavingGapReadsBackUnchanged() throws SQLException {
        TimeZone zone = TimeZone.getDefault();
        TimeZone.setDefault(TimeZone.getTimeZone("Europe/Paris"));
        try (Link paris = new Link(Pg.keeper())) {
            protect("ch-1");
            Pg.execute("UPDATE pcl_lease SET lease_expires_at = '2026-03-29 02:30:00' WHERE channel_id = 'ch-1'");

            Store.Row row = new JdbcStore(paris, a, 0).readBack().rows.get("ch-1");

            assertEquals(LocalDateTime.of(2026, 3, 29, 2, 30), row.expiresAt);
            assertEquals("2026-03-29T02:30:00Z", Times.iso(row.expiresAt));
        } finally {
            TimeZone.setDefault(zone);
        }
    }

    @Test
    void setModeWritesTheLeaseRowBeforeTheConfigRowAndRemoveKeepsTheLeaseRow() throws SQLException {
        ConfigWriter writer = new ConfigWriter(linkB);

        writer.setMode("ch-1", Store.SINGLE_OWNER, true, "1");
        assertEquals(1, number("SELECT count(*) FROM pcl_lease WHERE channel_id = 'ch-1'"));
        assertTrue(time("SELECT lease_expires_at FROM pcl_lease")
            .compareTo(time("SELECT updated_at FROM pcl_channel_config")) <= 0);
        assertEquals("1", text("SELECT updated_by FROM pcl_channel_config"));

        writer.setMode("ch-1", Store.UNMANAGED, false, "2");
        assertEquals("unmanaged", text("SELECT mode FROM pcl_channel_config"));

        writer.remove("ch-1");
        assertEquals(0, number("SELECT count(*) FROM pcl_channel_config"));
        assertEquals(1, number("SELECT count(*) FROM pcl_lease"));
    }

    @Test
    void aChannelDeletedAndProtectedAgainNeverHandsOutAnEpochTwice() throws SQLException {
        ConfigWriter writer = new ConfigWriter(linkB);
        Node twin = new Node("srv-a", "node-a", "inst-twin", "test");
        protect("ch-1");
        long held = acquireOne(storeA, "ch-1");

        // The channel is deleted and imported again under the same ID, then a second process
        // under the same server ID takes it once the row runs out.
        writer.remove("ch-1");
        writer.setMode("ch-1", Store.SINGLE_OWNER, false, "1");
        Pg.execute("UPDATE pcl_lease SET lease_expires_at = lease_expires_at - interval '1 hour'");
        long taken = acquireOne(new JdbcStore(linkB, twin, 0), "ch-1");

        assertTrue(taken > held, taken + " after " + held);
        assertEquals(0, renew(storeA, "ch-1", held, false).size());
    }

    @Test
    void setModeForgetsAStopOnlyOnARowNobodyHolds() throws SQLException {
        ConfigWriter writer = new ConfigWriter(linkB);
        protect("ch-1");
        acquireOne(storeA, "ch-1");
        Pg.execute("UPDATE pcl_lease SET stopped = true");

        writer.setMode("ch-1", Store.SINGLE_OWNER, false, "1");
        assertTrue(bool("SELECT stopped FROM pcl_lease"));

        Pg.execute("UPDATE pcl_lease SET lease_expires_at = lease_expires_at - interval '1 hour'");
        writer.setMode("ch-1", Store.SINGLE_OWNER, false, "1");
        assertFalse(bool("SELECT stopped FROM pcl_lease"));
    }

    @Test
    void aWriteThatLosesItsConnectionReopensAndRunsOnce() throws SQLException {
        ConfigWriter writer = new ConfigWriter(linkB);
        writer.setMode("ch-1", Store.UNMANAGED, false, "1");
        Pg.execute("SELECT pg_terminate_backend(pid) FROM pg_stat_activity "
            + "WHERE application_name = 'pontik-cluster' AND pid <> pg_backend_pid()");

        writer.setMode("ch-1", Store.SINGLE_OWNER, false, "1");

        assertEquals("single-owner", text("SELECT mode FROM pcl_channel_config"));
    }

    private void protect(String... channelIds) throws SQLException {
        ConfigWriter writer = new ConfigWriter(linkB);
        for (String id : channelIds) {
            writer.setMode(id, Store.SINGLE_OWNER, false, "test");
        }
    }

    private static long acquireOne(Store store, String channelId) throws SQLException {
        List<Store.Acquired> got = store.acquire(new String[] {channelId}, 1, LEASE);
        assertEquals(1, got.size(), "acquired " + channelId);
        return got.get(0).epoch;
    }

    private static List<Store.Renewed> renew(Store store, String channelId, long epoch, boolean stopped)
            throws SQLException {
        return store.renew(new String[] {channelId}, new Long[] {epoch}, new Boolean[] {stopped}, LEASE);
    }

    private static void assertBetween(LocalDateTime low, LocalDateTime high, LocalDateTime value) {
        assertTrue(!value.isBefore(low) && !value.isAfter(high), low + " <= " + value + " <= " + high);
    }

    static LocalDateTime dbNow() throws SQLException {
        return time("SELECT clock_timestamp() AT TIME ZONE 'UTC'");
    }
}
