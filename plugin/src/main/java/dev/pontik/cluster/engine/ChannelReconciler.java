// SPDX-License-Identifier: MPL-2.0
package dev.pontik.cluster.engine;

import com.mirth.connect.donkey.model.channel.DeployedState;
import com.mirth.connect.model.Channel;
import com.mirth.connect.model.DashboardStatus;
import dev.pontik.cluster.fault.Code;
import dev.pontik.cluster.fault.Faults;
import dev.pontik.cluster.fault.Messages;
import dev.pontik.cluster.fault.Names;
import dev.pontik.cluster.fault.Sink;
import dev.pontik.cluster.lease.Held;
import dev.pontik.cluster.lease.Keeper;
import dev.pontik.cluster.lease.LocalState;
import dev.pontik.cluster.lease.Ownership;
import dev.pontik.cluster.lease.Reconciler;
import dev.pontik.cluster.lease.Store;
import dev.pontik.cluster.lease.Timing;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * The engine side of the plugin: the deploy hook, each channel's lane, and the sweep. A lane
 * compares what a channel should be doing on this node with what the engine reports, and starts or
 * stops its source only when the two differ. The sweep reads every channel's state once per
 * heartbeat, tells the keeper whether the engine is busy and which channels are deployed here, and
 * queues the lanes that have work. It is the backstop for an event the engine never delivered.
 *
 * No engine call ever runs on the keeper's thread.
 */
final class ChannelReconciler {

    /** What one gate pause does: pauses the engine's copy of a channel, and says whether it was there. */
    interface Pause {
        boolean pause(String channelId) throws Exception;
    }

    /** What the last sweep saw of one deployed channel. */
    static final class Seen {
        final String name;
        final DeployedState channel;
        final DeployedState source;

        Seen(String name, DeployedState channel, DeployedState source) {
            this.name = name;
            this.channel = channel;
            this.source = source;
        }
    }

    private final Engine engine;
    private final Ownership ownership;
    private final LocalState local;
    private final Faults faults;
    private final Messages messages;
    private final Sink sink;
    private final Names names;
    private final Timing timing;
    private final Supplier<Keeper> keeper;
    private final LongSupplier nanos;
    private final Reconciler lanes;
    private final Targets targets;
    private final Pause pause;

    // The nanoTime at which our start or stop of each channel began, until it is judged.
    private final Map<String, Long> starting = new ConcurrentHashMap<>();
    private final Map<String, Long> stopping = new ConcurrentHashMap<>();
    private final Set<String> startWarned = ConcurrentHashMap.newKeySet();
    private final Map<String, Long> gatedAt = new ConcurrentHashMap<>();
    private final Map<String, Deque<Long>> undoneAt = new ConcurrentHashMap<>();
    private final Map<String, String> undone = new ConcurrentHashMap<>();
    private final Map<String, String> stoppedOn = new ConcurrentHashMap<>();
    private final Map<String, String> sourceTypes = new ConcurrentHashMap<>();
    private volatile Map<String, Seen> seen = Collections.emptyMap();
    private volatile long sweptNanos;
    private volatile boolean swept;
    private volatile boolean sweepFailing;
    private volatile boolean targetsFailing;

    /**
     * Creates the reconciler. A null pause means the deploy gate is unavailable on this engine, and
     * every protected channel is refused with PC-116.
     */
    ChannelReconciler(Engine engine, Ownership ownership, LocalState local, Faults faults, Messages messages,
            Sink sink, Names names, Timing timing, Supplier<Keeper> keeper, LongSupplier nanos, Reconciler lanes,
            Targets targets, Pause pause) {
        this.engine = engine;
        this.ownership = ownership;
        this.local = local;
        this.faults = faults;
        this.messages = messages;
        this.sink = sink;
        this.names = names;
        this.timing = timing;
        this.keeper = keeper;
        this.nanos = nanos;
        this.lanes = lanes;
        this.targets = targets;
        this.pause = pause;
    }

