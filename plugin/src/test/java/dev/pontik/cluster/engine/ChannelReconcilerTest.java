// SPDX-License-Identifier: MPL-2.0
package dev.pontik.cluster.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mirth.connect.donkey.model.channel.DeployedState;
import com.mirth.connect.model.Channel;
import dev.pontik.cluster.fault.Code;
import dev.pontik.cluster.fault.RecordingSink;
import dev.pontik.cluster.lease.Rig;
import dev.pontik.cluster.lease.Timing;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class ChannelReconcilerTest {

    private final Deque<Runnable> pending = new ArrayDeque<>();
    private final RecordingSink laneSink = new RecordingSink();
    private final FakeEngine engine = new FakeEngine();
    private final List<String> paused = new ArrayList<>();
    private boolean gateFindsCopy = true;

    private final Lanes lanes;
    private final Rig rig;
    private final Targets targets;
    private ChannelReconciler reconciler;

    ChannelReconcilerTest() {
        lanes = new Lanes(pending::add, laneSink);
        rig = new Rig(lanes);
        targets = new Targets(rig.sink);
        reconciler = reconciler(id -> {
            paused.add(id);
            return gateFindsCopy;
        });
    }

    // The deploy hook

    @Test
    void theHookPausesAProtectedChannelThisNodeDoesNotOwnAndMarksIt() {
        rig.protect("ch-1");

        deploy(Models.file("ch-1"), DeployedState.PAUSED, DeployedState.STOPPED);

        assertEquals(1, paused.size());
        assertTrue(rig.local.weStopped("ch-1"));
    }

    @Test
    void theHookLeavesAChannelConfiguredPausedOrUnprotectedAlone() {
        rig.protect("ch-1");
        Channel configuredPaused = Models.file("ch-1");
        configuredPaused.getProperties().setInitialState(DeployedState.PAUSED);

        deploy(configuredPaused, DeployedState.PAUSED, DeployedState.STOPPED);
        deploy(Models.file("ch-2"), DeployedState.STARTED, DeployedState.STARTED);

        assertTrue(paused.isEmpty());
        assertFalse(rig.local.weStopped("ch-1"));
    }

    @Test
    void aRedeployForgetsTheMarksOfThePreviousCopy() {
        rig.protect("ch-1");
        deploy(Models.file("ch-1"), DeployedState.PAUSED, DeployedState.STOPPED);
        rig.faults.raise(Code.PC118, "ch-1", "earlier");
        Channel reconfigured = Models.file("ch-1");
        reconfigured.getProperties().setInitialState(DeployedState.PAUSED);

        deploy(reconfigured, DeployedState.PAUSED, DeployedState.STOPPED);

        assertFalse(rig.local.weStopped("ch-1"));
        assertFalse(rig.faults.isFiring(Code.PC118, "ch-1"));
    }

    @Test
    void aRedeployOnTheOwnerKeepsTheSourceStarting() {
        own("ch-1");
        paused.clear();

        deploy(Models.file("ch-1"), DeployedState.STARTED, DeployedState.STARTED);

        assertTrue(paused.isEmpty());
        assertNotNull(rig.ownership.get("ch-1"));
    }

    @Test
    void aRefusedChannelRaisesItsCodeAndStartsAsConfigured() {
        rig.protect("ch-1");

        deploy(Models.javaScript("ch-1"), DeployedState.STARTED, DeployedState.STARTED);

        assertTrue(paused.isEmpty());
        assertTrue(rig.faults.isFiring(Code.PC114, "ch-1"));
        assertFalse(rig.local.eligible("ch-1", rig.clock().getAsLong()));
    }

    @Test
    void aMissingGateRefusesEveryProtectedChannelWithoutAFaultPerChannel() {
        reconciler = reconciler(null);
        rig.protect("ch-1");

        deploy(Models.file("ch-1"), DeployedState.STARTED, DeployedState.STARTED);

        assertEquals("PC-116", rig.local.refusal("ch-1"));
        assertFalse(rig.faults.isFiring(Code.PC116, "ch-1"));
    }

    @Test
    void aGateThatFindsNoCopyRefusesTheChannelWithPc116() {
        gateFindsCopy = false;
        rig.protect("ch-1");

        deploy(Models.file("ch-1"), DeployedState.STARTED, DeployedState.STARTED);

        assertTrue(rig.faults.isFiring(Code.PC116, "ch-1"));
        assertFalse(rig.local.weStopped("ch-1"));
    }

    @Test
    void aGateThatThrowsLeavesNoMarkAndTheChannelStartsAsConfigured() {
        reconciler = reconciler(id -> {
            throw new IllegalStateException("gate");
        });
        rig.protect("ch-1");

        deploy(Models.file("ch-1"), DeployedState.STARTED, DeployedState.STARTED);

        assertFalse(rig.local.weStopped("ch-1"));
        assertEquals(1, rig.sink.warnings.stream().filter(w -> w.startsWith("The deploy hook failed")).count());
    }

    @Test
    void aTargetAlreadyInTheWriterSetAtItsDeployIsRefusedAndNeverGated() {
        rig.protect("ch-1");
        engine.deploy(Models.writingTo(Models.file("router"), "ch-1"), DeployedState.STARTED, DeployedState.STARTED);
        sweep();

        deploy(Models.file("ch-1"), DeployedState.STARTED, DeployedState.STARTED);

        assertTrue(rig.faults.isFiring(Code.PC115, "ch-1"));
        assertTrue(paused.isEmpty());
        assertFalse(rig.local.weStopped("ch-1"));
    }

    @Test
    void theHookDoesNothingOnANodeThatIsNotRunning() {
        rig.protect("ch-1");
        reconciler = new ChannelReconciler(engine, rig.ownership, rig.local, rig.faults, rig.messages, rig.sink,
            rig.names, Timing.STANDARD, () -> null, rig.clock(), lanes, targets, id -> true);

        deploy(Models.file("ch-1"), DeployedState.STARTED, DeployedState.STARTED);

        assertFalse(rig.local.deployedHere("ch-1"));
    }

    @Test
    void aDeployWaitsForAReleaseInProgressSoItNeverCancelsHalfOfOne() throws Exception {
        rig.protect("ch-1");
        Thread hook = new Thread(() -> deploy(Models.file("ch-1"), DeployedState.PAUSED, DeployedState.STOPPED));

        synchronized (rig.local.releaseLock()) {
            hook.start();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (hook.getState() != Thread.State.BLOCKED && System.nanoTime() < deadline) {
                Thread.sleep(5);
            }
            assertEquals(Thread.State.BLOCKED, hook.getState());
            assertFalse(rig.local.deployedHere("ch-1"));
        }
        hook.join(5_000);

        assertTrue(rig.local.deployedHere("ch-1"));
    }

    @Test
    void theHookDoesNothingOnANodeThatDeclined() {
        rig.protect("ch-1");
        rig.peer("srv-b", "node-b", 2);

        deploy(Models.file("ch-1"), DeployedState.STARTED, DeployedState.STARTED);

        assertTrue(paused.isEmpty());
        assertTrue(rig.faults.isFiring(Code.PC107, null));
    }

    // Lanes on the owner

    @Test
    void theOwnerStartsTheSourceItStoppedAndForgetsTheMark() {
        own("ch-1");

        assertEquals(1, engine.count("start ch-1"));
        assertFalse(rig.local.weStopped("ch-1"));
        assertTrue(rig.sink.info.contains("Started ch-1: this node holds the lease."));
    }

    @Test
    void aConnectorWeDidNotStopIsNeverStarted() {
        rig.protect("ch-1");
        Channel configuredPaused = Models.file("ch-1");
        configuredPaused.getProperties().setInitialState(DeployedState.PAUSED);
        deploy(configuredPaused, DeployedState.PAUSED, DeployedState.STOPPED);
        sweep();

        rig.acquire();
        drain();
        sweep();

        assertNotNull(rig.ownership.get("ch-1"));
        assertEquals(0, engine.count("start ch-1"));
    }

    @Test
    void aStartThatDoesNotLandRaisesPc118AndTheLeaseIsReleasedForAPeer() {
        engine.onStart = (e, id) -> e.set(id, DeployedState.PAUSED, DeployedState.STOPPED);

        own("ch-1");
        rig.tick();

        assertTrue(rig.faults.isFiring(Code.PC118, "ch-1"));
        assertTrue(rig.local.weStopped("ch-1"));
        assertNull(rig.ownership.get("ch-1"));
        assertFalse(rig.local.eligible("ch-1", rig.clock().getAsLong()));

        deploy(Models.file("ch-1"), DeployedState.PAUSED, DeployedState.STOPPED);
        assertFalse(rig.faults.isFiring(Code.PC118, "ch-1"));
    }

    @Test
    void aStartStillInTransitionAfterOneLeaseRaisesPc118() {
        engine.onStart = (e, id) -> e.set(id, DeployedState.PAUSING, DeployedState.STOPPED);
        own("ch-1");
        assertFalse(rig.faults.isFiring(Code.PC118, "ch-1"));

        rig.advanceMs(31_000);
        sweep();

        assertTrue(rig.faults.isFiring(Code.PC118, "ch-1"));
    }

    @Test
    void aStartThatFindsTheChannelGoneRaisesNothing() {
        engine.onStart = (e, id) -> e.undeploy(id);

        own("ch-1");

        assertFalse(rig.faults.isFiring(Code.PC118, "ch-1"));
    }

    @Test
    void aLeaseTakenFromARowAnOperatorStoppedLeavesTheSourcePaused() {
        rig.protect("ch-1");
        rig.heldBy("ch-1", "srv-b");
        deploy(Models.file("ch-1"), DeployedState.PAUSED, DeployedState.STOPPED);
        sweep();
        rig.stoppedRow("ch-1");
        rig.expire("ch-1");

        rig.acquire();
        drain();

        assertEquals(0, engine.count("start ch-1"));
        assertFalse(rig.local.weStopped("ch-1"));
        assertTrue(rig.local.operatorStop("ch-1"));
        assertTrue(rig.sink.info.contains("Left ch-1 paused: stopped by an operator on srv-b."));

        sweep();
        assertTrue(rig.local.operatorStop("ch-1"));
        assertEquals(0, engine.count("start ch-1"));
    }

    @Test
    void anOperatorsStopIsDerivedOnlyFromAnIdleSweepThatSeesTheChannelSettled() {
        own("ch-1");
        engine.set("ch-1", DeployedState.PAUSED, DeployedState.STOPPED);
        engine.set("ch-2", DeployedState.DEPLOYING, DeployedState.STOPPED);

        sweep();
        assertFalse(rig.local.operatorStop("ch-1"));

        engine.undeploy("ch-2");
        sweep();
        assertTrue(rig.local.operatorStop("ch-1"));

        engine.set("ch-1", DeployedState.STARTED, DeployedState.STARTED);
        sweep();
        assertFalse(rig.local.operatorStop("ch-1"));
    }

    @Test
    void aChannelConfiguredPausedNeverReadsAsStoppedByAnOperator() {
        rig.protect("ch-1");
        Channel configuredPaused = Models.file("ch-1");
        configuredPaused.getProperties().setInitialState(DeployedState.PAUSED);
        deploy(configuredPaused, DeployedState.PAUSED, DeployedState.STOPPED);
        sweep();
        rig.acquire();
        drain();

        sweep();

        assertFalse(rig.local.operatorStop("ch-1"));
    }

    @Test
    void aStartOverdueWithItsSourceStartedIsJudgedLandedAndKeepsTheLease() {
        engine.onStart = (e, id) -> e.set(id, DeployedState.PAUSING, DeployedState.STARTED);
        own("ch-1");
        for (int i = 0; i < 4; i++) {
            rig.advanceMs(8_000);
            rig.tick();
        }

        sweep();

        assertFalse(rig.faults.isFiring(Code.PC118, "ch-1"));
        assertNotNull(rig.ownership.get("ch-1"));
        assertFalse(rig.local.weStopped("ch-1"));
    }

    @Test
    void aStartOverdueWithItsSourceStillStartingKeepsTheLeaseAndWarnsOnce() {
        engine.onStart = (e, id) -> e.set(id, DeployedState.STARTING, DeployedState.STARTING);
        own("ch-1");
        for (int i = 0; i < 4; i++) {
            rig.advanceMs(8_000);
            rig.tick();
        }

        sweep();
        sweep();

        assertFalse(rig.faults.isFiring(Code.PC118, "ch-1"));
        assertNotNull(rig.ownership.get("ch-1"));
        assertEquals(1, rig.sink.warnings.stream().filter(w -> w.startsWith("The source of ch-1 is still Starting"))
            .count());
    }

    // Lanes on a node that does not own the channel

    @Test
    void aStartOnANonOwnerIsUndoneWithOneInfoLineAndThreeWithinTheWindowRaiseOnePc105() {
        rig.protect("ch-1");
        rig.heldBy("ch-1", "srv-b");
        deploy(Models.file("ch-1"), DeployedState.PAUSED, DeployedState.STOPPED);

        for (int i = 0; i < 3; i++) {
            engine.set("ch-1", DeployedState.STARTED, DeployedState.STARTED);
            lanes.queue("ch-1");
            drain();
            rig.advanceMs(10_000);
        }

        assertEquals(3, engine.count("stop ch-1"));
        assertEquals(1, rig.sink.count(Code.PC105));
        assertTrue(rig.sink.info.stream().anyMatch(l -> l.startsWith("A start on node-a was undone: srv-b owns")));

        rig.advanceMs(91_000);
        sweep();
        assertFalse(rig.faults.isFiring(Code.PC105, "ch-1"));
    }

    @Test
    void aNodeThatNeverHeldTheLeaseLeavesARunningSourceWhileNobodyOwnsIt() {
        rig.protect("ch-1");
        engine.deploy(Models.file("ch-1"), DeployedState.STARTED, DeployedState.STARTED);

        lanes.queue("ch-1");
        drain();

        assertEquals(0, engine.count("stop ch-1"));
    }

    @Test
    void aDemotedOwnerStopsItsSourceAndKeepsTheMarkerUntilThePendingStopLands() {
        own("ch-1");
        engine.onStop = (e, id) -> e.set(id, DeployedState.PAUSING, DeployedState.STARTED);

        rig.advanceMs(25_000);
        drain();

        assertNull(rig.ownership.get("ch-1"));
        assertEquals(1, engine.count("stop ch-1"));
        assertTrue(rig.ownership.mustStop("ch-1"));

        rig.advanceMs(31_000);
        sweep();
        assertTrue(rig.faults.isFiring(Code.PC104, "ch-1"));

        engine.set("ch-1", DeployedState.PAUSED, DeployedState.STOPPED);
        sweep();
        assertFalse(rig.ownership.mustStop("ch-1"));
        assertFalse(rig.faults.isFiring(Code.PC104, "ch-1"));
    }

    @Test
    void aStopThatBlocksForAsLongAsAPollStillRaisesPc104AndClearsWhenItLands() throws Exception {
        own("ch-1");
        engine.stopGate = new CountDownLatch(1);
        engine.onStop = (e, id) -> e.set(id, DeployedState.PAUSED, DeployedState.STOPPED);
        rig.advanceMs(25_000);
        Thread lane = new Thread(this::drain);
        lane.start();
        assertTrue(engine.stopEntered.await(5, TimeUnit.SECONDS));

        rig.advanceMs(31_000);
        reconciler.sweep();
        assertTrue(rig.faults.isFiring(Code.PC104, "ch-1"));

        engine.stopGate.countDown();
        lane.join(5_000);
        assertFalse(lane.isAlive());
        assertFalse(rig.faults.isFiring(Code.PC104, "ch-1"));
        assertFalse(rig.ownership.mustStop("ch-1"));
    }

    @Test
    void aStartThatBlocksWarnsOnceFromTheSweepAndReleasesNothing() throws Exception {
        rig.protect("ch-1");
        deploy(Models.file("ch-1"), DeployedState.PAUSED, DeployedState.STOPPED);
        sweep();
        engine.startGate = new CountDownLatch(1);
        engine.onStart = (e, id) -> e.set(id, DeployedState.STARTING, DeployedState.STARTING);
        rig.acquire();
        Thread lane = new Thread(this::drain);
        lane.start();
        assertTrue(engine.startEntered.await(5, TimeUnit.SECONDS));
        engine.set("ch-1", DeployedState.STARTING, DeployedState.STARTING);
        for (int i = 0; i < 4; i++) {
            rig.advanceMs(8_000);
            rig.tick();
        }

        reconciler.sweep();
        reconciler.sweep();

        assertEquals(1, rig.sink.warnings.stream().filter(w -> w.startsWith("The source of ch-1 is still Starting"))
            .count());
        assertFalse(rig.faults.isFiring(Code.PC118, "ch-1"));
        assertNotNull(rig.ownership.get("ch-1"));
        engine.startGate.countDown();
        lane.join(5_000);
        assertFalse(lane.isAlive());
    }

    @Test
    void aStartMarkLeftByAnUnreadStatusDoesNotSurviveALostLease() {
        rig.protect("ch-1");
        deploy(Models.file("ch-1"), DeployedState.PAUSED, DeployedState.STOPPED);
        sweep();
        engine.onStart = (e, id) -> {
            e.set(id, DeployedState.STARTED, DeployedState.STARTED);
            e.statusFails = true;
        };
        rig.acquire();
        drain();
        engine.statusFails = false;

        rig.advanceMs(25_000);
        drain();
        assertEquals(1, engine.count("stop ch-1"));
        rig.expire("ch-1");
        rig.acquire();
        drain();

        assertFalse(rig.faults.isFiring(Code.PC118, "ch-1"));
        assertNotNull(rig.ownership.get("ch-1"));
        assertEquals(2, engine.count("start ch-1"));
    }

    @Test
    void aLeaseTakenAgainBeforeTheStopRanClearsTheMarkerWithoutAStop() {
        own("ch-1");
        rig.advanceMs(25_000);
        assertTrue(rig.ownership.mustStop("ch-1"));

        rig.expire("ch-1");
        rig.acquire();
        drain();

        assertEquals(0, engine.count("stop ch-1"));
        assertFalse(rig.ownership.mustStop("ch-1"));
    }

    @Test
    void aLaneThatCannotReadTheChannelsStateDoesNothing() {
        rig.protect("ch-1");
        rig.heldBy("ch-1", "srv-b");
        deploy(Models.file("ch-1"), DeployedState.STARTED, DeployedState.STARTED);
        engine.statusFails = true;

        lanes.queue("ch-1");
        drain();

        assertEquals(0, engine.count("stop ch-1"));
        assertTrue(laneSink.warnings.isEmpty());
    }

    @Test
    void aStopThatThrowsRaisesPc104() {
        rig.protect("ch-1");
        rig.heldBy("ch-1", "srv-b");
        deploy(Models.file("ch-1"), DeployedState.STARTED, DeployedState.STARTED);
        engine.onStop = (e, id) -> {
            throw new IllegalStateException("stuck");
        };

        lanes.queue("ch-1");
        drain();

        assertTrue(rig.faults.isFiring(Code.PC104, "ch-1"));
        assertTrue(rig.local.weStopped("ch-1"));
    }

    // Sweeps and hand-backs

    @Test
    void aStoppedChannelLosesOurMarkAndIsNeverStarted() {
        rig.protect("ch-1");
        deploy(Models.file("ch-1"), DeployedState.PAUSED, DeployedState.STOPPED);
        engine.set("ch-1", DeployedState.STOPPED, DeployedState.STOPPED);

        sweep();
        rig.acquire();
        drain();

        assertFalse(rig.local.weStopped("ch-1"));
        assertEquals(0, engine.count("start ch-1"));
    }

    @Test
    void aHandBackStartsWhatWeStoppedOnceAndForgetsTheMarkWhateverTheOutcome() {
        rig.protect("ch-1");
        deploy(Models.file("ch-1"), DeployedState.PAUSED, DeployedState.STOPPED);
        engine.onStart = (e, id) -> {
            throw new IllegalStateException("share gone");
        };

        rig.mode("ch-1", "unmanaged");
        drain();
        sweep();

        assertEquals(1, engine.count("start ch-1"));
        assertFalse(rig.local.weStopped("ch-1"));
    }

    @Test
    void aHandBackNeverStartsAChannelStoppedMeanwhile() {
        rig.protect("ch-1");
        deploy(Models.file("ch-1"), DeployedState.PAUSED, DeployedState.STOPPED);
        engine.set("ch-1", DeployedState.STOPPED, DeployedState.STOPPED);

        rig.mode("ch-1", "unmanaged");
        drain();

        assertEquals(0, engine.count("start ch-1"));
        assertFalse(rig.local.weStopped("ch-1"));
    }

    @Test
    void aDeletedChannelThisNodeHeldIsHandedBackAndRunsAsItDoesWithoutThePlugin() {
        engine.onStart = (e, id) -> e.set(id, DeployedState.PAUSED, DeployedState.STOPPED);
        own("ch-1");
        assertTrue(rig.local.weStopped("ch-1"));
        engine.onStart = (e, id) -> e.set(id, DeployedState.STARTED, DeployedState.STARTED);

        rig.delete("ch-1");
        drain();

        assertNull(rig.ownership.get("ch-1"));
        assertTrue(rig.sink.info.contains("Released the lease on ch-1: the channel was deleted."));
        assertEquals(2, engine.count("start ch-1"));
        assertFalse(rig.local.weStopped("ch-1"));
    }

    @Test
    void anOptInOnADeployedChannelIsJudgedByItsLane() {
        deploy(Models.javaScript("ch-1"), DeployedState.STARTED, DeployedState.STARTED);
        assertFalse(rig.faults.isFiring(Code.PC114, "ch-1"));

        rig.protect("ch-1");
        drain();

        assertTrue(rig.faults.isFiring(Code.PC114, "ch-1"));

        rig.mode("ch-1", "unmanaged");
        drain();
        assertFalse(rig.faults.isFiring(Code.PC114, "ch-1"));
    }

    @Test
    void aWriterDeployedLaterQueuesItsProtectedTargetWhichItsLaneRefuses() {
        rig.protect("ch-1");
        deploy(Models.file("ch-1"), DeployedState.PAUSED, DeployedState.STOPPED);

        deploy(Models.writingTo(Models.file("router"), "ch-1"), DeployedState.STARTED, DeployedState.STARTED);
        drain();

        assertTrue(rig.faults.isFiring(Code.PC115, "ch-1"));
        assertEquals(1, engine.count("start ch-1"));
    }

    @Test
    void aDisabledWriterCountsOnlyWhileItIsDeployedHere() {
        rig.protect("ch-1");
        deploy(Models.file("ch-1"), DeployedState.PAUSED, DeployedState.STOPPED);
        engine.models.put("router", Models.writingTo(Models.file("router"), "ch-1"));
        engine.disabled.add("router");

        sweep();
        drain();
        assertNull(targets.writer("ch-1"));

        engine.set("router", DeployedState.STARTED, DeployedState.STARTED);
        sweep();
        drain();
        assertEquals("name of router", targets.writer("ch-1"));
        assertTrue(rig.faults.isFiring(Code.PC115, "ch-1"));

        engine.undeploy("router");
        sweep();
        drain();
        assertNull(targets.writer("ch-1"));
        assertFalse(rig.faults.isFiring(Code.PC115, "ch-1"));
    }

    @Test
    void aSweepReadsTheEnabledFlagsOnceForAllItsChannels() {
        for (String id : new String[] {"ch-1", "ch-2", "ch-3"}) {
            engine.models.put(id, Models.file(id));
        }

        sweep();

        assertEquals(1, engine.metadataReads);
    }

    @Test
    void anUndeployedChannelIsReleasedOnlyAfterTwoIdleSweepsFoundItGone() {
        own("ch-1");
        engine.undeploy("ch-1");
        reconciler.undeployed("ch-1");

        sweep();
        rig.tick();
        assertNotNull(rig.ownership.get("ch-1"));

        sweep();
        rig.tick();
        assertNull(rig.ownership.get("ch-1"));
        assertTrue(rig.sink.info.contains("Released the lease on ch-1: undeployed here."));
    }

    @Test
    void aSweepThatCannotReadTheEngineWarnsOnceAndQueuesNothing() {
        own("ch-1");
        engine.statusFails = true;

        sweep();
        sweep();

        assertEquals(1, rig.sink.warnings.stream().filter(w -> w.startsWith("The sweep could not")).count());
        assertTrue(pending.isEmpty());
    }

    @Test
    void aStartThatLandsDuringTheSweepNeverReadsAsAnOperatorsStop() {
        rig.protect("ch-1");
        deploy(Models.file("ch-1"), DeployedState.PAUSED, DeployedState.STOPPED);
        sweep();
        rig.acquire();
        engine.onEnabled = this::drain;

        reconciler.sweep();

        assertEquals(1, engine.count("start ch-1"));
        assertFalse(rig.local.operatorStop("ch-1"));
    }

    @Test
    void aRedeployDuringTheSweepKeepsTheNewCopysMarkAndTheOwnerStartsIt() {
        rig.protect("ch-1");
        deploy(Models.file("ch-1"), DeployedState.PAUSED, DeployedState.STOPPED);
        engine.set("ch-1", DeployedState.STOPPED, DeployedState.STOPPED);
        engine.onEnabled = () -> {
            engine.onEnabled = () -> { };
            deploy(Models.file("ch-1"), DeployedState.PAUSED, DeployedState.STOPPED);
        };

        reconciler.sweep();

        assertTrue(rig.local.weStopped("ch-1"));
        sweep();
        rig.acquire();
        drain();
        assertEquals(1, engine.count("start ch-1"));
    }

    @Test
    void aFailedWriterRefreshWarnsOnceAcrossSweeps() {
        engine.savedFails = true;

        sweep();
        sweep();

        assertEquals(1, rig.sink.warnings.stream().filter(w -> w.startsWith("The sweep could not refresh")).count());
    }

    @Test
    void aMissingGateStaysRaisedWhenTheChannelTurnsUnmanagedAndBack() {
        gateFindsCopy = false;
        rig.protect("ch-1");
        deploy(Models.file("ch-1"), DeployedState.STARTED, DeployedState.STARTED);
        assertTrue(rig.faults.isFiring(Code.PC116, "ch-1"));

        rig.mode("ch-1", "unmanaged");
        drain();
        rig.mode("ch-1", "single-owner");
        drain();

        assertTrue(rig.faults.isFiring(Code.PC116, "ch-1"));
    }

    @Test
    void anUndeployClearsAPendingStopsFaultAndMarker() {
        own("ch-1");
        engine.onStop = (e, id) -> e.set(id, DeployedState.PAUSING, DeployedState.STARTED);
        rig.advanceMs(25_000);
        drain();
        rig.advanceMs(31_000);
        sweep();
        assertTrue(rig.faults.isFiring(Code.PC104, "ch-1"));
        assertTrue(rig.ownership.mustStop("ch-1"));

        reconciler.undeployed("ch-1");

        assertFalse(rig.faults.isFiring(Code.PC104, "ch-1"));
        assertFalse(rig.ownership.mustStop("ch-1"));
    }

    @Test
    void anUnsettledFreshReadKeepsAnOperatorsStopAndTheNodeThatMadeIt() {
        rig.protect("ch-1");
        rig.heldBy("ch-1", "srv-b");
        deploy(Models.file("ch-1"), DeployedState.PAUSED, DeployedState.STOPPED);
        sweep();
        rig.stoppedRow("ch-1");
        rig.expire("ch-1");
        rig.acquire();
        drain();
        assertTrue(rig.local.operatorStop("ch-1"));
        assertEquals("srv-b", reconciler.stoppedOn("ch-1"));
        engine.onEnabled = () -> engine.set("ch-1", DeployedState.PAUSING, DeployedState.STOPPED);

        reconciler.sweep();

        assertTrue(rig.local.operatorStop("ch-1"));
        assertEquals("srv-b", reconciler.stoppedOn("ch-1"));
    }

    @Test
    void aStopThatLandsBetweenTheMarkReadsAndTheStatusReadIsNotAnOperatorsStop() {
        own("ch-1");
        engine.set("ch-1", DeployedState.PAUSED, DeployedState.STOPPED);
        engine.onStatus = () -> {
            engine.onStatus = () -> { };
            rig.local.setWeStopped("ch-1", true);
        };

        reconciler.sweep();

        assertFalse(rig.local.operatorStop("ch-1"));
    }

    @Test
    void aSourceStartedByHandForgetsOurMarkSoAnOperatorsLaterPauseStands() {
        rig.protect("ch-1");
        deploy(Models.file("ch-1"), DeployedState.PAUSED, DeployedState.STOPPED);
        assertTrue(rig.local.weStopped("ch-1"));
        engine.set("ch-1", DeployedState.STARTED, DeployedState.STARTED);

        sweep();
        assertFalse(rig.local.weStopped("ch-1"));

        rig.acquire();
        drain();
        sweep();
        engine.set("ch-1", DeployedState.PAUSED, DeployedState.STOPPED);
        sweep();
        sweep();

        assertEquals(0, engine.count("start ch-1"));
        assertTrue(rig.local.operatorStop("ch-1"));
    }

    @Test
    void aRedeployBetweenTheLanesReadAndItsClearKeepsTheNewCopysMark() {
        rig.protect("ch-1");
        deploy(Models.file("ch-1"), DeployedState.PAUSED, DeployedState.STOPPED);
        engine.set("ch-1", DeployedState.STARTED, DeployedState.STARTED);
        engine.onDeployed = () -> {
            engine.onDeployed = () -> { };
            deploy(Models.file("ch-1"), DeployedState.PAUSED, DeployedState.STOPPED);
        };

        lanes.queue("ch-1");
        drain();
        assertTrue(rig.local.weStopped("ch-1"));

        sweep();
        rig.acquire();
        drain();
        assertEquals(1, engine.count("start ch-1"));
    }

    private ChannelReconciler reconciler(ChannelReconciler.Pause pause) {
        ChannelReconciler r = new ChannelReconciler(engine, rig.ownership, rig.local, rig.faults, rig.messages,
            rig.sink, rig.names, Timing.STANDARD, () -> rig.keeper, rig.clock(), lanes, targets, pause);
        lanes.bind(r::reconcile);
        return r;
    }

    /** Deploys the channel on this node and in the fake engine, as the engine does before the hook. */
    private void deploy(Channel model, DeployedState channel, DeployedState source) {
        engine.deploy(model, channel, source);
        reconciler.deployed(model);
    }

    /** Protects, deploys and acquires the channel, and lets its lane start the source. */
    private void own(String id) {
        rig.protect(id);
        deploy(Models.file(id), DeployedState.PAUSED, DeployedState.STOPPED);
        sweep();
        rig.acquire();
        drain();
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
}
