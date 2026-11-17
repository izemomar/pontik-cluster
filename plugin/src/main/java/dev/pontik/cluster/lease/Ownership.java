// SPDX-License-Identifier: MPL-2.0
package dev.pontik.cluster.lease;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * The leases this process holds, and the channels whose source must stop. Only an acquisition
 * adds an entry. Every other change checks the epoch on the entry it reads, then replaces or
 * removes exactly that entry with the map's compare-and-set, so a late renewal and a firing fence
 * can never both win.
 */
public final class Ownership {

    private final ConcurrentHashMap<String, Held> held = new ConcurrentHashMap<>();
    private final Set<String> mustStop = ConcurrentHashMap.newKeySet();
    private final Reconciler reconciler;
    private final LongSupplier nanos;

    public Ownership(Reconciler reconciler, LongSupplier nanos) {
        this.reconciler = reconciler;
        this.nanos = nanos;
    }

    public Held get(String channelId) {
        return held.get(channelId);
    }

    public boolean isEmpty() {
        return held.isEmpty();
    }

    public int size() {
        return held.size();
    }

    public Map<String, Held> snapshot() {
        return new HashMap<>(held);
    }

    void add(String channelId, Held lease) {
        held.put(channelId, lease);
    }

    /**
     * Extends the lease's deadline if this process still holds it at this epoch, and returns whether
     * it did. The keeper calls this for each row a renewal returns.
     */
    boolean confirm(String channelId, long epoch, long validUntil) {
        Held e = held.get(channelId);
        return e != null && e.epoch == epoch && held.replace(channelId, e, new Held(epoch, validUntil));
    }

    /**
     * Demotes the lease if this epoch is still held and its deadline has passed. The fence calls this
     * when a deadline fires, and every keeper tick reaches it too.
     */
    boolean expire(String channelId, long epoch) {
        Held e = held.get(channelId);
        boolean removed = e != null && e.epoch == epoch && nanos.getAsLong() - e.validUntil >= 0
            && held.remove(channelId, e);
        if (removed) {
            stop(channelId);
        }
        return removed;
    }

    /**
     * Demotes the lease now and marks its source to stop, if this epoch is still held. The keeper calls
     * this when the database no longer confirms the epoch.
     */
    boolean demote(String channelId, long epoch) {
        boolean removed = release(channelId, epoch);
        if (removed) {
            stop(channelId);
        }
        return removed;
    }

    /**
     * Removes the lease from the map without a stop marker, if this epoch is still held. The keeper
     * calls this only once the source is stopped here, or when it hands the channel back so it runs as
     * it does without the plugin.
     */
    boolean release(String channelId, long epoch) {
        Held e = held.get(channelId);
        return e != null && e.epoch == epoch && held.remove(channelId, e);
    }

    public boolean mustStop(String channelId) {
        return mustStop.contains(channelId);
    }

    /**
     * Forgets the stop marker. The reconciler calls this once the source is stopped here, or once
     * this process holds the channel again.
     */
    public void clearMustStop(String channelId) {
        mustStop.remove(channelId);
    }

    // The entry has already left the map, so the hook, the reconciler and the status call see the
    // demotion at once. Then the marker, then the reconciler hears of it.
    private void stop(String channelId) {
        mustStop.add(channelId);
        reconciler.queue(channelId);
    }
}