    /**
     * Runs on the engine's deploy thread for each channel it deploys, before the source starts. It
     * touches no database, starts and stops nothing, never changes the model, and never throws.
     */
    void deployed(Channel model) {
        try {
            Keeper k = keeper.get();
            if (k == null || k.state() != Keeper.State.RUNNING) {
                return;
            }
            String id = model.getId();
            long now = nanos.getAsLong();

            // A new runtime copy: the marks of the previous one die with it.
            local.deployed(id, model.getName());
            faults.clear(Code.PC118, id);
            startEnded(id);
            stopping.remove(id);
            gatedAt.remove(id);
            sourceTypes.put(id, model.getSourceConnector().getTransportName());
            for (String target : targets.add(model)) {
                lanes.queue(target);
            }

            Store.Row row = k.readBack().rows.get(id);
            boolean singleOwner = row != null && row.singleOwner();
            boolean started = model.getProperties().getInitialState() == DeployedState.STARTED;
            Refusal refusal = judge(model, row != null && row.rereadable);
            record(id, refusal, started, singleOwner);

            Held held = ownership.get(id);
            long leftMs = held == null ? -1 : TimeUnit.NANOSECONDS.toMillis(held.validUntil - now);
            Hook.Action action = Hook.decide(singleOwner, refusal != null, started, leftMs, timing.freshMs());
            if (action != Hook.Action.PAUSE) {
                return;
            }

            // The mark follows the pause, so a gate that fails leaves no mark on a channel that starts as configured.
            if (pause.pause(id)) {
                local.setWeStopped(id, true);
                gatedAt.put(id, now);
            } else {
                record(id, new Refusal(Code.PC116, messages.pc116(false)), started, true);
            }
        } catch (Throwable t) {
            sink.warn("The deploy hook failed for one channel, which starts as it is configured.", t);
        }
    }

    /** Runs on the engine's undeploy thread once the channel's source has stopped. */
    void undeployed(String channelId) {
        if (keeper.get() != null) {
            local.undeployed(channelId);
            startEnded(channelId);
            stopLanded(channelId);
        }
    }

    /** Runs one channel's lane: recomputes what the channel should be doing here, and acts on a difference. */
    void reconcile(String id) {
        Keeper k = keeper.get();
        if (k == null) {
            return;
        }
        // We read the deploy count before the status, so a clear this read justifies never reaches a newer copy.
        int deploys = local.deploys(id);
        DashboardStatus status;
        try {
            status = engine.status(id);
        } catch (RuntimeException e) {
            // The state is unknown, so we do nothing; the next sweep queues the lane again.
            return;
        }
        Store.ReadBack rb = k.readBack();
        Store.Row row = rb.rows.get(id);
        boolean singleOwner = row != null && row.singleOwner();

        if (!singleOwner) {
            clearRefusal(id);
        } else if (status != null && local.deployedHere(id) && !Code.PC116.label().equals(local.refusal(id))) {
            // An opt-in on a deployed channel, or a Channel Writer that came or went, has no deploy hook.
            Channel model = engine.deployed(id);
            if (model != null) {
                sourceTypes.put(id, model.getSourceConnector().getTransportName());
                record(id, judge(model, row.rereadable),
                    model.getProperties().getInitialState() == DeployedState.STARTED, true);
            }
        }

        DeployedState channel = status == null ? null : status.getState();
        DeployedState source = status == null ? null : Engine.source(status);
        if (startedElsewhere(id, source)) {
            local.forgetWeStopped(id, deploys);
        }
        Desired.Facts f = facts(k, rb, id, row, channel, source);
        switch (Desired.of(f)) {
            case RESTORE:
                restore(id, channel);
                break;
            case LEAVE_PAUSED:
                leavePaused(id, k, row);
                break;
            case START:
                start(id);
                break;
            case JUDGE_START:
                judgeStart(id, source, deploys);
                break;
            case STOP:
                stop(id, k, f);
                break;
            case CLEAR:
                stopLanded(id);
                break;
            case WAIT:
                overdue(id, channel, source, deploys);
                break;
            default:
                break;
        }
    }

