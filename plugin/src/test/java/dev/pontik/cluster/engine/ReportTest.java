// SPDX-License-Identifier: MPL-2.0
package dev.pontik.cluster.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mirth.connect.donkey.model.channel.DeployedState;
import dev.pontik.cluster.api.ChannelView;
import dev.pontik.cluster.api.NodeView;
import dev.pontik.cluster.fault.RecordingSink;
import dev.pontik.cluster.lease.Node;
import dev.pontik.cluster.lease.Rig;
import dev.pontik.cluster.lease.Timing;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import org.junit.jupiter.api.Test;

class ReportTest {

    private final Deque<Runnable> pending = new ArrayDeque<>();
    private final Lanes lanes = new Lanes(pending::add, new RecordingSink());
    private final Rig rig = new Rig(lanes);
    private final FakeEngine engine = new FakeEngine();
    private final ChannelReconciler reconciler = new ChannelReconciler(engine, rig.ownership, rig.local, rig.faults,
        rig.messages, rig.sink, rig.names, Timing.STANDARD, () -> rig.keeper, rig.clock(), lanes,
        new Targets(rig.sink), id -> true);
    private final Report report = new Report(new Node("srv-a", "node-a", "inst-a", "test"), Timing.STANDARD,
        rig.ownership, rig.local, rig.faults, rig.names, reconciler);

    ReportTest() {
        lanes.bind(reconciler::reconcile);
    }

    @Test
    void aDeployedChannelWithoutAModeIsListedAsUnmanagedWithItsSourceType() {
        deploy("ch-1", DeployedState.STARTED, DeployedState.STARTED);
        sweep();

        ChannelView v = only(report.channels(rig.keeper, rig.clock().getAsLong()));

        assertEquals("ch-1", v.channelId);
        assertEquals("unmanaged", v.mode);
        assertEquals("File Reader", v.sourceType);
        assertEquals(Status.UNMANAGED, v.status);
        assertEquals("none", v.thisNode.role);
    }

    @Test
    void theOwnerReportsItsLeaseWithTimesInUtcAndTheAgeOfTheReadBack() {
        rig.protect("ch-1");
        deploy("ch-1", DeployedState.PAUSED, DeployedState.STOPPED);
        sweep();
        rig.acquire();
        drain();
        rig.tick();
        sweep();

        ChannelView v = only(report.channels(rig.keeper, rig.clock().getAsLong()));

        assertEquals(Status.PROTECTED, v.status);
        assertEquals("this node holds the lease", v.reason);
        assertEquals("srv-a", v.owner);
        assertEquals("node-a", v.ownerName);
        assertNotNull(v.epoch);
        assertTrue(v.leaseExpiresAt.endsWith("Z"), v.leaseExpiresAt);
        assertTrue(v.asOf.endsWith("Z"), v.asOf);
        assertNotNull(v.readBackAgeMs);
        assertEquals("owner", v.thisNode.role);
        assertEquals("STARTED", v.thisNode.sourceState);
    }

    @Test
    void aSourceRunningWithoutALeaseIsUnprotectedAndRaisesNothing() {
        rig.protect("ch-1");
        engine.deploy(Models.file("ch-1"), DeployedState.STARTED, DeployedState.STARTED);
        sweep();

        ChannelView v = only(report.channels(rig.keeper, rig.clock().getAsLong()));

        assertEquals(Status.UNPROTECTED, v.status);
        assertEquals(Status.TAKING_OVER, v.reason);
        assertTrue(v.codes.isEmpty());
        assertTrue(rig.sink.fired.isEmpty());
    }

    @Test
    void aDeclinedNodeReportsEveryProtectedChannelUnprotectedWithPc107() {
        rig.protect("ch-1");
        deploy("ch-1", DeployedState.PAUSED, DeployedState.STOPPED);
        rig.peer("srv-b", "node-b", 2);

        ChannelView v = only(report.channels(rig.keeper, rig.clock().getAsLong()));

        assertEquals(Status.UNPROTECTED, v.status);
        assertEquals("[PC-107]", v.codes.toString());
    }

    @Test
    void theNodeViewNamesThisNodeItsPeersItsLeasesAndItsFaults() {
        rig.protect("ch-1");
        deploy("ch-1", DeployedState.PAUSED, DeployedState.STOPPED);
        sweep();
        rig.acquire();
        drain();
        rig.peer("srv-b", "node-b", 1);

        NodeView v = report.node("RUNNING", rig.keeper, rig.clock().getAsLong());

        assertEquals("node-a", v.nodeName);
        assertEquals("inst-a", v.instanceId);
        assertEquals("RUNNING", v.state);
        assertNull(v.code);
        assertEquals(2, v.timing.tolerance);
        assertEquals("node-b", v.peers.get(0).nodeName);
        assertEquals("ch-1", v.leases.get(0).channelId);
        assertTrue(v.leases.get(0).validForMs > 0);
        assertNotNull(v.lastSweepAgeMs);
    }

    @Test
    void anOffNodeViewCarriesNoKeeperFacts() {
        NodeView v = report.node("OFF", null, rig.clock().getAsLong());

        assertEquals("OFF", v.state);
        assertNull(v.lastTickAgeMs);
        assertTrue(v.peers.isEmpty());
    }

    private void deploy(String id, DeployedState channel, DeployedState source) {
        engine.deploy(Models.file(id), channel, source);
        reconciler.deployed(Models.file(id));
    }

    private void sweep() {
        reconciler.sweep();
        drain();
    }

    private void drain() {
        while (!pending.isEmpty()) {
            pending.poll().run();
        }
    }

    private static ChannelView only(List<ChannelView> views) {
        assertEquals(1, views.size());
        return views.get(0);
    }
}
