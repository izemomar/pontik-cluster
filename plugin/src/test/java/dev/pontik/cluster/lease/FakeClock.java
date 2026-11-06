// SPDX-License-Identifier: MPL-2.0
package dev.pontik.cluster.lease;

import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/** A nanoTime the tests move by hand. It starts far from zero, as nanoTime may. */
final class FakeClock implements LongSupplier {

    private long nanos = 1_000_000_000_000L;

    @Override
    public synchronized long getAsLong() {
        return nanos;
    }

    synchronized void advanceMs(long ms) {
        nanos += TimeUnit.MILLISECONDS.toNanos(ms);
    }

    synchronized long ms() {
        return TimeUnit.NANOSECONDS.toMillis(nanos);
    }
}
