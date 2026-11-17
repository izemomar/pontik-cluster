// SPDX-License-Identifier: MPL-2.0
package dev.pontik.cluster.lease;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.pontik.cluster.fault.Code;
import dev.pontik.cluster.fault.Faults;
import dev.pontik.cluster.fault.FixedNames;
import dev.pontik.cluster.fault.Messages;
import dev.pontik.cluster.fault.RecordingSink;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Keepers driven by hand against PostgreSQL. Expiry is set in the rows, so no test waits for a lease. */
class KeeperDatabaseTest {

    /** One keeper with everything it needs, on its own connection. */
    private static final class Member {
        final FakeClock clock = new FakeClock();
        final Node node;
        final Link link;
        final RecordingSink sink = new RecordingSink();
        final RecordingReconciler reconciler = new RecordingReconciler();
        final Ownership ownership = new Ownership(reconciler, clock);
        final LocalState local = new LocalState(Timing.STANDARD);
        final Keeper keeper;
        final Set<String> deployed = new HashSet<>();

        Member(String id, String instance) throws SQLException {
            node = new Node(id, "node-" + id, instance, "test");
            link = new Link(Pg.keeper());
            FixedNames names = new FixedNames(id, node.name);
            Faults faults = new Faults(sink, names);
            Messages messages = new Messages(24, 90, 30);
            Fence fence = new Fence(ownership, new ManualTimer(clock), clock, faults, messages, Timing.STANDARD, names,
                sink);
            keeper = new Keeper(new JdbcStore(link, node, 0), Timing.STANDARD, node, ownership, local, fence, faults,
                messages, names, reconciler, sink, clock, () -> 0L, Store.ReadBack.EMPTY);
        }

        void deploy(List<String> channelIds) {
            for (String id : channelIds) {
                local.deployed(id, id);
                local.verdict(id, null, true);
                deployed.add(id);
            }
        }

        // The undeploy hooks run, then two idle sweeps find the channels gone.
        void undeploy(List<String> channelIds) {
            for (String id : channelIds) {
                local.undeployed(id);
                deployed.remove(id);
            }
            local.swept(clock.getAsLong(), false, deployed);
            local.swept(clock.getAsLong(), false, deployed);
        }

        void round() {
            clock.advanceMs(10_000);
            keeper.tick();
            keeper.acquire();
        }

        Set<String> held() {
            return ownership.snapshot().keySet();
        }
    }

    private static final long VALIDITY_NANOS = TimeUnit.SECONDS.toNanos(24); // a 30 s lease minus the 6 s margin

    private final List<Member> members = new ArrayList<>();

    @BeforeEach
    void tables() throws SQLException {
        Pg.reset();
        Pg.bootstrap(Pg.settings());
    }

    @AfterEach
    void close() {
        for (Member m : members) {
            m.link.close();
        }
    }

    @Test
    void twoKeepersClaimingOneHundredFreeChannelsAtOnceSplitThemWithOneOwnerEach() throws Exception {
        List<String> channels = protect(100);
        Member a = member("srv-a", "inst-a");
        Member b = member("srv-b", "inst-b");
        for (Member m : members) {
            m.deploy(channels);
            new JdbcStore(m.link, m.node, 0).upsertNode();
        }
        a.keeper.tick();
        b.keeper.tick();

        CyclicBarrier together = new CyclicBarrier(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<?>> runs = new ArrayList<>();
            for (Member m : members) {
                runs.add(pool.submit(() -> {
                    together.await();
                    m.keeper.acquire();
                    return null;
                }));
            }
            for (Future<?> run : runs) {
                run.get();
            }
        } finally {
            pool.shutdown();
        }

        assertEquals(50, a.held().size());
        assertEquals(50, b.held().size());
        Set<String> both = new HashSet<>(a.held());
        both.retainAll(b.held());
        assertTrue(both.isEmpty());
        assertEquals(100, owners().size());
    }

    @Test
    void threeKeepersOutOfPhaseTakeEveryChannelOfAFailedPeerWithinOneTickEachAndNoHeldOneMoves() throws Exception {
        List<String> channels = protect(100);
        Member a = member("srv-a", "inst-a");
        Member b = member("srv-b", "inst-b");
        Member c = member("srv-c", "inst-c");
        for (Member m : members) {
            m.deploy(channels);
            new JdbcStore(m.link, m.node, 0).upsertNode();
        }
        assign(a, channels.subList(0, 33));
        assign(b, channels.subList(33, 66));
        assign(c, channels.subList(66, 100));
        a.keeper.tick();
        b.keeper.tick();
        Map<String, String> before = owners();

        // The third node fails: its node row ages out and its leases run out.
        Pg.execute("UPDATE pcl_node SET last_seen_at = last_seen_at - interval '1 minute' WHERE node_id = 'srv-c'",
            "UPDATE pcl_lease SET lease_expires_at = lease_expires_at - interval '1 hour' "
            + "WHERE owner_node_id = 'srv-c'");
        a.round();
        b.round();

        assertEquals(50, a.held().size());
        assertEquals(50, b.held().size());
        Map<String, String> after = owners();
        for (String id : channels.subList(0, 66)) {
            assertEquals(before.get(id), after.get(id), id);
        }
        assertFalse(after.containsValue("srv-c"));
    }

