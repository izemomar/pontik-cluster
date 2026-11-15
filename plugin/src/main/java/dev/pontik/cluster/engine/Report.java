// SPDX-License-Identifier: MPL-2.0
package dev.pontik.cluster.engine;

import com.mirth.connect.donkey.model.channel.DeployedState;
import dev.pontik.cluster.api.ChannelView;
import dev.pontik.cluster.api.NodeView;
import dev.pontik.cluster.fault.Faults;
import dev.pontik.cluster.fault.Names;
import dev.pontik.cluster.lease.Held;
import dev.pontik.cluster.lease.Keeper;
import dev.pontik.cluster.lease.LocalState;
import dev.pontik.cluster.lease.Node;
import dev.pontik.cluster.lease.Ownership;
import dev.pontik.cluster.lease.Store;
import dev.pontik.cluster.lease.Timing;
import dev.pontik.cluster.lease.Times;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;

/** Builds what the status calls return, from this node's memory alone. */
final class Report {

    private final Node node;
    private final Timing timing;
    private final Ownership ownership;
    private final LocalState local;
    private final Faults faults;
    private final Names names;
    private final ChannelReconciler reconciler;

    Report(Node node, Timing timing, Ownership ownership, LocalState local, Faults faults, Names names,
            ChannelReconciler reconciler) {
        this.node = node;
        this.timing = timing;
        this.ownership = ownership;
        this.local = local;
        this.faults = faults;
        this.names = names;
        this.reconciler = reconciler;
    }

    /** Returns every channel deployed here at the last sweep and every channel with a mode, by ID. */
    List<ChannelView> channels(Keeper k, long nowNanos) {
        Store.ReadBack readBack = k.readBack();
        Map<String, ChannelReconciler.Seen> seen = reconciler.seen();
        TreeSet<String> ids = new TreeSet<>(seen.keySet());
        ids.addAll(readBack.rows.keySet());

        List<ChannelView> views = new ArrayList<>();
        for (String id : ids) {
            views.add(channel(k, readBack, id, seen.get(id), nowNanos));
        }
        return views;
    }

    private ChannelView channel(Keeper k, Store.ReadBack readBack, String id, ChannelReconciler.Seen seen,
            long nowNanos) {
        Store.Row row = readBack.rows.get(id);
        boolean singleOwner = row != null && row.singleOwner();
        Held held = ownership.get(id);
        boolean holds = held != null && nowNanos - held.validUntil < 0;

        ChannelView v = new ChannelView();
        v.channelId = id;
        v.name = seen != null ? seen.name : local.name(id);
        v.sourceType = reconciler.sourceType(id);
        v.mode = row == null ? Store.UNMANAGED : row.mode;
        v.rereadable = row != null && row.rereadable;
        if (row != null && row.hasLease()) {
            v.owner = row.owner;
            v.ownerName = row.owner == null ? null : k.ownerName(row.owner);
            v.epoch = row.epoch;
            v.heldSince = Times.iso(row.acquiredAt);
            v.leaseExpiresAt = Times.iso(row.expiresAt);
            v.stopped = row.stopped;
        }
        v.asOf = Times.iso(readBack.dbNow);
        v.readBackAgeMs = k.readBackAgeMs();

        String unprotectedBy = null;
        for (Faults.Firing f : faults.firing()) {
            if (f.channelId == null ? f.code.unprotected : f.channelId.equals(id)) {
                v.codes.add(f.code.label());
                if (unprotectedBy == null && f.code.unprotected) {
                    unprotectedBy = f.message;
                }
            }
        }

        String stoppedOn = reconciler.stoppedOn(id);
        Status status = Status.of(singleOwner, unprotectedBy, holds,
            seen != null && seen.source != DeployedState.STOPPED, reconciler.undone(id), local.operatorStop(id),
            stoppedOn == null ? names.nodeName() : stoppedOn, seen != null && seen.channel == DeployedState.STOPPED,
            k.anotherHolder(readBack, id) ? k.ownerName(row.owner) : null);
        v.status = status.state;
        v.reason = status.reason;

        v.thisNode.role = !singleOwner ? "none" : holds ? "owner" : "standby";
        v.thisNode.sourceState = seen == null || seen.source == null ? null : seen.source.name();
        v.thisNode.stoppedByUs = local.weStopped(id);
        v.thisNode.stoppedByOperator = holds ? local.operatorStop(id) : local.stoppedHere(id);
        return v;
    }

    /** Returns this node, with the coordinator's state; the keeper is null while the coordinator is off. */
    NodeView node(String state, Keeper k, long nowNanos) {
        NodeView v = new NodeView();
        v.nodeId = node.id;
        v.nodeName = node.name;
        v.instanceId = node.instance;
        v.state = state;
        v.gateAvailable = reconciler.gateAvailable();
        v.timing.leaseMs = timing.leaseMs;
        v.timing.heartbeatMs = timing.heartbeatMs;
        v.timing.marginMs = timing.marginMs;
        v.timing.jitterMs = timing.jitterMs;
        v.timing.tolerance = timing.tolerance();

        for (Faults.Firing f : faults.firing()) {
            if (f.channelId == null && v.code == null) {
                v.code = f.code.label();
            }
            NodeView.Fault fault = new NodeView.Fault();
            fault.code = f.code.label();
            fault.channelId = f.channelId;
            fault.message = f.message;
            v.faults.add(fault);
        }

        if (k == null) {
            return v;
        }
        v.lastTickAgeMs = TimeUnit.NANOSECONDS.toMillis(nowNanos - k.lastTickNanos());
        long sweepAge = reconciler.sweepAgeMs();
        v.lastSweepAgeMs = sweepAge < 0 ? null : sweepAge;
        v.engineBusy = local.engineBusy();
        for (Store.Peer p : k.peers()) {
            NodeView.Peer peer = new NodeView.Peer();
            peer.nodeId = p.nodeId;
            peer.nodeName = p.nodeName;
            peer.protocolVersion = p.protocol;
            v.peers.add(peer);
        }
        for (Map.Entry<String, Held> e : ownership.snapshot().entrySet()) {
            NodeView.Lease lease = new NodeView.Lease();
            lease.channelId = e.getKey();
            lease.epoch = e.getValue().epoch;
            lease.validForMs = Math.max(0, TimeUnit.NANOSECONDS.toMillis(e.getValue().validUntil - nowNanos));
            v.leases.add(lease);
        }
        return v;
    }
}
