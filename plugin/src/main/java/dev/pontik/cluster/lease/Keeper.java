// SPDX-License-Identifier: MPL-2.0
package dev.pontik.cluster.lease;

import dev.pontik.cluster.fault.Code;
import dev.pontik.cluster.fault.Faults;
import dev.pontik.cluster.fault.Messages;
import dev.pontik.cluster.fault.Names;
import dev.pontik.cluster.fault.Sink;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/**
 * The lease keeper. One thread runs a tick every heartbeat, at a fixed rate and never jittered:
 * renew, read back the config and the leases, write back a missing lease row, release what is due,
 * write this node's row, read the live peers. The acquisition follows on the same thread, at a
 * random offset after the tick.
 *
 * Every statement must fit the tick: before each one, the keeper skips the rest of the tick if the
 * statement could still be running when the next tick is due. The tick also ends at its first
 * failed statement. A slow or hung database therefore costs at most the rest of one tick and can
 * never delay the next renewal.
 *
 * The keeper calls no engine API. What it knows about the engine comes from {@link LocalState},
 * and what it decides goes to the reconciler through {@link Reconciler}.
 */
public final class Keeper {

    public enum State { RUNNING, DECLINED }

    private static final class Unowned {
        final long sinceNanos;
        final long sinceTick;

        Unowned(long sinceNanos, long sinceTick) {
            this.sinceNanos = sinceNanos;
            this.sinceTick = sinceTick;
        }
    }

    private static final class Due {
        final String channelId;
        final long epoch;
        final String reason;
        final boolean handBack;

        Due(String channelId, long epoch, String reason, boolean handBack) {
            this.channelId = channelId;
            this.epoch = epoch;
            this.reason = reason;
            this.handBack = handBack;
        }
    }

    private final Store store;
    private final Timing timing;
    private final Node node;
    private final Ownership ownership;
    private final LocalState local;
    private final Fence fence;
    private final Faults faults;
    private final Messages messages;
    private final Names names;
    private final Reconciler reconciler;
    private final Sink sink;
    private final LongSupplier nanos;
    private final LongSupplier jitterMs;

    private volatile State state = State.RUNNING;
    private volatile Store.ReadBack readBack;
    private volatile long readBackNanos;
    private volatile List<Store.Peer> peers = Collections.emptyList();
    private volatile long lastTickNanos;

    // Touched only on the keeper's thread.
    private ScheduledExecutorService executor;
    private long tick;
    private long tickStartNanos;
    private long nextDueNanos;
    private int mismatches;
    private int matches;
    private boolean blind;
    private final Map<String, Unowned> unowned = new HashMap<>();
    private final Map<String, Long> lostAtRenewal = new HashMap<>();

    public Keeper(Store store, Timing timing, Node node, Ownership ownership, LocalState local, Fence fence,
            Faults faults, Messages messages, Names names, Reconciler reconciler, Sink sink, LongSupplier nanos,
            LongSupplier jitterMs, Store.ReadBack initial) {
        this.store = store;
        this.timing = timing;
        this.node = node;
        this.ownership = ownership;
        this.local = local;
        this.fence = fence;
        this.faults = faults;
        this.messages = messages;
        this.names = names;
        this.reconciler = reconciler;
        this.sink = sink;
        this.nanos = nanos;
        this.jitterMs = jitterMs;
        this.readBack = initial;
        this.readBackNanos = nanos.getAsLong();
        this.lastTickNanos = nanos.getAsLong();
    }

    /** Ticks at a fixed rate on the given single-thread executor. */
    void start(ScheduledExecutorService executor) {
        this.executor = executor;
        this.lastTickNanos = nanos.getAsLong();
        executor.scheduleAtFixedRate(this::scheduledTick, 0, timing.heartbeatMs, TimeUnit.MILLISECONDS);
    }

    private void scheduledTick() {
        try {
            tick();
            if (state == State.RUNNING) {
                long at = tickStartNanos + TimeUnit.MILLISECONDS.toNanos(jitterMs.getAsLong());
                executor.schedule(this::acquire, Math.max(0, at - nanos.getAsLong()), TimeUnit.NANOSECONDS);
            }
        } catch (RejectedExecutionException e) {
            // The executor rejects only once it is shut down, and then the coordinator is stopping.
        } catch (Throwable t) {
            sink.warn("The lease keeper could not schedule its acquisition.", t);
        }
    }

