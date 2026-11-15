// SPDX-License-Identifier: MPL-2.0
package dev.pontik.cluster.lease;

import static dev.pontik.cluster.lease.FakeStore.list;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.pontik.cluster.fault.Code;
import dev.pontik.cluster.fault.Faults;
import dev.pontik.cluster.fault.FixedNames;
import dev.pontik.cluster.fault.Messages;
import dev.pontik.cluster.fault.RecordingSink;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class KeeperTest {

    private static final Set<String> NONE = Collections.emptySet();
    private static final long VALIDITY_NANOS = TimeUnit.SECONDS.toNanos(24); // a 30 s lease minus the 6 s margin

    private final FakeClock clock = new FakeClock();
    private final Node me = new Node("srv-a", "node-a", "inst-a", "test");
    private final FakeStore store = new FakeStore(clock, me);
    private final RecordingSink sink = new RecordingSink();
    private final RecordingReconciler reconciler = new RecordingReconciler();
    private final Ownership ownership = new Ownership(reconciler, clock);
    private final LocalState local = new LocalState(Timing.STANDARD);
    private final FixedNames names = new FixedNames("srv-a", "node-a").channel("ch-1", "Orders");
    private final Faults faults = new Faults(sink, names);
    private final Messages messages = new Messages(24, 90, 30);
    private final ManualTimer timer = new ManualTimer(clock);
    private final Fence fence = new Fence(ownership, timer, clock, faults, messages, Timing.STANDARD, names, sink);
    private final Keeper keeper = new Keeper(store, Timing.STANDARD, me, ownership, local, fence, faults, messages,
        names, reconciler, sink, clock, () -> 0L, Store.ReadBack.EMPTY);

    @Test
    void theSteadyTickIsFourStatementsWithTheRenewalFirst() {
        own("ch-1", "ch-2", "ch-3");
        int mark = store.calls.size();

        nextTick();

        assertEquals(list("renew", "readBack", "upsertNode", "peers"), store.callsSince(mark));
    }

    @Test
    void aTickWithoutLeasesRenewsNothingAndADueReleaseComesAfterTheReadBack() {
        store.protect("ch-1");
        keeper.tick();
        assertEquals(list("readBack", "upsertNode", "peers"), store.calls);

        deploy("ch-1");
        keeper.acquire();
        local.undeployed("ch-1");
        idleSweeps(2);
        int mark = store.calls.size();
        nextTick();
        assertEquals(list("renew", "readBack", "release", "upsertNode", "peers"), store.callsSince(mark));
    }

    @Test
    void aConfirmedRenewalMovesTheDeadlineToItsSendTimePlusLeaseMinusMargin() {
        own("ch-1");
        clock.advanceMs(10_000);
        long sent = clock.getAsLong();
        // The reply arrives two seconds after the send; the deadline must not move with it.
        store.behavior = statement -> clock.advanceMs(2_000);

        keeper.tick();

        assertEquals(sent + VALIDITY_NANOS, ownership.get("ch-1").validUntil);
    }

    @Test
    void anAcquisitionMovesTheDeadlineToItsSendTimePlusLeaseMinusMarginToo() {
        store.protect("ch-1");
        deploy("ch-1");
        keeper.tick();
        long sent = clock.getAsLong();
        store.behavior = statement -> clock.advanceMs(2_000);

        keeper.acquire();

        assertEquals(sent + VALIDITY_NANOS, ownership.get("ch-1").validUntil);
    }

    @Test
    void aKeeperThatStopsTickingIsFencedAtItsLastRenewalsDeadline() {
        own("ch-1");
        clock.advanceMs(10_000);
        keeper.tick();
        clock.advanceMs(23_999);
        timer.runDue();
        assertNotNull(ownership.get("ch-1"));
        clock.advanceMs(1);
        timer.runDue();
        assertNull(ownership.get("ch-1"));
        assertTrue(ownership.mustStop("ch-1"));
    }

    @Test
    void aTickThatStartsPastTheDeadlineFencesTheLeaseBeforeItSendsAnything() {
        own("ch-1");
        clock.advanceMs(25_000);
        keeper.tick();
        assertNull(ownership.get("ch-1"));
        assertTrue(ownership.mustStop("ch-1"));
        assertEquals(1, sink.count(Code.PC102));
    }

    @Test
    void aTickThatIsInterruptedSendsNoStatement() {
        own("ch-1");
        int mark = store.calls.size();
        clock.advanceMs(10_000);

        Thread.currentThread().interrupt();
        try {
            keeper.tick();
            assertEquals(list(), store.callsSince(mark));
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void theRenewalTickRunsAtAFixedRateAndOnlyTheAcquisitionIsJittered() {
        own("ch-1");
        store.protect("ch-2");
        deploy("ch-2");
        CapturingExecutor executor = new CapturingExecutor();
        Keeper jittered = new Keeper(store, Timing.STANDARD, me, ownership, local, fence, faults, messages, names,
            reconciler, sink, clock, () -> 1_500L, Store.ReadBack.EMPTY);

        jittered.start(executor);
        assertEquals(list("fixed rate 0 10000"), executor.scheduled);

        int mark = store.calls.size();
        executor.tasks.get(0).run();
        assertEquals(list("renew", "readBack", "upsertNode", "peers"), store.callsSince(mark));
        assertEquals(list("fixed rate 0 10000", "once 1500"), executor.scheduled);

        mark = store.calls.size();
        executor.tasks.get(1).run();
        assertEquals(list("acquire"), store.callsSince(mark));
        assertNotNull(ownership.get("ch-2"));
    }

    @Test
    void aLeaseTheRenewalDoesNotReturnIsDemotedAtOnceAndPc101NamesTheNewOwner() {
        own("ch-1");
        store.holdBy("ch-1", "srv-b", 2);

        nextTick();

        assertNull(ownership.get("ch-1"));
        assertTrue(ownership.mustStop("ch-1"));
        assertEquals(1, sink.count(Code.PC101));
        assertTrue(sink.fired.get(0).contains("the database now names \"srv-b\" as the owner (epoch 2)"),
            sink.fired.get(0));
    }

    @Test
    void aRowSomeoneElseClearsAfterOurRenewalGivesPc101WithNoOwnerNamed() {
        own("ch-1");
        long epoch = ownership.get("ch-1").epoch;
        // The renewal confirms the lease; a foreign write then clears the owner before the read-back.
        store.behavior = statement -> {
            if (statement.equals("readBack")) {
                store.holdBy("ch-1", null, epoch);
            }
        };

        clock.advanceMs(10_000);
        keeper.tick();

        assertNull(ownership.get("ch-1"));
        assertEquals(1, sink.count(Code.PC101));
        assertTrue(sink.fired.get(0).contains("the database now names no owner (epoch " + epoch + ")"),
            sink.fired.get(0));
    }

    @Test
    void aLeaseTheRenewalDoesNotReturnIsDemotedAtOnceEvenWhenTheRowStillNamesUs() {
        own("ch-1");
        store.leases.get("ch-1").expiresAt = store.dbNow;
        clock.advanceMs(10_000);

        keeper.tick();

        assertNull(ownership.get("ch-1"));
        assertTrue(ownership.mustStop("ch-1"));
    }

    @Test
    void aBatchedRenewalThatReturnsOneOfTwoLeasesDemotesTheOther() {
        own("ch-1", "ch-2");
        // The row still names this node and its epoch but has run out, so the renewal skips it.
        store.leases.get("ch-2").expiresAt = store.dbNow;
        clock.advanceMs(10_000);

        keeper.tick();

        assertNotNull(ownership.get("ch-1"));
        assertFalse(ownership.mustStop("ch-1"));
        assertNull(ownership.get("ch-2"));
        assertTrue(ownership.mustStop("ch-2"));
        assertEquals(0, sink.count(Code.PC101));
    }

    @Test
    void aFailedRenewalKeepsTheDeadlinesAndEndsTheTick() {
        own("ch-1");
        long validUntil = ownership.get("ch-1").validUntil;
        store.behavior = statement -> {
            throw new SQLException("socket", "08006");
        };
        int mark = store.calls.size();

        nextTick();

        assertEquals(list("renew"), store.callsSince(mark));
        assertEquals(validUntil, ownership.get("ch-1").validUntil);
    }

    @Test
    void aDatabaseTakingFourPointNineSecondsPerStatementNeverDelaysARenewal() {
        own("ch-1");
        long start = clock.ms();
        store.behavior = statement -> clock.advanceMs(4_900);
        int mark = store.calls.size();

        for (int i = 1; i <= 3; i++) {
            at(start + i * 10_000L);
            keeper.tick();
            keeper.acquire();
            if (i == 1) {
                assertEquals(list("renew", "readBack"), store.callsSince(mark));
            }
        }

        assertEquals(Arrays.asList(start + 10_000, start + 20_000, start + 30_000), renewTimes());
        timer.runDue();
        assertNotNull(ownership.get("ch-1"));
        assertFalse(ownership.mustStop("ch-1"));
        assertEquals(0, sink.count(Code.PC102));
    }

    @Test
    void aHangingStatementCostsOneTickAndOneFailedTickDoesNotFence() {
        own("ch-1");
        long start = clock.ms();
        AtomicInteger renewals = new AtomicInteger();
        store.behavior = statement -> {
            if (statement.equals("renew") && renewals.incrementAndGet() == 1) {
                clock.advanceMs(3_000);
                throw new SQLException("socket timeout", "08006");
            }
        };

        at(start + 10_000);
        keeper.tick();
        at(start + 20_000);
        keeper.tick();
        at(start + 24_000);
        timer.runDue();

        assertEquals(Arrays.asList(start + 10_000, start + 20_000), renewTimes());
        assertNotNull(ownership.get("ch-1"));
        assertEquals(0, sink.count(Code.PC102));
    }

    @Test
    void newCandidatesFillTheFairShareAndCarriedOnesAreTakenWholeAndFirst() {
        String[] ten = {"c0", "c1", "c2", "c3", "c4", "c5", "c6", "c7", "c8", "c9"};
        store.protect(ten);
        deploy(ten);
        store.peers.add(new Store.Peer("srv-b", "node-b", Node.PROTOCOL));

        keeper.tick();
        keeper.acquire();
        keeper.tick();

        assertEquals(Integer.valueOf(5), store.acquireShares.get(0));
        assertEquals(5, ownership.size());

        store.protect("new");
        deploy("new");
        clock.advanceMs(10_000);
        keeper.tick();
        keeper.acquire();

        String[] candidates = store.acquireCandidates.get(1);
        assertEquals(6, candidates.length);
        assertEquals("new", candidates[5]);
        assertEquals(Integer.valueOf(6), store.acquireShares.get(1));
        assertEquals(11, ownership.size());
    }

    @Test
    void theShareIsAtLeastOneWhenThisNodeAlreadyHoldsItsFairShare() {
        store.protect("ch-1", "ch-2");
        deploy("ch-1");
        store.peers.add(new Store.Peer("srv-b", "node-b", Node.PROTOCOL));
        keeper.tick();
        keeper.tick();
        keeper.acquire();
        assertEquals(1, ownership.size());

        deploy("ch-2");
        nextTick();

        assertEquals(2, ownership.size());
    }

    @Test
    void anotherHolderIncludesOurServerIdUnderAnEpochThisProcessDoesNotHold() {
        store.protect("ch-1", "ch-2", "ch-3");
        deploy("ch-1", "ch-2", "ch-3");
        store.holdBy("ch-1", "srv-a", 5);
        store.holdBy("ch-2", "srv-b", 1);

        keeper.tick();
        keeper.acquire();

        assertTrue(keeper.anotherHolder(keeper.readBack(), "ch-1"));
        assertTrue(keeper.anotherHolder(keeper.readBack(), "ch-2"));
        assertNull(ownership.get("ch-1"));
        assertFalse(keeper.anotherHolder(keeper.readBack(), "ch-3"));
        assertNotNull(ownership.get("ch-3"));
        nextTick();
        assertFalse(keeper.anotherHolder(keeper.readBack(), "ch-3"));
    }

    @Test
    void pc119FiresAfterTwoMismatchedTicksClearsAfterTwoMatchedOnesAndTheNodeStaysRunning() {
        store.behavior = statement -> {
            if (statement.equals("upsertNode")) {
                store.previousInstance = "inst-x";
            }
        };
        keeper.tick();
        assertEquals(0, sink.count(Code.PC119));
        keeper.tick();
        assertEquals(1, sink.count(Code.PC119));
        assertEquals(Keeper.State.RUNNING, keeper.state());

        store.behavior = statement -> { };
        keeper.tick();
        assertTrue(faults.isFiring(Code.PC119, null));
        keeper.tick();
        assertFalse(faults.isFiring(Code.PC119, null));
    }

    @Test
    void pc119ClearsOnlyAfterTwoMatchedTicksInARow() {
        AtomicBoolean mismatch = new AtomicBoolean(true);
        store.behavior = statement -> {
            if (statement.equals("upsertNode") && mismatch.get()) {
                store.previousInstance = "inst-x";
            }
        };
        keeper.tick();
        keeper.tick();
        assertTrue(faults.isFiring(Code.PC119, null));

        mismatch.set(false);
        keeper.tick();
        mismatch.set(true);
        keeper.tick();
        mismatch.set(false);
        keeper.tick();
        assertTrue(faults.isFiring(Code.PC119, null));
        assertEquals(1, sink.count(Code.PC119));
    }

    @Test
    void aPeerWithAnUnknownProtocolMakesThisNodeDeclineAndReleaseAtTheNextTick() {
        own("ch-1");
        store.peers.add(new Store.Peer("srv-b", "node-b", 2));

        nextTick();
        assertEquals(Keeper.State.DECLINED, keeper.state());
        assertEquals(1, sink.count(Code.PC107));
        assertTrue(sink.fired.get(0).contains("node \"node-b\" runs protocol 2"), sink.fired.get(0));

        nextTick();
        assertTrue(ownership.isEmpty());
        assertTrue(sink.info.contains("Released the lease on Orders: node declined."));
        int mark = store.calls.size();
        keeper.acquire();
        assertEquals(list(), store.callsSince(mark));
    }

    @Test
    void aColdStartRaisesNoPc103AndAChannelStoppedHereWithNoOwnerRaisesItAfterNinetySeconds() {
        store.protect("ch-1", "ch-2");
        deploy("ch-1", "ch-2");
        local.observed("ch-2", true, false);

        keeper.tick();
        keeper.acquire();
        for (int i = 0; i < 9; i++) {
            nextTick();
        }
        assertEquals(0, sink.count(Code.PC103));

        nextTick();
        assertEquals(1, sink.count(Code.PC103));
        assertTrue(sink.fired.get(0).contains("On this node it is stopped."), sink.fired.get(0));

        store.holdBy("ch-2", "srv-b", 1);
        nextTick();
        assertFalse(faults.isFiring(Code.PC103, "ch-2"));
    }

    @Test
    void pc103SaysSoWhenTheOperatorStoppedTheSourceOnThisNode() {
        store.protect("ch-1");
        deploy("ch-1");
        local.observed("ch-1", false, true);

        keeper.tick();
        keeper.acquire();
        for (int i = 0; i < 10; i++) {
            nextTick();
        }

        assertEquals(1, sink.count(Code.PC103));
        assertTrue(sink.fired.get(0).contains("On this node it is stopped."), sink.fired.get(0));
    }

    @Test
    void aNodeThatDeclinesClearsItsPc103AndRaisesNoneForTheChannelsItHandsBack() {
        own("ch-2");
        store.protect("ch-1");
        deploy("ch-1");
        local.observed("ch-1", true, false);
        for (int i = 0; i < 11; i++) {
            nextTick();
        }
        assertTrue(faults.isFiring(Code.PC103, "ch-1"));

        store.peers.add(new Store.Peer("srv-b", "node-b", 2));
        nextTick();
        nextTick();
        assertEquals(Keeper.State.DECLINED, keeper.state());
        assertTrue(ownership.isEmpty());
        assertFalse(faults.isFiring(Code.PC103, "ch-1"));

        for (int i = 0; i < 11; i++) {
            nextTick();
        }
        assertEquals(1, sink.count(Code.PC103));
    }

    @Test
    void aRedeployWhoseHookArrivesBeforeTheSecondIdleSweepKeepsTheLease() {
        own("ch-1");
        local.undeployed("ch-1");
        idleSweeps(1);
        local.deployed("ch-1", "Orders");
        // A sweep that started before the hook can still find the channel gone.
        idleSweeps(1);

        int mark = store.calls.size();
        nextTick();

        assertFalse(store.callsSince(mark).contains("release"));
        assertNotNull(ownership.get("ch-1"));
    }

    @Test
    void aRedeployedChannelThisNodeDoesNotHoldIsACandidateAgain() {
        store.protect("ch-1");
        deploy("ch-1");
        local.undeployed("ch-1");
        local.deployed("ch-1", "Orders");

        keeper.tick();
        keeper.acquire();

        assertNotNull(ownership.get("ch-1"));
    }

    @Test
    void twoIdleSweepsThenATickReleaseTheLease() {
        own("ch-1");
        local.undeployed("ch-1");
        idleSweeps(2);

        nextTick();

        assertNull(ownership.get("ch-1"));
        assertTrue(sink.info.contains("Released the lease on Orders: undeployed here."));
    }

    @Test
    void noReleaseWhileTheEngineIsBusyOrTheLatestSweepIsStale() {
        own("ch-1");
        local.undeployed("ch-1");
        for (int i = 0; i < 3; i++) {
            local.swept(clock.getAsLong(), true, NONE);
            nextTick();
        }
        assertNotNull(ownership.get("ch-1"));

        idleSweeps(2);
        clock.advanceMs(20_001);
        keeper.tick();
        assertNotNull(ownership.get("ch-1"));
    }

    @Test
    void anAcquisitionThatRacedAnUndeployIsReleasedWithOneInfoLineAndNoCode() {
        store.protect("ch-1");
        deploy("ch-1");
        keeper.tick();
        store.behavior = statement -> {
            if (statement.equals("acquire")) {
                local.undeployed("ch-1");
            }
        };
        keeper.acquire();
        store.behavior = statement -> { };
        idleSweeps(2);

        nextTick();

        assertNull(ownership.get("ch-1"));
        assertTrue(sink.info.contains("Released the lease on Orders: undeployed here."));
        assertTrue(sink.fired.isEmpty());
    }

    @Test
    void aLeaseIsReleasedOnlyOnceItsSourceIsStoppedExceptTheHandBacks() {
        own("ch-1", "ch-2", "ch-3");
        for (int i = 0; i < 5; i++) {
            local.swept(clock.getAsLong(), false, new HashSet<>(list("ch-1", "ch-2", "ch-3")));
            nextTick();
        }
        assertFalse(store.calls.contains("release"));

        store.modes.put("ch-1", Store.UNMANAGED);
        store.modes.remove("ch-2");
        local.verdict("ch-3", Code.PC114, true);
        reconciler.queued.clear();
        nextTick();

        assertTrue(ownership.isEmpty());
        assertTrue(sink.info.contains("Released the lease on Orders: set to unmanaged."));
        assertTrue(sink.info.contains("Released the lease on ch-2: the channel was deleted."));
        assertTrue(sink.info.contains("Released the lease on ch-3: refused (PC-114)."));
        assertTrue(reconciler.queued.containsAll(list("ch-1", "ch-2", "ch-3")));
        assertFalse(ownership.mustStop("ch-1"));
    }

    @Test
    void aModeThisVersionDoesNotKnowCountsAsUnmanaged() {
        own("ch-1");
        store.protect("ch-2");
        deploy("ch-2");
        store.modes.put("ch-1", "observe");
        store.modes.put("ch-2", "observe");

        nextTick();
        nextTick();

        assertTrue(ownership.isEmpty());
        assertTrue(sink.info.contains("Released the lease on Orders: set to unmanaged."));
        assertNull(ownership.get("ch-2"));
    }

    @Test
    void aReleaseThatMatchesNoRowRaisesPc101() {
        own("ch-1");
        local.undeployed("ch-1");
        idleSweeps(2);
        store.behavior = statement -> {
            if (statement.equals("release")) {
                store.leases.get("ch-1").epoch++;
            }
        };

        nextTick();

        assertEquals(1, sink.count(Code.PC101));
        assertTrue(sink.fired.get(0).contains("the release matched no row"), sink.fired.get(0));
    }

    @Test
    void aReleaseThatFailsStillForgetsTheStartFailureSoALeaseTakenAgainIsKept() {
        own("ch-1");
        local.startFailed("ch-1", clock.getAsLong());
        store.behavior = statement -> {
            if (statement.equals("release")) {
                throw new SQLException("socket", "08006");
            }
        };
        nextTick();
        assertNull(ownership.get("ch-1"));

        // The cool-down passes and the unreleased row expires, so this node takes the channel again.
        store.behavior = statement -> { };
        store.dbNow = store.dbNow.plusSeconds(40);
        clock.advanceMs(90_000);
        nextTick();
        assertNotNull(ownership.get("ch-1"));

        int mark = store.calls.size();
        nextTick();

        assertFalse(store.callsSince(mark).contains("release"));
        assertNotNull(ownership.get("ch-1"));
    }

    @Test
    void aMissingLeaseRowOfAProtectedChannelIsWrittenBack() {
        store.modes.put("ch-1", Store.SINGLE_OWNER);

        keeper.tick();

        assertTrue(store.calls.contains("insertLease"));
        assertNotNull(store.leases.get("ch-1"));
    }

    @Test
    void pc102ClearsAfterATickWithTheNodeRowWrittenAndNoLeaseAndASecondFenceAlertsAgain() {
        own("ch-1");
        clock.advanceMs(24_000);
        timer.runDue();
        assertEquals(1, sink.count(Code.PC102));

        store.leases.get("ch-1").expiresAt = store.dbNow;
        store.behavior = statement -> {
            if (statement.equals("upsertNode")) {
                throw new SQLException("socket", "08006");
            }
        };
        keeper.tick();
        assertTrue(faults.isFiring(Code.PC102, null));

        store.behavior = statement -> { };
        keeper.tick();
        assertFalse(faults.isFiring(Code.PC102, null));

        keeper.acquire();
        assertNotNull(ownership.get("ch-1"));
        clock.advanceMs(24_000);
        timer.runDue();
        assertEquals(2, sink.count(Code.PC102));
    }

    @Test
    void aSerializationFailureOnTheAcquisitionMeansNotAcquiredAndNothingToReport() {
        store.protect("ch-1");
        deploy("ch-1");
        keeper.tick();
        store.behavior = statement -> {
            if (statement.equals("acquire")) {
                throw new SQLException("could not serialize access", "40001");
            }
        };

        keeper.acquire();

        assertTrue(ownership.isEmpty());
        assertTrue(sink.fired.isEmpty());
        assertTrue(sink.warnings.isEmpty());
        assertEquals(Keeper.State.RUNNING, keeper.state());
    }

    @Test
    void anAcquisitionLogsOneLineAndARenewalLogsNone() {
        own("ch-1");
        assertEquals(list("Acquired the lease on Orders (epoch 1)."), sink.info);

        nextTick();
        nextTick();

        assertEquals(1, sink.info.size());
    }

    @Test
    void theRenewalWritesTheOperatorStopAndAnAcquisitionStartsItFromTheRow() {
        own("ch-1");
        local.setOperatorStop("ch-1", true);
        nextTick();
        assertTrue(store.leases.get("ch-1").stopped);

        store.protect("ch-2");
        store.leases.get("ch-2").stopped = true;
        deploy("ch-2");
        nextTick();
        assertTrue(local.operatorStop("ch-2"));
        assertTrue(local.acquiredStopped("ch-2"));
    }

    @Test
    void aNodeWithoutAReadBackForTwoHeartbeatsLogsOneWarningAndOneLineWhenItReadsAgain() {
        keeper.tick();
        store.behavior = statement -> {
            if (statement.equals("readBack")) {
                throw new SQLException("connection refused to db.internal:5432", "08006");
            }
        };

        nextTick();
        assertTrue(sink.warnings.isEmpty(), sink.warnings.toString());
        nextTick();
        assertEquals(1, sink.warnings.size(), sink.warnings.toString());
        nextTick();
        nextTick();
        assertEquals(list("This node has not read the lease tables for 20 seconds (SQLState 08006), so it "
            + "cannot take over a channel. Please check this node's connection to the database and its access "
            + "to the lease tables. null"), sink.warnings);

        store.behavior = statement -> { };
        nextTick();
        nextTick();
        assertEquals(list("This node reads the lease tables again."), sink.info);
        assertEquals(1, sink.warnings.size());

        store.behavior = statement -> {
            if (statement.equals("readBack")) {
                throw new SQLException("terminating connection", "57P01");
            }
        };
        nextTick();
        assertEquals(1, sink.warnings.size(), sink.warnings.toString());
        nextTick();
        assertEquals(2, sink.warnings.size(), sink.warnings.toString());
        assertTrue(sink.warnings.get(1).contains("(SQLState 57P01)"), sink.warnings.get(1));
        assertTrue(sink.fired.isEmpty(), sink.fired.toString());
    }

    @Test
    void aHolderWhoseReadBackFailsKeepsItsLeaseAndLogsTheSameWarning() {
        own("ch-1");
        store.behavior = statement -> {
            if (statement.equals("readBack")) {
                throw new SQLException("permission denied for table pcl_channel_config", "42501");
            }
        };

        nextTick();
        nextTick();

        assertNotNull(ownership.get("ch-1"));
        assertEquals(0, sink.count(Code.PC102));
        assertEquals(1, sink.warnings.size());
        assertTrue(sink.warnings.get(0).contains("(SQLState 42501)"), sink.warnings.get(0));
    }

    @Test
    void oneMissedReadBackLogsNothing() {
        keeper.tick();
        store.behavior = statement -> {
            if (statement.equals("readBack")) {
                throw new SQLException("socket", "08006");
            }
        };
        nextTick();

        store.behavior = statement -> { };
        nextTick();

        assertTrue(sink.warnings.isEmpty(), sink.warnings.toString());
        assertTrue(sink.info.isEmpty(), sink.info.toString());
    }

    @Test
    void shutdownReleasesEveryLeaseInOneStatementThenMarksTheNodeGone() {
        own("ch-1", "ch-2");
        int mark = store.calls.size();

        keeper.shutdown();

        assertEquals(list("release", "markGone"), store.callsSince(mark));
        assertTrue(ownership.isEmpty());
        assertTrue(sink.info.contains("Released the lease on Orders: shutting down."));
        assertTrue(sink.info.contains("Released the lease on ch-2: shutting down."));
    }

    @Test
    void shutdownRetriesOnceAfterAConnectionErrorAndThenGivesUp() {
        own("ch-1");
        AtomicInteger failures = new AtomicInteger(1);
        store.behavior = statement -> {
            if (statement.equals("release") && failures.getAndDecrement() > 0) {
                throw new SQLException("socket", "08006");
            }
        };
        int mark = store.calls.size();
        keeper.shutdown();
        assertEquals(list("release", "release", "markGone"), store.callsSince(mark));

        own("ch-2");
        store.behavior = statement -> {
            throw new SQLException("refused", "08001");
        };
        mark = store.calls.size();
        keeper.shutdown();
        assertEquals(list("release", "release"), store.callsSince(mark));
        assertEquals(1, sink.warnings.size());
    }

    @Test
    void aShutdownReleaseThatIsCanceledIsNotRetriedAndTheNodeIsStillMarkedGone() {
        own("ch-1");
        store.behavior = statement -> {
            if (statement.equals("release")) {
                throw new SQLException("canceling statement due to statement timeout", "57014");
            }
        };
        int mark = store.calls.size();

        keeper.shutdown();

        assertEquals(list("release", "markGone"), store.callsSince(mark));
        assertEquals(list("The release at shutdown failed, so the leases expire on their own within one lease. "
            + "null"), sink.warnings);
    }

    @Test
    void aShutdownReleaseThatLosesItsConnectionAndIsThenCanceledStillMarksTheNodeGone() {
        own("ch-1");
        AtomicInteger releases = new AtomicInteger();
        store.behavior = statement -> {
            if (statement.equals("release")) {
                throw releases.incrementAndGet() == 1
                    ? new SQLException("socket", "08006")
                    : new SQLException("canceling statement due to statement timeout", "57014");
            }
        };
        int mark = store.calls.size();

        keeper.shutdown();

        assertEquals(list("release", "release", "markGone"), store.callsSince(mark));
        assertEquals(1, sink.warnings.size());
    }

    @Test
    void theFirstConfirmedRenewalAfterAFenceClearsPc102() {
        store.protect("ch-1", "ch-2");
        deploy("ch-1");
        keeper.tick();
        keeper.acquire();
        // The renewal fails while this node takes ch-2, so the two leases end up with different deadlines.
        store.behavior = statement -> {
            if (statement.equals("renew")) {
                throw new SQLException("socket", "08006");
            }
        };
        deploy("ch-2");
        nextTick();
        clock.advanceMs(14_000);
        timer.runDue();
        assertTrue(faults.isFiring(Code.PC102, null));
        assertNotNull(ownership.get("ch-2"));

        store.behavior = statement -> { };
        clock.advanceMs(6_000);
        keeper.tick();

        assertNotNull(ownership.get("ch-2"));
        assertFalse(faults.isFiring(Code.PC102, null));
    }

    @Test
    void aFailedReadBackLeaseWriteOrReleaseEndsTheTick() {
        own("ch-1");
        local.undeployed("ch-1");
        // A protected channel whose lease row is missing, so the tick writes it back.
        store.modes.put("ch-2", Store.SINGLE_OWNER);

        for (String failing : list("readBack", "insertLease", "release")) {
            store.behavior = statement -> {
                if (statement.equals(failing)) {
                    throw new SQLException("socket", "08006");
                }
            };
            clock.advanceMs(10_000);
            idleSweeps(2);
            int mark = store.calls.size();

            keeper.tick();

            List<String> calls = store.callsSince(mark);
            assertEquals(failing, calls.get(calls.size() - 1), calls.toString());
        }
    }

    @Test
    void aChannelWhoseModeChangesIsQueuedForTheReconcilerOnce() {
        store.protect("ch-1");
        keeper.tick();
        assertEquals(list("ch-1"), reconciler.queued);

        keeper.tick();
        assertEquals(list("ch-1"), reconciler.queued);

        store.modes.put("ch-1", Store.UNMANAGED);
        keeper.tick();
        assertEquals(list("ch-1", "ch-1"), reconciler.queued);
    }

    @Test
    void pc101ForALeaseLostAtRenewalNamesTheDatabaseTimeAndFiresOnce() {
        own("ch-1");
        store.holdBy("ch-1", "srv-b", 2);

        nextTick();
        nextTick();

        assertEquals(1, sink.count(Code.PC101));
        assertTrue(sink.fired.get(0).contains("around 2026-11-04T10:00:00Z;"), sink.fired.get(0));
    }

    @Test
    void aReleaseThatSucceedsForgetsTheStartFailureSoALeaseTakenAgainIsKept() {
        own("ch-1");
        local.startFailed("ch-1", clock.getAsLong());
        nextTick();
        assertNull(ownership.get("ch-1"));

        // The cool-down passes, so this node takes the channel again.
        clock.advanceMs(90_000);
        nextTick();
        assertNotNull(ownership.get("ch-1"));

        int mark = store.calls.size();
        nextTick();

        assertFalse(store.callsSince(mark).contains("release"));
        assertNotNull(ownership.get("ch-1"));
    }

    @Test
    void pc102StaysThroughATickThatRunsOutOfTimeBeforeTheNodeRow() {
        own("ch-1");
        clock.advanceMs(24_000);
        timer.runDue();
        assertTrue(faults.isFiring(Code.PC102, null));
        // The read-back takes seven seconds, so the node row could still be running when the next tick is due.
        store.behavior = statement -> {
            if (statement.equals("readBack")) {
                clock.advanceMs(7_000);
            }
        };
        int mark = store.calls.size();

        keeper.tick();

        assertEquals(list("readBack"), store.callsSince(mark));
        assertTrue(faults.isFiring(Code.PC102, null));
    }

    @Test
    void aNodeThatDeclinesQueuesEveryChannelForTheReconcilerAtOnce() {
        own("ch-1");
        store.protect("ch-2");
        nextTick();
        reconciler.queued.clear();
        store.peers.add(new Store.Peer("srv-b", "node-b", 2));

        clock.advanceMs(10_000);
        keeper.tick();

        assertEquals(Keeper.State.DECLINED, keeper.state());
        assertTrue(reconciler.queued.containsAll(list("ch-1", "ch-2")), reconciler.queued.toString());
    }

    @Test
    void newCandidatesFillOnlyWhatIsLeftOfTheFairShare() {
        store.peers.add(new Store.Peer("srv-b", "node-b", Node.PROTOCOL));
        own("ch-1");
        store.protect("ch-2", "ch-3", "ch-4");
        deploy("ch-2", "ch-3", "ch-4");

        nextTick();

        // Four channels and two live nodes make a fair share of two, and this node already holds one.
        assertEquals(Integer.valueOf(1), store.acquireShares.get(store.acquireShares.size() - 1));
        assertEquals(2, ownership.size());
    }

    @Test
    void anAcquisitionClearsItsPc103AndQueuesTheChannelForTheReconciler() {
        store.protect("ch-1");
        deploy("ch-1");
        local.observed("ch-1", true, false);
        keeper.tick();
        for (int i = 0; i < 10; i++) {
            nextTick();
        }
        assertTrue(faults.isFiring(Code.PC103, "ch-1"));

        local.observed("ch-1", false, false);
        reconciler.queued.clear();
        nextTick();

        assertNotNull(ownership.get("ch-1"));
        assertFalse(faults.isFiring(Code.PC103, "ch-1"));
        assertEquals(list("ch-1"), reconciler.queued);
    }

    @Test
    void shutdownForgetsTheOperatorStopOfEveryLeaseItReleases() {
        own("ch-1");
        local.setOperatorStop("ch-1", true);

        keeper.shutdown();

        assertFalse(local.operatorStop("ch-1"));
    }

    @Test
    void shutdownRetriesTheNodeMarkerOnceAfterAConnectionErrorAndNotAfterAnyOther() {
        store.behavior = statement -> {
            throw new SQLException("socket", "08006");
        };
        keeper.shutdown();
        assertEquals(list("markGone", "markGone"), store.calls);

        store.behavior = statement -> {
            throw new SQLException("statement timeout", "57014");
        };
        int mark = store.calls.size();
        keeper.shutdown();
        assertEquals(list("markGone"), store.callsSince(mark));
    }

    @Test
    void theStatusCallReadsTheLatestReadBackItsAgeAndTheLastTick() {
        store.protect("ch-1");
        clock.advanceMs(10_000);
        long tickedAt = clock.getAsLong();
        keeper.tick();
        clock.advanceMs(3_000);

        assertTrue(keeper.readBack().rows.containsKey("ch-1"));
        assertEquals(3_000, keeper.readBackAgeMs());
        assertEquals(tickedAt, keeper.lastTickNanos());
    }

    @Test
    void anOwnerIsNamedByTheServerNameThisNodeKnowsElseByItsServerId() {
        own("ch-1", "ch-2", "ch-3");
        long ownEpoch = ownership.get("ch-1").epoch;
        // A peer seen on a tick of its own, so the next tick can name srv-b. Added after the acquisition,
        // because a second live node would cut this node's fair share below three channels.
        store.peers.add(new Store.Peer("srv-b", "node-b", Node.PROTOCOL));
        keeper.tick();

        // This node's own server ID under a newer epoch, a known peer, and a server ID no peer names.
        store.holdBy("ch-1", "srv-a", ownEpoch + 1);
        store.holdBy("ch-2", "srv-b", 2);
        store.holdBy("ch-3", "srv-c", 2);
        nextTick();

        assertEquals(3, sink.count(Code.PC101));
        assertFired("ch-1", "the database now names \"node-a\" as the owner (epoch " + (ownEpoch + 1) + ")");
        assertFired("ch-2", "the database now names \"node-b\" as the owner (epoch 2)");
        assertFired("ch-3", "the database now names \"srv-c\" as the owner (epoch 2)");
    }

    @Test
    void aStatementThatCanFinishExactlyWhenTheNextTickIsDueIsStillSent() {
        own("ch-1");
        // The renewal takes six seconds, so each later statement's budget of four ends exactly at the next tick.
        store.behavior = statement -> {
            if (statement.equals("renew")) {
                clock.advanceMs(6_000);
            }
        };
        int mark = store.calls.size();
        clock.advanceMs(10_000);

        keeper.tick();

        assertEquals(list("renew", "readBack", "upsertNode", "peers"), store.callsSince(mark));
    }

    @Test
    void aTickThatCannotScheduleItsAcquisitionWarns() {
        CapturingExecutor executor = new CapturingExecutor();
        Keeper broken = new Keeper(store, Timing.STANDARD, me, ownership, local, fence, faults, messages, names,
            reconciler, sink, clock, () -> {
                throw new IllegalStateException("no jitter");
            }, Store.ReadBack.EMPTY);
        broken.start(executor);

        executor.tasks.get(0).run();

        assertEquals(list("This node could not schedule its next attempt to take over channels. "
            + "java.lang.IllegalStateException: no jitter"), sink.warnings);
    }

    @Test
    void anUnexpectedFailureInAnAcquisitionOrATickWarnsWithItsOwnPhrase() {
        store.protect("ch-1");
        deploy("ch-1");
        keeper.tick();
        store.behavior = statement -> {
            throw new IllegalStateException("unexpected");
        };

        keeper.acquire();
        keeper.tick();

        assertTrue(ownership.isEmpty());
        assertEquals(list(
            "This node's attempt to take over channels failed unexpectedly. java.lang.IllegalStateException: "
                + "unexpected",
            "This node's lease renewal round failed unexpectedly. java.lang.IllegalStateException: unexpected"),
            sink.warnings);
    }

    @Test
    void theReadBackAgeCountsFromTheLastReadBackThatSucceeded() {
        keeper.tick();
        clock.advanceMs(3_000);
        assertEquals(3_000, keeper.readBackAgeMs());

        store.behavior = statement -> {
            if (statement.equals("readBack")) {
                throw new SQLException("socket", "08006");
            }
        };
        clock.advanceMs(7_000);
        keeper.tick();
        assertEquals(10_000, keeper.readBackAgeMs());

        store.behavior = statement -> { };
        clock.advanceMs(10_000);
        keeper.tick();
        assertEquals(0, keeper.readBackAgeMs());
    }

    private void own(String... channelIds) {
        store.protect(channelIds);
        deploy(channelIds);
        keeper.tick();
        keeper.acquire();
        for (String id : channelIds) {
            assertNotNull(ownership.get(id), "acquired " + id);
        }
    }

    private void deploy(String... channelIds) {
        for (String id : channelIds) {
            local.deployed(id, names.channelName(id));
            local.verdict(id, null, true);
        }
    }

    private void nextTick() {
        clock.advanceMs(10_000);
        keeper.tick();
        keeper.acquire();
    }

    private void assertFired(String channelId, String message) {
        boolean found = sink.fired.stream()
            .anyMatch(line -> line.contains("(" + channelId + ") ") && line.contains(message));
        assertTrue(found, message + " on " + channelId + " in " + sink.fired);
    }

    private void idleSweeps(int count) {
        for (int i = 0; i < count; i++) {
            local.swept(clock.getAsLong(), false, NONE);
        }
    }

    private void at(long ms) {
        assertTrue(clock.ms() <= ms, "the clock is at " + clock.ms() + " ms, already past " + ms);
        clock.advanceMs(ms - clock.ms());
    }

    private List<Long> renewTimes() {
        List<Long> times = new ArrayList<>();
        for (int i = 0; i < store.calls.size(); i++) {
            if (store.calls.get(i).equals("renew")) {
                times.add(store.callTimes.get(i));
            }
        }
        return times;
    }

    /** An executor that keeps each task it is handed and runs none, so the test runs them by hand. */
    private static final class CapturingExecutor extends ScheduledThreadPoolExecutor {

        final List<String> scheduled = new ArrayList<>();
        final List<Runnable> tasks = new ArrayList<>();

        CapturingExecutor() {
            super(1);
        }

        @Override
        public ScheduledFuture<?> scheduleAtFixedRate(Runnable command, long initialDelay, long period,
                TimeUnit unit) {
            return capture("fixed rate " + unit.toMillis(initialDelay) + " " + unit.toMillis(period), command);
        }

        @Override
        public ScheduledFuture<?> scheduleWithFixedDelay(Runnable command, long initialDelay, long delay,
                TimeUnit unit) {
            return capture("fixed delay " + unit.toMillis(initialDelay) + " " + unit.toMillis(delay), command);
        }

        @Override
        public ScheduledFuture<?> schedule(Runnable command, long delay, TimeUnit unit) {
            return capture("once " + unit.toMillis(delay), command);
        }

        // The keeper never reads the future it gets back, and no thread ever starts.
        private ScheduledFuture<?> capture(String call, Runnable command) {
            scheduled.add(call);
            tasks.add(command);
            return null;
        }
    }
}
