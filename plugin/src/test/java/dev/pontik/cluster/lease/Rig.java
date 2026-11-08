// SPDX-License-Identifier: MPL-2.0
package dev.pontik.cluster.lease;

import dev.pontik.cluster.fault.Faults;
import dev.pontik.cluster.fault.FixedNames;
import dev.pontik.cluster.fault.Messages;
import dev.pontik.cluster.fault.RecordingSink;
import java.util.function.LongSupplier;

/**
 * A real keeper, fence and ownership map on a fake database and a hand-moved clock, for the tests of
 * other packages. The tests here drive the same parts directly.
 */
public final class Rig {

    public final RecordingSink sink = new RecordingSink();
    public final FixedNames names = new FixedNames("srv-a", "node-a");
    public final Faults faults = new Faults(sink, names);
    public final Messages messages = new Messages(24, 90, 30);
    public final LocalState local = new LocalState(Timing.STANDARD);
    public final Ownership ownership;
    public final Keeper keeper;

    private final FakeClock clock = new FakeClock();
    private final Node me = new Node("srv-a", "node-a", "inst-a", "test");
    private final FakeStore store = new FakeStore(clock, me);
    private final ManualTimer timer = new ManualTimer(clock);

    public Rig(Reconciler reconciler) {
        ownership = new Ownership(reconciler, clock);
        Fence fence = new Fence(ownership, timer, clock, faults, messages, Timing.STANDARD, sink);
        keeper = new Keeper(store, Timing.STANDARD, me, ownership, local, fence, faults, messages, names, reconciler,
            sink, clock, () -> 0L, Store.ReadBack.EMPTY);
    }

    /** Adds a live peer that speaks the given protocol, and reads it at the next tick. */
    public void peer(String nodeId, String nodeName, int protocol) {
        store.peers.add(new Store.Peer(nodeId, nodeName, protocol));
        keeper.tick();
    }

    public LongSupplier clock() {
        return clock;
    }

    public void advanceMs(long ms) {
        clock.advanceMs(ms);
        timer.runDue();
    }

    /** Makes each channel single-owner with a free lease row, and reads it back. */
    public void protect(String... channelIds) {
        store.protect(channelIds);
        keeper.tick();
    }

    /** Declares the channel rereadable in its config row, and reads it back. */
    public void rereadable(String channelId) {
        store.rereadable.add(channelId);
        keeper.tick();
    }

    /** Gives the channel's lease to another node, and reads it back. */
    public void heldBy(String channelId, String owner) {
        store.holdBy(channelId, owner, 7);
        keeper.tick();
    }

    /** Has this node acquire every channel it may, as the jittered acquisition after a tick does. */
    public void acquire() {
        keeper.acquire();
    }

    public void tick() {
        keeper.tick();
    }

    /** Marks the lease row of a channel as stopped by an operator, as a crashed owner's last renewal left it. */
    public void stoppedRow(String channelId) {
        store.leases.get(channelId).stopped = true;
    }

    /** Lets the channel's lease run out in the database, with the row still naming its last owner. */
    public void expire(String channelId) {
        store.leases.get(channelId).expiresAt = store.dbNow;
        keeper.tick();
    }

    /** Deletes the channel's config row and keeps its lease row, as a channel delete does, and reads it back. */
    public void delete(String channelId) {
        store.modes.remove(channelId);
        keeper.tick();
    }

    /** Sets the channel's mode in the database, and reads it back. */
    public void mode(String channelId, String mode) {
        store.modes.put(channelId, mode);
        keeper.tick();
    }
}