    /** Runs one tick on the keeper's thread. The tests in this package drive it by hand. */
    void tick() {
        tickStartNanos = nanos.getAsLong();
        lastTickNanos = tickStartNanos;
        nextDueNanos = tickStartNanos + TimeUnit.MILLISECONDS.toNanos(timing.heartbeatMs);
        tick++;

        try {
            fence.expireDue();
            if (renew() && refresh() && recreateMissingLeases() && release() && writeNodeRow()) {
                readPeers();
                if (ownership.isEmpty()) {
                    faults.clear(Code.PC102, null);
                }
            }
        } catch (Throwable t) {
            sink.warn("The lease keeper's tick failed unexpectedly.", t);
        }
    }

    /** Renews every held lease. It runs first in the tick, so no slower statement can delay it. */
    private boolean renew() {
        Map<String, Held> held = ownership.snapshot();
        if (held.isEmpty()) {
            return true;
        }
        if (!affordable()) {
            return false;
        }

        List<String> ids = new ArrayList<>(held.keySet());
        Long[] epochs = new Long[ids.size()];
        Boolean[] stopped = new Boolean[ids.size()];
        for (int i = 0; i < ids.size(); i++) {
            epochs[i] = held.get(ids.get(i)).epoch;
            stopped[i] = local.operatorStop(ids.get(i));
        }

        long sent = nanos.getAsLong();
        List<Store.Renewed> rows;
        try {
            rows = store.renew(ids.toArray(new String[0]), epochs, stopped, timing.leaseMs);
        } catch (SQLException e) {
            // Nothing is confirmed and the deadlines stand; PC-102 covers a holder.
            return false;
        }

        long validUntil = sent + TimeUnit.MILLISECONDS.toNanos(timing.validityMs());
        Set<String> returned = new HashSet<>();
        boolean confirmedAny = false;
        for (Store.Renewed row : rows) {
            Held mine = held.get(row.channelId);
            if (mine != null && mine.epoch == row.epoch) {
                returned.add(row.channelId);
            }
            // A row for a lease the fence demoted while the reply was on its way is ignored here.
            if (ownership.confirm(row.channelId, row.epoch, validUntil)) {
                fence.arm(row.channelId, row.epoch, validUntil);
                confirmedAny = true;
            }
        }

        for (Map.Entry<String, Held> e : held.entrySet()) {
            if (!returned.contains(e.getKey()) && ownership.demote(e.getKey(), e.getValue().epoch)) {
                lostAtRenewal.put(e.getKey(), e.getValue().epoch);
            }
        }

        if (confirmedAny) {
            faults.clear(Code.PC102, null);
        }
        return true;
    }

    private boolean refresh() {
        if (!affordable()) {
            return false;
        }

        Store.ReadBack fresh;
        try {
            fresh = store.readBack();
        } catch (SQLException e) {
            // One WARN after two heartbeats without a read-back; a shorter gap logs nothing.
            long unread = nanos.getAsLong() - readBackNanos;
            if (!blind && unread >= TimeUnit.MILLISECONDS.toNanos(2 * timing.heartbeatMs)) {
                blind = true;
                String state = e.getSQLState() == null ? "unknown" : e.getSQLState();
                sink.warn("This node has not read the lease tables for "
                    + TimeUnit.MILLISECONDS.toSeconds(2 * timing.heartbeatMs) + " seconds (SQLState "
                    + state + "), so it cannot take over a channel. Please check this node's "
                    + "connection to the database and its access to the lease tables.", null);
            }
            return false;
        }
        if (blind) {
            blind = false;
            sink.info("This node reads the lease tables again.");
        }

        Store.ReadBack previous = readBack;
        readBack = fresh;
        readBackNanos = nanos.getAsLong();

        queueModeChanges(previous, fresh);
        checkHeld(fresh);
        countUnowned(fresh);
        return true;
    }

    // A channel whose mode changed needs the reconciler: an opt-in gates it, an opt-out restores it.
    private void queueModeChanges(Store.ReadBack previous, Store.ReadBack fresh) {
        Set<String> ids = new HashSet<>(fresh.rows.keySet());
        ids.addAll(previous.rows.keySet());
        for (String id : ids) {
            Store.Row before = previous.rows.get(id);
            Store.Row after = fresh.rows.get(id);
            String was = before == null ? Store.UNMANAGED : before.mode;
            String is = after == null ? Store.UNMANAGED : after.mode;
            if (!was.equals(is)) {
                reconciler.queue(id);
            }
        }
    }

