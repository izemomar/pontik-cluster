// SPDX-License-Identifier: MPL-2.0
package dev.pontik.cluster.lease;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

/**
 * What this node knows about each channel here: whether it is deployed, whether its lease may be
 * released, whether it may be acquired, and the marks the engine side keeps about the current
 * runtime copy. The engine side writes it from its hooks and its sweep; the keeper reads it.
 *
 * One lock guards everything. The deploy hook and the keeper's release step both take it, so a
 * redeploy either cancels a pending release or finds the lease already gone, never half of each.
 */
public final class LocalState {

    /** Why a held lease is due for release. */
    public enum Release {
        /** Two consecutive idle sweeps found the channel gone. */
        UNDEPLOYED,
        /** The channel is refused here, so it runs as it does without the plugin. */
        REFUSED,
        /** The owner could not start the source, which therefore is not running. */
        START_FAILED
    }

    private static final class Channel {
        String name;
        boolean deployed;
        boolean pending;
        int absentSweeps;
        String refusal;
        boolean configuredStarted;
        boolean channelStopped;
        boolean sourceStoppedByOperator;
        boolean weStopped;
        boolean coolingDown;
        long coolDownUntil;
        boolean startFailed;
        boolean operatorStop;
        boolean acquiredStopped;
        int deploys;
    }

    private final Timing timing;
    private final Map<String, Channel> channels = new HashMap<>();
    private boolean busy = true;
    private boolean swept;
    private long sweptAt;

    public LocalState(Timing timing) {
        this.timing = timing;
    }

    /**
     * Records that this channel is deployed here, which cancels a pending release and forgets the
     * marks of its previous runtime copy. The deploy hook calls this for every deploy of the channel.
     */
    public synchronized void deployed(String channelId, String name) {
        Channel c = channel(channelId);
        c.name = name;
        c.deployed = true;
        c.pending = false;
        c.absentSweeps = 0;
        c.weStopped = false;
        c.coolingDown = false;
        c.startFailed = false;
        c.deploys++;
    }

    /**
     * Records that this channel is no longer deployed here, so its lease waits to be released. The
     * undeploy hook calls this once the source has stopped. The absent count starts again, so only
     * sweeps taken after this call count toward the two.
     */
    public synchronized void undeployed(String channelId) {
        Channel c = channel(channelId);
        c.deployed = false;
        c.pending = true;
        c.absentSweeps = 0;
    }

    /**
     * Records one sweep of the deployed channels, taken once per heartbeat. A sweep that finds a
     * channel absent while the engine is idle counts one absent sweep; a busy sweep, a sweep that
     * finds the channel, or a deploy or undeploy resets the count. After two absent sweeps, the
     * channel is no longer deployed here, and its lease may be released.
     */
    public synchronized void swept(long nowNanos, boolean engineBusy, Set<String> present) {
        busy = engineBusy;
        swept = true;
        sweptAt = nowNanos;

        for (Map.Entry<String, Channel> e : channels.entrySet()) {
            Channel c = e.getValue();
            if (engineBusy || present.contains(e.getKey())) {
                c.absentSweeps = 0;
                continue;
            }
            if (c.absentSweeps < 2) {
                c.absentSweeps++;
            }
            if (c.absentSweeps >= 2 && c.deployed) {
                c.deployed = false;
                c.pending = true;
            }
        }
    }

    /**
     * Releases the lease through {@code release} if it is due, under the lock the deploy hook takes, and returns
     * why, or null when it is not due or {@code release} found it gone.
     */
    public synchronized Release release(String channelId, long nowNanos, BooleanSupplier release) {
        Release why = releaseDue(channelId, nowNanos);
        return why != null && release.getAsBoolean() ? why : null;
    }

    // Returns why this held lease is due for release now, or null.
    private Release releaseDue(String channelId, long nowNanos) {
        Channel c = channels.get(channelId);
        if (c == null) {
            return null;
        }
        if (c.refusal != null) {
            return Release.REFUSED;
        }
        if (c.startFailed) {
            return Release.START_FAILED;
        }
        boolean fresh = swept && nowNanos - sweptAt <= TimeUnit.MILLISECONDS.toNanos(timing.sweepMaxAgeMs());
        if (c.pending && c.absentSweeps >= 2 && fresh && !busy) {
            return Release.UNDEPLOYED;
        }
        return null;
    }

    /** Records that the keeper sent the release, which also forgets an operator's stop. */
    public synchronized void released(String channelId) {
        Channel c = channels.get(channelId);
        if (c != null) {
            c.pending = false;
            c.startFailed = false;
            c.operatorStop = false;
            c.acquiredStopped = false;
        }
    }

