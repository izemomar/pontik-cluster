// SPDX-License-Identifier: MPL-2.0
package dev.pontik.cluster.engine;

/** What the deploy hook does with one channel, decided from memory alone. */
final class Hook {

    enum Action {
        /** Today's behavior: the channel starts as it is configured. */
        NOTHING,
        /** The channel is refused here; its fault is raised and it starts as configured. */
        REFUSE,
        /** This node holds a lease confirmed within the last heartbeat, so its source starts with the deploy. */
        KEEP,
        /** Pause the engine's copy of the channel, so its source stays stopped until this node owns it. */
        PAUSE
    }

    private Hook() {
    }

    /**
     * Decides the hook's action.
     *
     * @param singleOwner whether the channel's mode is single-owner
     * @param refused whether the channel failed one of the eligibility checks
     * @param configuredStarted whether the channel is configured to start
     * @param leftMs how long this node's lease on the channel may still be read, or a negative value without one
     * @param freshMs the least time left that proves a renewal confirmed within the last heartbeat
     */
    static Action decide(boolean singleOwner, boolean refused, boolean configuredStarted, long leftMs,
            long freshMs) {
        if (!singleOwner) {
            return Action.NOTHING;
        }
        if (refused) {
            return Action.REFUSE;
        }
        if (!configuredStarted) {
            return Action.NOTHING;
        }
        return leftMs >= freshMs ? Action.KEEP : Action.PAUSE;
    }
}