    // A lease we hold that the row now gives to another owner or epoch is lost: PC-101.
    private void checkHeld(Store.ReadBack fresh) {
        for (Map.Entry<String, Held> e : ownership.snapshot().entrySet()) {
            Store.Row row = fresh.rows.get(e.getKey());
            if (row != null && row.hasLease() && namesOther(row, e.getValue().epoch)) {
                ownership.demote(e.getKey(), e.getValue().epoch);
                String owner = row.owner == null ? null : ownerName(row.owner);
                faults.raise(Code.PC101, e.getKey(), messages.pc101Owner(owner, row.epoch, Times.iso(fresh.dbNow)));
            }
        }
        // A renewal returned no row for a lease whose deadline had not passed. We report it only if the
        // database now names an owner other than this process at that epoch.
        for (Map.Entry<String, Long> e : lostAtRenewal.entrySet()) {
            Store.Row row = fresh.rows.get(e.getKey());
            if (row != null && row.hasLease() && row.owner != null && namesOther(row, e.getValue())) {
                faults.raise(Code.PC101, e.getKey(), messages.pc101Owner(ownerName(row.owner), row.epoch,
                    Times.iso(fresh.dbNow)));
            }
        }
        lostAtRenewal.clear();
    }

    private boolean namesOther(Store.Row row, long epoch) {
        return !node.id.equals(row.owner) || row.epoch == null || row.epoch != epoch;
    }

    // PC-103 counts, on this node's clock, how long a channel deployed and eligible here has had
    // no owner. The same record tells the acquisition which candidates it saw at an earlier tick.
    // A node that is not running counts nothing and clears every PC-103 it raised.
    private void countUnowned(Store.ReadBack fresh) {
        // A declined node runs every channel as it does without the plugin, and PC-107 says why.
        if (state != State.RUNNING) {
            for (String id : unowned.keySet()) {
                faults.clear(Code.PC103, id);
            }
            unowned.clear();
            return;
        }

        long now = nanos.getAsLong();
        Set<String> seen = new HashSet<>();

        for (Store.Row row : fresh.rows.values()) {
            String id = row.channelId;
            if (!row.singleOwner() || !local.deployedHere(id) || local.refusal(id) != null) {
                continue;
            }
            if (ownership.get(id) != null || row.held(fresh.dbNow)) {
                continue;
            }
            seen.add(id);
            Unowned since = unowned.get(id);
            if (since == null) {
                since = new Unowned(now, tick);
                unowned.put(id, since);
            }
            if (local.configuredStarted(id)
                    && now - since.sinceNanos > TimeUnit.MILLISECONDS.toNanos(timing.alarmMs())) {
                faults.raise(Code.PC103, id, messages.pc103(local.stoppedHere(id)));
            }
        }

        for (String id : new ArrayList<>(unowned.keySet())) {
            if (!seen.contains(id)) {
                unowned.remove(id);
                faults.clear(Code.PC103, id);
            }
        }
    }

