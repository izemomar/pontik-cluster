// SPDX-License-Identifier: MPL-2.0
package dev.pontik.cluster.lease;

import dev.pontik.cluster.fault.Code;
import dev.pontik.cluster.fault.Faults;
import dev.pontik.cluster.fault.Messages;
import dev.pontik.cluster.fault.Names;
import dev.pontik.cluster.fault.Sink;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/**
 * The self-fence. Each held lease has a deadline on this node's nanoTime, anchored at the moment
 * its last confirmed renewal was sent. When a deadline passes, this node stops reading that
 * channel, whatever the keeper is doing. The fence also watches the keeper.
 */
public final class Fence {

    private final Ownership ownership;
    private final Timer timer;
    private final LongSupplier nanos;
    private final Faults faults;
    private final Messages messages;
    private final Timing timing;
    private final Names names;
    private final Sink sink;
    private final Map<String, Timer.Task> deadlines = new ConcurrentHashMap<>();

    public Fence(Ownership ownership, Timer timer, LongSupplier nanos, Faults faults, Messages messages,
            Timing timing, Names names, Sink sink) {
        this.ownership = ownership;
        this.timer = timer;
        this.nanos = nanos;
        this.faults = faults;
        this.messages = messages;
        this.timing = timing;
        this.names = names;
        this.sink = sink;
    }

    /** Schedules this lease's deadline and cancels the one before it. */
    void arm(String channelId, long epoch, long validUntil) {
        Timer.Task next = timer.after(validUntil - nanos.getAsLong(), () -> fire(channelId, epoch));
        Timer.Task previous = deadlines.put(channelId, next);
        if (previous != null) {
            previous.cancel();
        }
    }

    /**
     * Demotes every lease whose deadline has passed. Leases renewed together share a deadline, so
     * one firing stops them all and raises one PC-102. The keeper calls this at every tick too,
     * so the deadlines hold even if the fence's thread dies.
     */
    void expireDue() {
        int fenced = 0;
        for (Map.Entry<String, Held> e : ownership.snapshot().entrySet()) {
            try {
                if (ownership.expire(e.getKey(), e.getValue().epoch)) {
                    fenced++;
                }
            } catch (RuntimeException ex) {
                // Only the reconciler can throw here, once the lease has left the map and
                // its marker is set. We count the lease as fenced and go on to the next one.
                fenced++;
                sink.warn("This node gave up the lease on \"" + names.channelName(e.getKey()) + "\" in time, but "
                    + "could not queue the stop of its source; its next channel check tries again.", ex);
            }
        }
        if (fenced > 0) {
            faults.raise(Code.PC102, null, messages.pc102(fenced));
        }
    }

    /** Raises PC-110 while the keeper has not started a tick within two heartbeats. */
    void checkKeeper(long lastTickNanos) {
        try {
            long idle = nanos.getAsLong() - lastTickNanos;
            if (idle > TimeUnit.MILLISECONDS.toNanos(timing.watchdogMs())) {
                faults.raise(Code.PC110, null, messages.pc110(TimeUnit.NANOSECONDS.toSeconds(idle)));
            } else {
                faults.clear(Code.PC110, null);
            }
        } catch (Throwable t) {
            sink.warn("This node's check that its lease renewals still run failed unexpectedly.", t);
        }
    }

    private void fire(String channelId, long epoch) {
        try {
            expireDue();
        } catch (Throwable t) {
            sink.warn("This node could not stop polling \"" + names.channelName(channelId) + "\" at its lease deadline "
                + "(epoch " + epoch + "); it tries again at its next lease renewal round and channel check.", t);
        }
    }
}
