// SPDX-License-Identifier: MPL-2.0
package dev.pontik.cluster.engine;

import com.mirth.connect.donkey.model.channel.DeployedState;

/**
 * What a channel's lane must do, decided from what this node knows when the lane runs. The sweep
 * asks the same question, and queues a lane when the answer is not {@code NONE}, or when a source
 * someone else started still carries our mark.
 */
final class Desired {

    enum Action {
        NONE,
        /** A channel handed back to today's behavior: start what we stopped, once, and forget the mark. */
        RESTORE,
        /** A lease acquired from a row an operator stopped: leave the source paused and forget our mark. */
        LEAVE_PAUSED,
        /** This node holds the lease and stopped the source: start it. */
        START,
        /** A start is in flight: judge whether it landed. */
        JUDGE_START,
        /** Another node holds the lease, or this node lost it: stop the running source. */
        STOP,
        /** The stop marker is no longer needed: the source is stopped, or this node holds the lease. */
        CLEAR,
        /** The channel is between states with our start in flight: check how long it has taken. */
        WAIT
    }

    /** What the lane knows about one channel. */
    static final class Facts {
        boolean handBack;
        boolean held;
        boolean acquiredStopped;
        boolean weStopped;
        boolean mustStop;
        boolean anotherHolder;
        boolean starting;
        boolean stopping;
        DeployedState channel;
        DeployedState source;
    }

    private Desired() {
    }

    static Action of(Facts f) {
        if (f.channel == null) {
            return Action.NONE;
        }
        if (!Engine.settled(f.channel)) {
            return f.starting ? Action.WAIT : Action.NONE;
        }
        if (f.handBack) {
            return f.weStopped ? Action.RESTORE : Action.NONE;
        }
        if (f.held) {
            if (f.acquiredStopped) {
                return Action.LEAVE_PAUSED;
            }
            if (f.starting) {
                return Action.JUDGE_START;
            }
            if (f.weStopped && f.channel == DeployedState.PAUSED) {
                return Action.START;
            }
            return f.mustStop || f.stopping ? Action.CLEAR : Action.NONE;
        }
        if (f.source == DeployedState.STARTED) {
            return f.mustStop || f.anotherHolder ? Action.STOP : Action.NONE;
        }
        if ((f.mustStop || f.stopping) && f.source == DeployedState.STOPPED) {
            return Action.CLEAR;
        }
        return Action.NONE;
    }
}