    /**
     * Reads every channel's state once, publishes whether the engine is busy and which channels are
     * here, recomputes the Channel Writer targets, derives an operator's stop on the channels we hold,
     * and queues each lane that has work. The sweep's thread calls this once per heartbeat.
     */
    void sweep() {
        Keeper k = keeper.get();
        if (k == null) {
            return;
        }
        long now = nanos.getAsLong();
        List<DashboardStatus> all;
        try {
            all = engine.statuses();
        } catch (Throwable t) {
            if (!sweepFailing) {
                sweepFailing = true;
                sink.warn("The sweep could not read the channel states; until it can, no lease is released here.", t);
            }
            return;
        }
        sweepFailing = false;

        boolean busy = false;
        Map<String, Seen> fresh = new HashMap<>();
        for (DashboardStatus s : all) {
            busy |= Engine.busy(s.getState());
            fresh.put(s.getChannelId(), new Seen(s.getName(), s.getState(), Engine.source(s)));
        }
        local.swept(now, busy, fresh.keySet());

        try {
            Predicate<String> enabled = engine.enabled();
            for (String id : targets.replace(engine.saved(), id -> fresh.containsKey(id) || enabled.test(id))) {
                lanes.queue(id);
            }
            targetsFailing = false;
        } catch (Throwable t) {
            // We keep the targets we have; the next sweep tries again.
            if (!targetsFailing) {
                targetsFailing = true;
                sink.warn("The sweep could not refresh the Channel Writer targets; until it can, we keep the ones "
                    + "we have.", t);
            }
        }

        for (Map.Entry<String, Seen> e : fresh.entrySet()) {
            observe(e.getKey(), e.getValue(), busy, now);
        }
        seen = fresh;
        sweptNanos = now;
        swept = true;

        endFights(now);

        Store.ReadBack readBack = k.readBack();
        warnOverdue(k, readBack, fresh, now);
        for (Map.Entry<String, Seen> e : fresh.entrySet()) {
            String id = e.getKey();
            Seen s = e.getValue();
            // A source someone else started leaves our mark behind, and no action of Desired forgets it.
            if (Desired.of(facts(k, readBack, id, readBack.rows.get(id), s.channel, s.source)) != Desired.Action.NONE
                    || startedElsewhere(id, s.source) && local.weStopped(id)) {
                lanes.queue(id);
            }
        }
    }

    // A stop or a start can block a lane for as long as a poll, so the sweep raises the time-based alerts and the
    // lane keeps the decisions.
    private void warnOverdue(Keeper k, Store.ReadBack rb, Map<String, Seen> fresh, long now) {
        long lease = TimeUnit.MILLISECONDS.toNanos(timing.leaseMs);
        for (Map.Entry<String, Long> e : stopping.entrySet()) {
            Seen s = fresh.get(e.getKey());
            if (s != null && now - e.getValue() > lease && s.source != DeployedState.STOPPED) {
                faults.raise(Code.PC104, e.getKey(), messages.pc104(ownerOf(k, rb, e.getKey()),
                    messages.stillStopping()));
            }
        }
        for (Map.Entry<String, Long> e : starting.entrySet()) {
            String id = e.getKey();
            Seen s = fresh.get(id);
            if (s != null && now - e.getValue() > lease && s.source != DeployedState.STOPPED
                    && s.source != DeployedState.STARTED && startWarned.add(id)) {
                sink.warn("The source of " + names.channelName(id) + " is still " + s.source + " after we started it, "
                    + "so we keep its lease until it settles. Please check this node's access to the channel's "
                    + "source.", null);
            }
        }
    }

    /** Returns what the last sweep saw, by channel ID. */
    Map<String, Seen> seen() {
        return seen;
    }

    /** Returns the age of the last sweep in milliseconds, or -1 before the first one. */
    long sweepAgeMs() {
        return swept ? TimeUnit.NANOSECONDS.toMillis(nanos.getAsLong() - sweptNanos) : -1;
    }

    /** Returns the transport name of the channel's source, such as "File Reader", or null. */
    String sourceType(String channelId) {
        return sourceTypes.get(channelId);
    }

    /** Returns the line of the last start this node undid, while a start fight may still be on. */
    String undone(String channelId) {
        return undone.get(channelId);
    }

    /** Returns the node on which an operator stopped a channel this node took over stopped, or null. */
    String stoppedOn(String channelId) {
        return stoppedOn.get(channelId);
    }

    boolean gateAvailable() {
        return pause != null;
    }

    private Refusal judge(Channel model, boolean rereadable) {
        if (pause == null) {
            return new Refusal(Code.PC116, messages.pc116(true));
        }
        return Eligibility.judge(model, rereadable, targets.writer(model.getId()), messages);
    }

    // A refusal raises its code once; a missing gate is one node fault, not one per channel.
    private void record(String id, Refusal refusal, boolean started, boolean singleOwner) {
        String before = local.refusal(id);
        String after = refusal == null ? null : refusal.code.label();
        local.verdict(id, after, started);
        Code old = Refusal.code(before);
        if (old != null && (!singleOwner || !old.label().equals(after))) {
            faults.clear(old, id);
        }
        if (refusal != null && singleOwner && !(refusal.code == Code.PC116 && pause == null)) {
            faults.raise(refusal.code, id, refusal.message);
        }
    }

    private void clearRefusal(String id) {
        Code old = Refusal.code(local.refusal(id));
        // PC-116 clears at the channel's next deploy, not when the channel turns unmanaged.
        if (old != null && old != Code.PC116) {
            faults.clear(old, id);
        }
    }

