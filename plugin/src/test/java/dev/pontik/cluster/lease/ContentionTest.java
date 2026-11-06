// SPDX-License-Identifier: MPL-2.0
package dev.pontik.cluster.lease;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicIntegerArray;
import org.junit.jupiter.api.Test;

class ContentionTest {

    private static final int ROUNDS = 10_000;

    @Test
    void threeNodesRacingForOneFreeLeaseGiveExactlyOneWinnerEveryRound() throws Exception {
        Pg.reset();
        Pg.bootstrap(Pg.settings());
        Pg.execute("INSERT INTO pcl_lease (channel_id, lease_expires_at) "
            + "SELECT 'ch-' || g, clock_timestamp() AT TIME ZONE 'UTC' FROM generate_series(0, " + (ROUNDS - 1)
            + ") g");

        AtomicIntegerArray winners = new AtomicIntegerArray(ROUNDS);
        CyclicBarrier together = new CyclicBarrier(3);
        ExecutorService pool = Executors.newFixedThreadPool(3);
        List<Future<?>> nodes = new ArrayList<>();

        for (int n = 0; n < 3; n++) {
            Node node = new Node("srv-" + n, "node-" + n, "inst-" + n, "test");
            nodes.add(pool.submit(() -> {
                try (Link link = new Link(Pg.keeper())) {
                    Store store = new JdbcStore(link, node, 0);
                    for (int round = 0; round < ROUNDS; round++) {
                        together.await();
                        winners.addAndGet(round, acquire(store, "ch-" + round));
                    }
                }
                return null;
            }));
        }
        for (Future<?> node : nodes) {
            node.get();
        }
        pool.shutdown();

        for (int round = 0; round < ROUNDS; round++) {
            assertEquals(1, winners.get(round), "round " + round);
        }
    }

    private static int acquire(Store store, String channelId) throws SQLException {
        return store.acquire(new String[] {channelId}, 1, Timing.STANDARD.leaseMs).size();
    }
}
