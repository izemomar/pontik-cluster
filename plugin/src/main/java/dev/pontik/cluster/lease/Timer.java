// SPDX-License-Identifier: MPL-2.0
package dev.pontik.cluster.lease;

import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/** A way to run an action after a delay. The fence uses one; the tests drive one by hand. */
public interface Timer {

    interface Task {
        void cancel();
    }

    Task after(long delayNanos, Runnable action);

    /** Returns a timer on the fence's own single-thread executor, which nothing else shares. */
    static Timer on(ScheduledExecutorService executor) {
        return (delayNanos, action) -> {
            ScheduledFuture<?> future = executor.schedule(action, Math.max(0, delayNanos), TimeUnit.NANOSECONDS);
            return () -> future.cancel(false);
        };
    }
}