    /** Records that the keeper acquired this channel, and takes the operator's stop from the lease row. */
    public synchronized void acquired(String channelId, boolean stoppedRow) {
        Channel c = channel(channelId);
        c.operatorStop = stoppedRow;
        c.acquiredStopped = stoppedRow;
    }

    /** Returns whether the keeper may try to acquire this channel. */
    public synchronized boolean eligible(String channelId, long nowNanos) {
        Channel c = channels.get(channelId);
        return c != null && c.deployed && !c.pending && c.refusal == null && !c.channelStopped
            && !c.sourceStoppedByOperator && !coolingDown(c, nowNanos);
    }

    /**
     * Records whether this channel may be protected here, with its configured initial state. A null
     * refusal code means the channel is eligible.
     */
    public synchronized void verdict(String channelId, String refusalCode, boolean configuredStarted) {
        Channel c = channel(channelId);
        c.refusal = refusalCode;
        c.configuredStarted = configuredStarted;
    }

    /**
     * Records what the sweep saw of this channel. {@code channelStopped} says its state is STOPPED,
     * and {@code sourceStoppedByOperator} says the operator stopped its source here: it is configured
     * to start, its source is not started, and we did not stop it.
     */
    public synchronized void observed(String channelId, boolean channelStopped, boolean sourceStoppedByOperator) {
        Channel c = channel(channelId);
        c.channelStopped = channelStopped;
        c.sourceStoppedByOperator = sourceStoppedByOperator;
    }

    /** Records that the source did not start, so this node sits out the cool-down and releases the lease (PC-118). */
    public synchronized void startFailed(String channelId, long nowNanos) {
        Channel c = channel(channelId);
        c.startFailed = true;
        c.coolingDown = true;
        c.coolDownUntil = nowNanos + TimeUnit.MILLISECONDS.toNanos(timing.alarmMs());
    }

    public synchronized void setOperatorStop(String channelId, boolean stopped) {
        channel(channelId).operatorStop = stopped;
    }

    public synchronized boolean operatorStop(String channelId) {
        Channel c = channels.get(channelId);
        return c != null && c.operatorStop;
    }

    public synchronized boolean acquiredStopped(String channelId) {
        Channel c = channels.get(channelId);
        return c != null && c.acquiredStopped;
    }

    /** Forgets that the lease was acquired from a row an operator stopped, once the reconciler acted on it. */
    public synchronized void clearAcquiredStopped(String channelId) {
        Channel c = channels.get(channelId);
        if (c != null) {
            c.acquiredStopped = false;
        }
    }

    /** Returns whether this node sits out the start-failure cool-down for this channel. */
    public synchronized boolean coolingDown(String channelId, long nowNanos) {
        Channel c = channels.get(channelId);
        return c != null && coolingDown(c, nowNanos);
    }

    public synchronized void setWeStopped(String channelId, boolean stopped) {
        channel(channelId).weStopped = stopped;
    }

    public synchronized boolean weStopped(String channelId) {
        Channel c = channels.get(channelId);
        return c != null && c.weStopped;
    }

    /** Returns how many times the deploy hook ran for this channel, which a lane reads before its status read. */
    public synchronized int deploys(String channelId) {
        Channel c = channels.get(channelId);
        return c == null ? 0 : c.deploys;
    }

    /** Forgets our stop mark, unless the channel was deployed again since {@code deploys} was read. */
    public synchronized void forgetWeStopped(String channelId, int deploys) {
        Channel c = channels.get(channelId);
        if (c != null && c.deploys == deploys) {
            c.weStopped = false;
        }
    }

    public synchronized boolean deployedHere(String channelId) {
        Channel c = channels.get(channelId);
        return c != null && c.deployed;
    }

    public synchronized String refusal(String channelId) {
        Channel c = channels.get(channelId);
        return c == null ? null : c.refusal;
    }

    public synchronized boolean configuredStarted(String channelId) {
        Channel c = channels.get(channelId);
        return c != null && c.configuredStarted;
    }

    /**
     * Returns whether this channel is stopped here, because its state is STOPPED or the operator
     * stopped its source.
     */
    public synchronized boolean stoppedHere(String channelId) {
        Channel c = channels.get(channelId);
        return c != null && (c.channelStopped || c.sourceStoppedByOperator);
    }

    public synchronized String name(String channelId) {
        Channel c = channels.get(channelId);
        return c == null || c.name == null ? channelId : c.name;
    }

    public synchronized boolean engineBusy() {
        return busy;
    }

    private static boolean coolingDown(Channel c, long nowNanos) {
        return c.coolingDown && nowNanos - c.coolDownUntil < 0;
    }

    private Channel channel(String channelId) {
        Channel c = channels.get(channelId);
        if (c == null) {
            c = new Channel();
            channels.put(channelId, c);
        }
        return c;
    }
}
