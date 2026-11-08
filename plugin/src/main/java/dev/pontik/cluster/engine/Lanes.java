// SPDX-License-Identifier: MPL-2.0
package dev.pontik.cluster.engine;

import dev.pontik.cluster.fault.Sink;
import dev.pontik.cluster.lease.Reconciler;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

/**
 * One lane per channel: a queue of channel IDs in which each channel waits at most once, with at
 * most one run per channel at a time, so at most one engine call is in flight per channel. A channel
 * queued while its run is in progress runs once more after it, so no change is ever missed.
 */
final class Lanes implements Reconciler {

    private enum Lane { QUEUED, RUNNING, AGAIN }

    private final Executor executor;
    private final Sink sink;
    // A channel is here while its run is queued or in progress.
    private final Map<String, Lane> lanes = new HashMap<>();
    private volatile Consumer<String> work;

    Lanes(Executor executor, Sink sink) {
        this.executor = executor;
        this.sink = sink;
    }

    /** Sets what a run does. Until then, a run does nothing. */
    void bind(Consumer<String> work) {
        this.work = work;
    }

    @Override
    public void queue(String channelId) {
        try {
            synchronized (lanes) {
                Lane lane = lanes.get(channelId);
                if (lane == Lane.RUNNING) {
                    lanes.put(channelId, Lane.AGAIN);
                }
                if (lane != null) {
                    return;
                }
                lanes.put(channelId, Lane.QUEUED);
            }
            executor.execute(() -> run(channelId));
        } catch (Throwable t) {
            // The executor refuses work only once the plugin is stopping.
            synchronized (lanes) {
                lanes.remove(channelId);
            }
        }
    }

    private void run(String channelId) {
        while (true) {
            synchronized (lanes) {
                lanes.put(channelId, Lane.RUNNING);
            }
            Consumer<String> current = work;
            try {
                if (current != null) {
                    current.accept(channelId);
                }
            } catch (Throwable t) {
                sink.warn("The reconciler failed on one channel; the next sweep tries again.", t);
            }
            synchronized (lanes) {
                if (lanes.get(channelId) != Lane.AGAIN) {
                    lanes.remove(channelId);
                    return;
                }
            }
        }
    }
}