    private Desired.Facts facts(Keeper k, Store.ReadBack rb, String id, Store.Row row, DeployedState channel,
            DeployedState source) {
        Held held = ownership.get(id);
        Desired.Facts f = new Desired.Facts();
        f.handBack = row == null || !row.singleOwner() || local.refusal(id) != null
            || k.state() == Keeper.State.DECLINED;
        f.held = held != null && nanos.getAsLong() - held.validUntil < 0;
        f.acquiredStopped = local.acquiredStopped(id);
        f.weStopped = local.weStopped(id);
        f.mustStop = ownership.mustStop(id);
        f.anotherHolder = k.anotherHolder(rb, id);
        f.starting = starting.containsKey(id);
        f.stopping = stopping.containsKey(id);
        f.channel = channel;
        f.source = source;
        return f;
    }

    // A hand-back starts what we stopped once, and forgets the mark whatever the outcome.
    private void restore(String id, DeployedState channel) {
        local.setWeStopped(id, false);
        ownership.clearMustStop(id);
        startEnded(id);
        stopping.remove(id);
        if (channel == DeployedState.PAUSED) {
            try {
                engine.startSource(id);
            } catch (Exception e) {
                sink.warn("Could not start " + names.channelName(id) + " after handing it back to the engine; "
                    + "please start it by hand.", e);
            }
        }
    }

    private void leavePaused(String id, Keeper k, Store.Row row) {
        local.setWeStopped(id, false);
        local.clearAcquiredStopped(id);
        ownership.clearMustStop(id);
        String on = row != null && row.owner != null && !row.owner.equals(names.nodeId())
            ? k.ownerName(row.owner) : "another node";
        stoppedOn.put(id, on);
        sink.info("Left " + names.channelName(id) + " paused: stopped by an operator on " + on + ".");
    }

    private void start(String id) {
        startWarned.remove(id);
        starting.put(id, nanos.getAsLong());
        try {
            engine.startSource(id);
        } catch (Exception e) {
            // A start that throws is judged by the read-back, as one that returns.
        }
        int deploys = local.deploys(id);
        DashboardStatus after;
        try {
            after = engine.status(id);
        } catch (RuntimeException e) {
            // The state is unknown; the next sweep queues the lane again, and the start is judged then.
            return;
        }
        if (after == null) {
            // The channel was undeployed meanwhile: the release path takes it, and nothing is wrong.
            startEnded(id);
        } else if (Engine.settled(after.getState())) {
            judgeStart(id, Engine.source(after), deploys);
        }
    }

    private void judgeStart(String id, DeployedState source, int deploys) {
        startEnded(id);
        if (source == DeployedState.STARTED) {
            local.forgetWeStopped(id, deploys);
            faults.clear(Code.PC118, id);
            sink.info("Started " + names.channelName(id) + ": this node holds the lease.");
        } else {
            startFailed(id, String.valueOf(source));
        }
    }

    // The mark stays: we did stop this source. The keeper releases the lease so a peer can take over.
    private void startFailed(String id, String cause) {
        startEnded(id);
        local.startFailed(id, nanos.getAsLong());
        faults.raise(Code.PC118, id, messages.pc118(cause));
    }

    private void stop(String id, Keeper k, Desired.Facts f) {
        String owner = ownerOf(k, k.readBack(), id);
        // A start still marked is over: we are about to stop the source it started.
        startEnded(id);
        // A retry of a stop already in flight is the same stop, so it logs and counts nothing more.
        if (!f.stopping) {
            if (f.anotherHolder && !f.mustStop) {
                undo(id, owner);
            } else {
                sink.info("Stopped " + names.channelName(id) + ": "
                    + (f.anotherHolder ? owner + " holds the lease" : "this node lost its lease") + ".");
            }
        }

        local.setWeStopped(id, true);
        stopping.putIfAbsent(id, nanos.getAsLong());
        boolean threw = false;
        try {
            engine.stopSource(id);
        } catch (Exception e) {
            threw = true;
        }
        DashboardStatus after;
        try {
            after = engine.status(id);
        } catch (RuntimeException e) {
            // The state is unknown; the marker stays, and the next sweep queues the lane again.
            return;
        }
        if (after == null || Engine.source(after) == DeployedState.STOPPED) {
            stopLanded(id);
        } else if (threw) {
            faults.raise(Code.PC104, id, messages.pc104(owner, null));
        }
    }

    private void stopLanded(String id) {
        ownership.clearMustStop(id);
        stopping.remove(id);
        faults.clear(Code.PC104, id);
    }