    // A single-owner channel whose lease row is missing counts as unowned until we write it back.
    private boolean recreateMissingLeases() {
        for (Store.Row row : readBack.rows.values()) {
            if (row.singleOwner() && !row.hasLease()) {
                if (!affordable()) {
                    return false;
                }
                try {
                    store.insertLease(row.channelId);
                } catch (SQLException e) {
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * Releases what is due. We release a lease only once its source is not running here: after two idle
     * sweeps in a row found the channel gone, or when the source could not start (PC-118). The exception
     * is a channel we hand back so it runs as it does without the plugin: a change to unmanaged, a
     * refusal, or a node that declines.
     */
    private boolean release() {
        Map<String, Held> held = ownership.snapshot();
        if (held.isEmpty()) {
            return true;
        }
        if (!affordable()) {
            return false;
        }

        List<Due> due = new ArrayList<>();
        long now = nanos.getAsLong();
        for (Map.Entry<String, Held> e : held.entrySet()) {
            Due d = due(e.getKey(), e.getValue().epoch, now);
            if (d != null) {
                due.add(d);
            }
        }
        if (due.isEmpty()) {
            return true;
        }
        return send(due);
    }

    // A due lease leaves the map here. A release that a redeploy could cancel is checked and made under
    // LocalState's lock, so the deploy hook sees the lease either still held or already gone.
    private Due due(String id, long epoch, long now) {
        if (state == State.DECLINED) {
            return handBack(id, epoch, "node declined");
        }
        Store.Row row = readBack.rows.get(id);
        if (row == null) {
            return handBack(id, epoch, "the channel was deleted");
        }
        if (!row.singleOwner()) {
            return handBack(id, epoch, "set to unmanaged");
        }
        LocalState.Release why = local.release(id, now, () -> ownership.release(id, epoch));
        if (why == null) {
            return null;
        }
        switch (why) {
            case REFUSED:
                return new Due(id, epoch, "refused (" + local.refusal(id) + ")", true);
            case START_FAILED:
                return new Due(id, epoch, "could not start it (PC-118)", false);
            default:
                return new Due(id, epoch, "undeployed here", false);
        }
    }

    private Due handBack(String id, long epoch, String reason) {
        return ownership.release(id, epoch) ? new Due(id, epoch, reason, true) : null;
    }

    private boolean send(List<Due> due) {
        String[] ids = new String[due.size()];
        Long[] epochs = new Long[due.size()];
        for (int i = 0; i < due.size(); i++) {
            ids[i] = due.get(i).channelId;
            epochs[i] = due.get(i).epoch;
        }

        List<String> released;
        try {
            released = store.release(ids, epochs);
        } catch (SQLException e) {
            // The entries are gone already, so this node no longer renews them: they expire within one lease.
            // We forget their release marks all the same, so a lease taken again later starts clean.
            for (Due d : due) {
                local.released(d.channelId);
            }
            return false;
        }

        for (Due d : due) {
            local.released(d.channelId);
            if (released.contains(d.channelId)) {
                sink.info("Released the lease on " + names.channelName(d.channelId) + ": " + d.reason + ".");
            } else {
                faults.raise(Code.PC101, d.channelId, messages.pc101Release(Times.iso(readBack.dbNow)));
            }
            if (d.handBack) {
                reconciler.queue(d.channelId);
            }
        }
        return true;
    }

    private boolean writeNodeRow() {
        if (!affordable()) {
            return false;
        }
        String previous;
        try {
            previous = store.upsertNode();
        } catch (SQLException e) {
            // The row keeps its last time, and the next tick retries.
            return false;
        }

        // One mismatch is this process's own first tick after a restart; two in a row are another process.
        if (previous != null && !previous.equals(node.instance)) {
            matches = 0;
            if (++mismatches >= 2) {
                faults.raise(Code.PC119, null, messages.pc119(previous));
            }
        } else {
            mismatches = 0;
            if (++matches >= 2) {
                faults.clear(Code.PC119, null);
            }
        }
        return true;
    }

    private void readPeers() {
        if (!affordable()) {
            return;
        }
        List<Store.Peer> live;
        try {
            live = store.peers(timing.peerWindowMs());
        } catch (SQLException e) {
            return;
        }
        peers = Collections.unmodifiableList(live);

        for (Store.Peer peer : live) {
            if (peer.protocol != Node.PROTOCOL && state == State.RUNNING) {
                state = State.DECLINED;
                faults.raise(Code.PC107, null, messages.pc107Peer(peerName(peer), peer.protocol));
                for (String id : readBack.rows.keySet()) {
                    reconciler.queue(id);
                }
            }
        }
    }

    /**
     * Acquires free channels in one statement that names every candidate. Candidates this node saw
     * unowned at an earlier tick are carried and taken whole; the new ones fill this node up to its
     * fair share of every single-owner channel, so survivors that tick at different moments spread a
     * failed node's channels in one round, and a held channel never moves.
     */
    void acquire() {
        try {
            if (state != State.RUNNING) {
                return;
            }
            Store.ReadBack rb = readBack;
            long now = nanos.getAsLong();
            List<String> carried = new ArrayList<>();
            List<String> fresh = new ArrayList<>();
            int singleOwner = 0;

            for (Store.Row row : rb.rows.values()) {
                if (!row.singleOwner()) {
                    continue;
                }
                singleOwner++;
                String id = row.channelId;
                if (ownership.get(id) != null || !row.hasLease() || row.held(rb.dbNow) || !local.eligible(id, now)) {
                    continue;
                }
                Unowned since = unowned.get(id);
                if (since != null && since.sinceTick < tick) {
                    carried.add(id);
                } else {
                    fresh.add(id);
                }
            }
            if (carried.isEmpty() && fresh.isEmpty()) {
                return;
            }

            int live = peers.size() + 1;
            int fair = (singleOwner + live - 1) / live;
            int share = carried.size() + Math.min(fresh.size(), Math.max(1, fair - ownership.size()));
            List<String> candidates = new ArrayList<>(carried);
            candidates.addAll(fresh);

            if (!affordable()) {
                return;
            }
            long sent = nanos.getAsLong();
            List<Store.Acquired> rows;
            try {
                rows = store.acquire(candidates.toArray(new String[0]), share, timing.leaseMs);
            } catch (SQLException e) {
                // A serialization failure or a lost reply. We add nothing to the map, so this node never reads;
                // a lease the database did grant runs out within one lease.
                return;
            }

            long validUntil = sent + TimeUnit.MILLISECONDS.toNanos(timing.validityMs());
            for (Store.Acquired row : rows) {
                // The lease is visible to the reconciler once it is added, so the row's stop flag goes first.
                local.acquired(row.channelId, row.stopped);
                ownership.add(row.channelId, new Held(row.epoch, validUntil));
                fence.arm(row.channelId, row.epoch, validUntil);
                unowned.remove(row.channelId);
                faults.clear(Code.PC103, row.channelId);
                sink.info("Acquired the lease on " + names.channelName(row.channelId) + " (epoch " + row.epoch + ").");
                reconciler.queue(row.channelId);
            }
        } catch (Throwable t) {
            sink.warn("The lease keeper's acquisition failed unexpectedly.", t);
        }
    }

    /**
     * Releases every held lease in one statement, then writes the node marker, so the other nodes take
     * this node's channels at their next tick. The coordinator calls this once the engine has undeployed
     * every channel and the keeper's thread has ended. A connection error gets one reopen and one retry.
     * A release that still fails gets one warning, and the leases run out within one lease; the marker
     * is written anyway, unless the database was unreachable both times.
     */
    void shutdown() {
        Map<String, Held> held = ownership.snapshot();
        List<String> ids = new ArrayList<>();
        List<Long> epochs = new ArrayList<>();
        for (Map.Entry<String, Held> e : held.entrySet()) {
            if (ownership.release(e.getKey(), e.getValue().epoch)) {
                ids.add(e.getKey());
                epochs.add(e.getValue().epoch);
            }
        }

        if (!ids.isEmpty()) {
            List<String> released = null;
            int lost = 0;
            for (int attempt = 0; attempt < 2 && released == null; attempt++) {
                try {
                    released = store.release(ids.toArray(new String[0]), epochs.toArray(new Long[0]));
                } catch (SQLException e) {
                    // Only a lost connection is worth a retry; a canceled release would only wait on the
                    // same row lock again.
                    if (!Connections.isConnectionError(e)) {
                        break;
                    }
                    lost++;
                }
            }
            if (released == null) {
                sink.warn("The release at shutdown failed, so the leases expire on their own within one "
                    + "lease.", null);
                // With the connection gone twice, the marker could only lengthen the shutdown.
                if (lost == 2) {
                    return;
                }
            } else {
                for (String id : released) {
                    local.released(id);
                    sink.info("Released the lease on " + names.channelName(id) + ": shutting down.");
                }
            }
        }

        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                store.markGone(timing.peerWindowMs());
                return;
            } catch (SQLException e) {
                if (!Connections.isConnectionError(e)) {
                    return;
                }
            }
        }
    }

    /** Returns whether a node other than this one holds the channel's lease in the given read-back. */
    public boolean anotherHolder(Store.ReadBack rb, String channelId) {
        Store.Row row = rb.rows.get(channelId);
        if (row == null || !row.held(rb.dbNow)) {
            return false;
        }
        Held mine = ownership.get(channelId);
        return mine == null || namesOther(row, mine.epoch);
    }

    public State state() {
        return state;
    }

    public Store.ReadBack readBack() {
        return readBack;
    }

    /** Returns the age of the last read-back on this node's clock, in milliseconds. */
    public long readBackAgeMs() {
        return TimeUnit.NANOSECONDS.toMillis(nanos.getAsLong() - readBackNanos);
    }

    public long lastTickNanos() {
        return lastTickNanos;
    }

    /** Returns the live peers the last tick read. */
    public List<Store.Peer> peers() {
        return peers;
    }

    /** Returns the name an operator recognizes for a node, its server name or else its server ID. */
    public String ownerName(String nodeId) {
        if (nodeId.equals(node.id)) {
            return node.name;
        }
        for (Store.Peer peer : peers) {
            if (peer.nodeId.equals(nodeId)) {
                return peerName(peer);
            }
        }
        return nodeId;
    }

    private static String peerName(Store.Peer peer) {
        return peer.nodeName == null ? peer.nodeId : peer.nodeName;
    }

    // A tick that Coordinator.stop() interrupts sends nothing more.
    private boolean affordable() {
        if (Thread.currentThread().isInterrupted()) {
            return false;
        }
        long budget = TimeUnit.MILLISECONDS.toNanos(timing.statementBudgetMs());
        return nanos.getAsLong() + budget - nextDueNanos <= 0;
    }
}
