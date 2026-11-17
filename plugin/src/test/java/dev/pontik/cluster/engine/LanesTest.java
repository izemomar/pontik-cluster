// SPDX-License-Identifier: MPL-2.0
package dev.pontik.cluster.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.pontik.cluster.fault.RecordingSink;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class LanesTest {

    private final RecordingSink sink = new RecordingSink();

    /** Runs queued work only when the test says so. */
    private final Deque<Runnable> pending = new ArrayDeque<>();
    private final Lanes lanes = new Lanes(pending::add, sink);
    private final List<String> runs = new ArrayList<>();

    @Test
    void aChannelQueuedTwiceBeforeItRunsRunsOnce() {
        lanes.bind(runs::add);

        lanes.queue("ch-1");
        lanes.queue("ch-1");
        drain();

        assertEquals(Arrays.asList("ch-1"), runs);
    }

    @Test
    void aChannelQueuedWhileItsRunIsInProgressRunsOnceMoreAfterIt() {
        lanes.bind(id -> {
            runs.add(id);
            if (runs.size() == 1) {
                // The keeper demotes the lease while the lane is busy with the channel.
                lanes.queue(id);
                lanes.queue(id);
            }
        });

        lanes.queue("ch-1");
        drain();

        assertEquals(Arrays.asList("ch-1", "ch-1"), runs);
        assertEquals(0, pending.size());
    }

    @Test
    void aRunThatThrowsLogsOneWarningAndTheLaneTakesWorkAgain() {
        lanes.bind(id -> {
            runs.add(id);
            throw new IllegalStateException("boom");
        });

        lanes.queue("ch-1");
        drain();
        lanes.queue("ch-1");
        drain();

        assertEquals(2, runs.size());
        assertEquals(2, sink.warnings.size());
    }

    @Test
    void aLaneBeforeItsWorkIsBoundDoesNothing() {
        lanes.queue("ch-1");
        drain();

        assertTrue(runs.isEmpty());
    }

    @Test
    void neverTwoRunsOfOneChannelAtOnceOnARealPool() throws Exception {
        ExecutorService pool = Executors.newCachedThreadPool();
        try {
            Lanes real = new Lanes(pool, sink);
            AtomicInteger inside = new AtomicInteger();
            AtomicInteger most = new AtomicInteger();
            AtomicInteger total = new AtomicInteger();
            CountDownLatch entered = new CountDownLatch(1);
            CountDownLatch queued = new CountDownLatch(1);
            real.bind(id -> {
                most.accumulateAndGet(inside.incrementAndGet(), Math::max);
                entered.countDown();
                try {
                    queued.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                total.incrementAndGet();
                inside.decrementAndGet();
            });

            real.queue("ch-1");
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            for (int i = 0; i < 50; i++) {
                real.queue("ch-1");
            }
            queued.countDown();
            pool.shutdown();
            assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS));

            assertEquals(1, most.get());
            assertEquals(2, total.get());
        } finally {
            pool.shutdownNow();
        }
    }

    private void drain() {
        while (!pending.isEmpty()) {
            pending.poll().run();
        }
    }
}