    // The sweep raises the alerts; the lane decides what an overdue start means.
    private void overdue(String id, DeployedState channel, DeployedState source, int deploys) {
        Long startAt = starting.get(id);
        if (startAt == null || nanos.getAsLong() - startAt <= TimeUnit.MILLISECONDS.toNanos(timing.leaseMs)) {
            return;
        }
        if (source == DeployedState.STOPPED) {
            startFailed(id, String.valueOf(channel));
        } else if (source == DeployedState.STARTED) {
            judgeStart(id, DeployedState.STARTED, deploys);
        }
    }

    private void startEnded(String id) {
        starting.remove(id);
        startWarned.remove(id);
    }

    // A source that runs with no start or stop of ours in flight was started by someone else. A running source
    // stays ours to stop until our stop lands.
    private boolean startedElsewhere(String id, DeployedState source) {
        return source == DeployedState.STARTED && !starting.containsKey(id) && !stopping.containsKey(id);
    }

    // One start undone is an INFO line; three within the alarm window are a fight, and PC-105.
    private void undo(String id, String owner) {
        long now = nanos.getAsLong();
        long window = TimeUnit.MILLISECONDS.toNanos(timing.alarmMs());
        Deque<Long> times = undoneAt.computeIfAbsent(id, key -> new ArrayDeque<>());
        int count;
        synchronized (times) {
            times.addLast(now);
            while (now - times.peekFirst() > window) {
                times.removeFirst();
            }
            count = times.size();
        }
        String line = "A start on " + names.nodeName() + " was undone: " + owner + " owns this channel. Start or "
            + "stop it on " + owner + ".";
        undone.put(id, line);
        Long gated = gatedAt.get(id);
        sink.info(line + (gated == null ? ""
            : " It started " + TimeUnit.NANOSECONDS.toMillis(now - gated) + " ms after the deploy paused it."));
        if (count >= 3) {
            faults.raise(Code.PC105, id, messages.pc105(owner));
        }
    }

    private void endFights(long now) {
        long window = TimeUnit.MILLISECONDS.toNanos(timing.alarmMs());
        for (Map.Entry<String, Deque<Long>> e : undoneAt.entrySet()) {
            Deque<Long> times = e.getValue();
            synchronized (times) {
                if (!times.isEmpty() && now - times.peekLast() > window) {
                    times.clear();
                    undone.remove(e.getKey());
                    faults.clear(Code.PC105, e.getKey());
                }
            }
        }
    }

    private void observe(String id, Seen s, boolean busy, long now) {
        // An operator's Stop or Halt after our pause is the later intent, if a fresh read still says so.
        if (s.channel == DeployedState.STOPPED && local.weStopped(id)) {
            int deploys = local.deploys(id);
            if (stoppedNow(id)) {
                local.forgetWeStopped(id, deploys);
            }
        }
        if (!Engine.settled(s.channel)) {
            return;
        }
        boolean started = local.configuredStarted(id);
        boolean ours = local.weStopped(id);
        local.observed(id, s.channel == DeployedState.STOPPED, started && s.source != DeployedState.STARTED && !ours);

        // Only an idle sweep that sees the channel settled may change the operator's stop that the renewal writes
        // to the row.
        if (!busy && ownership.get(id) != null && !local.acquiredStopped(id)) {
            boolean byOperator = started && s.source != DeployedState.STARTED && !ours && !ownership.mustStop(id)
                && !local.coolingDown(id, now);
            if (byOperator) {
                // We read our mark before and after the status: a start clears it only once the source runs, and a stop
                // sets it before it stops the source.
                boolean oursNow = local.weStopped(id);
                boolean mustStop = ownership.mustStop(id);
                boolean cooling = local.coolingDown(id, now);
                DashboardStatus fresh;
                try {
                    fresh = engine.status(id);
                } catch (RuntimeException e) {
                    return;
                }
                if (fresh == null || !Engine.settled(fresh.getState())) {
                    return;
                }
                byOperator = Engine.source(fresh) != DeployedState.STARTED && !oursNow && !local.weStopped(id)
                    && !mustStop && !cooling;
            }
            local.setOperatorStop(id, byOperator);
            if (!byOperator) {
                stoppedOn.remove(id);
            }
        }
    }

    // The snapshot may be older than a redeploy, so we confirm with a fresh read before we forget our mark.
    private boolean stoppedNow(String id) {
        try {
            DashboardStatus fresh = engine.status(id);
            return fresh != null && fresh.getState() == DeployedState.STOPPED;
        } catch (RuntimeException e) {
            return false;
        }
    }

    private String ownerOf(Keeper k, Store.ReadBack rb, String id) {
        Store.Row row = rb.rows.get(id);
        return row == null || row.owner == null ? "another node" : k.ownerName(row.owner);
    }
}
