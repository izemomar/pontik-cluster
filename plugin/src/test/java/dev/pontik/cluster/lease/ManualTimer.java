// SPDX-License-Identifier: MPL-2.0
package dev.pontik.cluster.lease;

import java.util.ArrayList;
import java.util.List;

/** A timer that runs its tasks only when the test says the time has come. */
final class ManualTimer implements Timer {

    private static final class Scheduled implements Task {
        final long dueAt;
        final Runnable action;
        boolean canceled;

        Scheduled(long dueAt, Runnable action) {
            this.dueAt = dueAt;
            this.action = action;
        }

        @Override
        public void cancel() {
            canceled = true;
        }
    }

    private final FakeClock clock;
    private final List<Scheduled> tasks = new ArrayList<>();

    ManualTimer(FakeClock clock) {
        this.clock = clock;
    }

    @Override
    public Task after(long delayNanos, Runnable action) {
        Scheduled task = new Scheduled(clock.getAsLong() + delayNanos, action);
        tasks.add(task);
        return task;
    }

    /** Runs every task that is due at the clock's time and has not been canceled. */
    void runDue() {
        for (Scheduled task : new ArrayList<>(tasks)) {
            if (!task.canceled && clock.getAsLong() - task.dueAt >= 0) {
                tasks.remove(task);
                task.action.run();
            }
        }
    }

    int pending() {
        int pending = 0;
        for (Scheduled task : tasks) {
            if (!task.canceled) {
                pending++;
            }
        }
        return pending;
    }
}