    @Test
    void twoProcessesUnderOneServerIdNeverRenewEachOthersLeases() throws Exception {
        List<String> channels = protect(1);
        Member first = member("srv-a", "inst-1");
        Member second = member("srv-a", "inst-2");
        first.deploy(channels);
        second.deploy(channels);
        String row = "SELECT epoch || ' ' || lease_expires_at::text FROM pcl_lease WHERE channel_id = 'ch-0'";

        first.round();
        assertEquals(Collections.singleton("ch-0"), first.held());

        // The first process stalls past its lease, so the row runs out.
        Pg.execute("UPDATE pcl_lease SET lease_expires_at = lease_expires_at - interval '1 hour'"
            + " WHERE channel_id = 'ch-0'");
        second.round();
        assertEquals(Collections.singleton("ch-0"), second.held());
        String taken = Pg.text(row);
        assertTrue(taken.startsWith("2 "), taken);

        first.round();
        assertEquals(taken, Pg.text(row));
        assertTrue(first.held().isEmpty());

        second.round();
        first.round();
        assertEquals(1, first.sink.count(Code.PC119));
        assertEquals(1, second.sink.count(Code.PC119));
        assertEquals(Collections.singleton("ch-0"), second.held());
        assertTrue(first.held().isEmpty());
    }

    @Test
    void releasingTwentyChannelsMovesThemAllToTheBalancedPeerWithinTwoTicksAndRaisesNoPc103() throws Exception {
        List<String> channels = protect(100);
        Member a = member("srv-a", "inst-a");
        Member b = member("srv-b", "inst-b");
        for (Member m : members) {
            m.deploy(channels);
            new JdbcStore(m.link, m.node, 0).upsertNode();
        }
        assign(a, channels.subList(0, 50));
        assign(b, channels.subList(50, 100));
        b.keeper.tick();

        a.undeploy(channels.subList(0, 20));
        a.round();
        assertEquals(30, a.held().size());

        b.round();
        b.round();

        assertEquals(70, b.held().size());
        assertEquals(0, b.sink.count(Code.PC103));
        assertEquals(0, a.sink.count(Code.PC103));
    }

    @Test
    void aThousandChannelsAreAcquiredAndRenewedWellWithinTheStatementTimeout() throws Exception {
        List<String> channels = protect(1000);
        Member a = member("srv-a", "inst-a");
        a.deploy(channels);

        a.keeper.tick();
        long started = System.nanoTime();
        a.keeper.acquire();
        long acquisitionMs = (System.nanoTime() - started) / 1_000_000;
        assertEquals(1000, a.held().size());
        assertTrue(acquisitionMs < 1000, "the acquisition took " + acquisitionMs + " ms");

        started = System.nanoTime();
        a.round();
        long roundMs = (System.nanoTime() - started) / 1_000_000;

        assertEquals(1000, a.held().size());
        assertTrue(roundMs < 1000, "the round took " + roundMs + " ms");
        // A renewal that failed would leave the acquisition's validity in place.
        for (Held h : a.ownership.snapshot().values()) {
            assertEquals(a.clock.getAsLong() + VALIDITY_NANOS, h.validUntil);
        }
    }

    private Member member(String id, String instance) throws SQLException {
        Member m = new Member(id, instance);
        members.add(m);
        return m;
    }

    private static List<String> protect(int count) throws SQLException {
        List<String> ids = new ArrayList<>();
        try (Link link = new Link(Connections.second(Pg.settings()))) {
            ConfigWriter writer = new ConfigWriter(link);
            for (int i = 0; i < count; i++) {
                writer.setMode("ch-" + i, Store.SINGLE_OWNER, false, "test");
                ids.add("ch-" + i);
            }
        }
        return ids;
    }

    // Gives the member these channels the way a past acquisition would have.
    private static void assign(Member m, List<String> channelIds) throws SQLException {
        for (String id : channelIds) {
            List<Store.Acquired> got = new JdbcStore(m.link, m.node, 0)
                .acquire(new String[] {id}, 1, Timing.STANDARD.leaseMs);
            assertEquals(1, got.size(), id);
            m.ownership.add(id, new Held(got.get(0).epoch, m.clock.getAsLong() + VALIDITY_NANOS));
            m.local.acquired(id, false);
        }
    }

    private static Map<String, String> owners() throws SQLException {
        Map<String, String> owners = new HashMap<>();
        try (Connection c = Pg.admin(); Statement s = c.createStatement();
                ResultSet rs = s.executeQuery(
                    "SELECT channel_id, owner_node_id FROM pcl_lease WHERE owner_node_id IS NOT NULL")) {
            while (rs.next()) {
                owners.put(rs.getString(1), rs.getString(2));
            }
        }
        return owners;
    }
}
