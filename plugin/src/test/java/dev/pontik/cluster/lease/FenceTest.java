// SPDX-License-Identifier: MPL-2.0
package dev.pontik.cluster.lease;

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
import java.util.Collections;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class FenceTest {

    private static final long VALIDITY_NANOS = TimeUnit.SECONDS.toNanos(24); // a 30 s lease minus the 6 s margin

    private final FakeClock clock = new FakeClock();
    private final ManualTimer timer = new ManualTimer(clock);
    private final RecordingSink sink = new RecordingSink();
    private final RecordingReconciler reconciler = new RecordingReconciler();
    private final Ownership ownership = new Ownership(reconciler, clock);
    private final Faults faults = new Faults(sink, new FixedNames("srv-1", "node-a"));
    private final Fence fence =
        new Fence(ownership, timer, clock, faults, new Messages(24, 90, 30), Timing.STANDARD, sink);

    @Test
    void aPassedDeadlineDemotesAndRaisesPc102Once() {
        long validUntil = hold("ch-1", 1);
        hold("ch-2", 1);

        clock.advanceMs(23_999);
        timer.runDue();
        assertNotNull(ownership.get("ch-1"));

        clock.advanceMs(1);
        timer.runDue();
        assertNull(ownership.get("ch-1"));
        assertNull(ownership.get("ch-2"));
        assertTrue(ownership.mustStop("ch-1"));
        assertEquals(1, sink.count(Code.PC102));
        assertTrue(sink.fired.get(0).contains("stopped its 2 protected sources"), sink.fired.get(0));
        assertEquals(validUntil, clock.getAsLong());
    }

    @Test
    void reArmingCancelsTheOldDeadline() {
        hold("ch-1", 1);
        clock.advanceMs(10_000);
        long later = clock.getAsLong() + VALIDITY_NANOS;
        assertTrue(ownership.confirm("ch-1", 1, later));
        fence.arm("ch-1", 1, later);
        assertEquals(1, timer.pending());

        clock.advanceMs(14_000);
        timer.runDue();
        assertNotNull(ownership.get("ch-1"));

        clock.advanceMs(10_000);
        timer.runDue();
        assertNull(ownership.get("ch-1"));
    }

    @Test
    void eachLeaseIsFencedAtItsOwnDeadline() {
        hold("ch-1", 1);
        clock.advanceMs(10_000);
        hold("ch-2", 1);

        clock.advanceMs(14_000);
        timer.runDue();
        assertNull(ownership.get("ch-1"));
        assertTrue(ownership.mustStop("ch-1"));
        assertNotNull(ownership.get("ch-2"));
        assertTrue(sink.fired.get(0).contains("stopped its 1 protected source;"), sink.fired.get(0));

        clock.advanceMs(10_000);
        timer.runDue();
        assertNull(ownership.get("ch-2"));
    }

    @Test
    void aConfirmationThatLandsFirstWins() {
        hold("ch-1", 1);
        clock.advanceMs(24_000);

        // The renewal's reply is processed just before the deadline task runs.
        assertTrue(ownership.confirm("ch-1", 1, clock.getAsLong() + VALIDITY_NANOS));
        timer.runDue();

        assertNotNull(ownership.get("ch-1"));
        assertEquals(0, sink.count(Code.PC102));
    }

    @Test
    void aDeadlineThatFiresFirstWinsAndTheLateReplyIsIgnored() {
        hold("ch-1", 1);
        clock.advanceMs(24_000);
        timer.runDue();

        assertFalse(ownership.confirm("ch-1", 1, clock.getAsLong() + VALIDITY_NANOS));
        assertTrue(ownership.isEmpty());
    }

    @Test
    void theDemotionLeavesTheMapAndSetsTheMarkerBeforeTheReconcilerHears() {
        reconciler.onQueue = id -> {
            assertNull(ownership.get(id));
            assertTrue(ownership.mustStop(id));
        };
        hold("ch-1", 1);
        clock.advanceMs(24_000);
        timer.runDue();

        assertEquals(1, reconciler.queued.size());
    }

    @Test
    void theKeeperCanExpireAPassedDeadlineWithoutTheFenceThread() {
        hold("ch-1", 1);
        clock.advanceMs(25_000);

        fence.expireDue();

        assertNull(ownership.get("ch-1"));
        assertEquals(1, sink.count(Code.PC102));
    }

    @Test
    void theWatchdogRaisesPc110AfterTwoHeartbeatsWithoutATickAndClearsWhenTicksResume() {
        long lastTick = clock.getAsLong();

        clock.advanceMs(20_000);
        fence.checkKeeper(lastTick);
        assertEquals(0, sink.count(Code.PC110));

        clock.advanceMs(1);
        fence.checkKeeper(lastTick);
        fence.checkKeeper(lastTick);
        assertEquals(1, sink.count(Code.PC110));

        fence.checkKeeper(clock.getAsLong());
        assertEquals(1, sink.cleared.size());
    }

    @Test
    void aReconcilerThatThrowsDoesNotStopTheSweep() {
        reconciler.onQueue = id -> {
            if (id.equals("ch-1")) {
                throw new RuntimeException("the reconciler is gone");
            }
        };
        hold("ch-1", 1);
        hold("ch-2", 1);

        clock.advanceMs(24_000);
        timer.runDue();

        assertNull(ownership.get("ch-1"));
        assertNull(ownership.get("ch-2"));
        assertTrue(ownership.mustStop("ch-1"));
        assertTrue(ownership.mustStop("ch-2"));
        assertEquals(1, sink.count(Code.PC102));
        assertTrue(sink.fired.get(0).contains("stopped its 2 protected sources"), sink.fired.get(0));
        assertEquals(1, sink.warnings.size());
    }

    @Test
    void aStaleEpochCannotConfirmDemoteOrReleaseTheLeaseHeldUnderANewerOne() {
        long validUntil = hold("ch-1", 2);
        assertFalse(ownership.isEmpty());

        assertFalse(ownership.confirm("ch-1", 1, validUntil + VALIDITY_NANOS));
        assertFalse(ownership.demote("ch-1", 1));
        assertFalse(ownership.release("ch-1", 1));

        assertNotNull(ownership.get("ch-1"));
        assertEquals(2, ownership.get("ch-1").epoch);
        assertEquals(validUntil, ownership.get("ch-1").validUntil);
        assertFalse(ownership.mustStop("ch-1"));
    }

    @Test
    void aTimerSchedulesOnTheExecutorItIsGivenAndCancelsWhatItScheduled() {
        ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(1);
        try {
            Timer.Task task = Timer.on(executor).after(TimeUnit.HOURS.toNanos(1), () -> { });
            task.cancel();

            assertTrue(((Future<?>) executor.getQueue().peek()).isCancelled());
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void aWatchdogThatFailsUnexpectedlyWarns() {
        Fence broken = new Fence(ownership, timer, () -> {
            throw new IllegalStateException("no clock");
        }, faults, new Messages(24, 90, 30), Timing.STANDARD, sink);

        broken.checkKeeper(clock.getAsLong());

        assertEquals(Collections.singletonList(
            "The keeper watchdog failed unexpectedly. java.lang.IllegalStateException: no clock"), sink.warnings);
    }

    @Test
    void aFenceThatFailsUnexpectedlyStillStopsTheSourceAndWarns() {
        reconciler.onQueue = id -> {
            throw new Error("the reconciler is gone");
        };
        hold("ch-1", 1);

        clock.advanceMs(24_000);
        timer.runDue();

        assertNull(ownership.get("ch-1"));
        assertTrue(ownership.mustStop("ch-1"));
        assertEquals(Collections.singletonList(
            "The self-fence failed unexpectedly for channel ch-1 (epoch 1). java.lang.Error: the reconciler is gone"),
            sink.warnings);
    }

    private long hold(String channelId, long epoch) {
        long validUntil = clock.getAsLong() + VALIDITY_NANOS;
        ownership.add(channelId, new Held(epoch, validUntil));
        fence.arm(channelId, epoch, validUntil);
        return validUntil;
    }
}
