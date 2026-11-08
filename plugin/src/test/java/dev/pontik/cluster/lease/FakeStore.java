// SPDX-License-Identifier: MPL-2.0
package dev.pontik.cluster.lease;

import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * An in-memory database for the keeper's unit tests. It keeps lease and config rows the way the
 * real statements would, records every call with the clock's time, and can be told to make each
 * statement slow, hang or fail.
 */
final class FakeStore implements Store {

    interface Behavior {
        void before(String statement) throws SQLException;
    }

    static final class Lease {
        String owner;
        long epoch;
        LocalDateTime expiresAt;
        boolean stopped;
    }

    final List<String> calls = new ArrayList<>();
    final List<Long> callTimes = new ArrayList<>();
    final List<String[]> acquireCandidates = new ArrayList<>();
    final List<Integer> acquireShares = new ArrayList<>();
    final Map<String, String> modes = new LinkedHashMap<>();
    final Map<String, Lease> leases = new HashMap<>();
    final List<Peer> peers = new ArrayList<>();
    final Set<String> rereadable = new HashSet<>();
    String previousInstance;
    LocalDateTime dbNow = LocalDateTime.of(2026, 11, 4, 10, 0);
    Behavior behavior = statement -> { };

    private final FakeClock clock;
    private final Node node;

    FakeStore(FakeClock clock, Node node) {
        this.clock = clock;
        this.node = node;
    }

    /** Makes each channel single-owner and gives it a free lease row. */
    void protect(String... channelIds) {
        for (String id : channelIds) {
            modes.put(id, SINGLE_OWNER);
            leases.put(id, new Lease());
            leases.get(id).expiresAt = dbNow;
        }
    }

    void holdBy(String channelId, String owner, long epoch) {
        Lease lease = leases.get(channelId);
        lease.owner = owner;
        lease.epoch = epoch;
        lease.expiresAt = dbNow.plusSeconds(30);
    }

    @Override
    public List<Renewed> renew(String[] channelIds, Long[] epochs, Boolean[] stopped, long leaseMs)
            throws SQLException {
        call("renew");
        List<Renewed> out = new ArrayList<>();
        for (int i = 0; i < channelIds.length; i++) {
            Lease lease = leases.get(channelIds[i]);
            if (lease != null && node.id.equals(lease.owner) && lease.epoch == epochs[i]
                    && lease.expiresAt.isAfter(dbNow)) {
                lease.expiresAt = dbNow.plusNanos(leaseMs * 1_000_000);
                lease.stopped = stopped[i];
                out.add(new Renewed(channelIds[i], lease.epoch));
            }
        }
        return out;
    }

    @Override
    public ReadBack readBack() throws SQLException {
        call("readBack");
        Map<String, Row> rows = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : modes.entrySet()) {
            Lease lease = leases.get(e.getKey());
            rows.put(e.getKey(), lease == null
                ? new Row(e.getKey(), e.getValue(), rereadable.contains(e.getKey()), null, null, null, null, null)
                : new Row(e.getKey(), e.getValue(), rereadable.contains(e.getKey()), lease.owner, lease.epoch,
                    lease.expiresAt, lease.stopped, null));
        }
        return new ReadBack(rows, dbNow);
    }

    @Override
    public List<String> release(String[] channelIds, Long[] epochs) throws SQLException {
        call("release");
        List<String> out = new ArrayList<>();
        for (int i = 0; i < channelIds.length; i++) {
            Lease lease = leases.get(channelIds[i]);
            if (lease != null && node.id.equals(lease.owner) && lease.epoch == epochs[i]) {
                lease.owner = null;
                lease.epoch++;
                lease.stopped = false;
                out.add(channelIds[i]);
            }
        }
        return out;
    }

    @Override
    public String upsertNode() throws SQLException {
        call("upsertNode");
        String previous = previousInstance;
        previousInstance = node.instance;
        return previous;
    }

    @Override
    public List<Peer> peers(long windowMs) throws SQLException {
        call("peers");
        return new ArrayList<>(peers);
    }

    @Override
    public List<Acquired> acquire(String[] candidates, int share, long leaseMs) throws SQLException {
        call("acquire");
        acquireCandidates.add(candidates);
        acquireShares.add(share);
        List<Acquired> out = new ArrayList<>();
        for (String id : candidates) {
            Lease lease = leases.get(id);
            if (out.size() < share && lease != null && (lease.owner == null || !lease.expiresAt.isAfter(dbNow))) {
                lease.owner = node.id;
                lease.epoch++;
                lease.expiresAt = dbNow.plusNanos(leaseMs * 1_000_000);
                out.add(new Acquired(id, lease.epoch, lease.stopped));
            }
        }
        return out;
    }

    @Override
    public void insertLease(String channelId) throws SQLException {
        call("insertLease");
        Lease lease = leases.get(channelId);
        if (lease == null) {
            lease = new Lease();
            lease.expiresAt = dbNow;
            leases.put(channelId, lease);
        } else if (lease.owner == null || !lease.expiresAt.isAfter(dbNow)) {
            lease.stopped = false;
        }
    }

    @Override
    public void markGone(long windowMs) throws SQLException {
        call("markGone");
    }

    List<String> callsSince(int index) {
        return new ArrayList<>(calls.subList(index, calls.size()));
    }

    static List<String> list(String... calls) {
        return Arrays.asList(calls);
    }

    private void call(String statement) throws SQLException {
        calls.add(statement);
        callTimes.add(clock.ms());
        behavior.before(statement);
    }
}
