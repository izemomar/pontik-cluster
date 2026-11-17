// SPDX-License-Identifier: MPL-2.0
package dev.pontik.cluster.engine;

import dev.pontik.cluster.lease.Timing;
import java.util.concurrent.TimeUnit;

/** Whether a channel is protected on this node, with the reason an operator reads. */
final class Status {

    static final String UNMANAGED = "unmanaged";
    static final String PROTECTED = "protected";
    static final String UNPROTECTED = "unprotected";

    // A source that runs here without the lease settles within two sweeps.
    static final String TAKING_OVER = "taking over from another node, settles within about "
        + TimeUnit.MILLISECONDS.toSeconds(2 * Timing.STANDARD.heartbeatMs) + " s";
    static final String STOPPED_HERE =
        "cannot take over: stopped on this node; redeploy it here so this node can take over";

    /** What a channel's status depends on. The report sets every field before it calls {@link #of}. */
    static final class Facts {
        boolean singleOwner;
        /** The message of a firing code that leaves this channel unprotected, or null. */
        String unprotectedBy;
        boolean held;
        /** Whether the source is anything but stopped here: started, starting or still stopping. */
        boolean running;
        /** The line of a start this node undid, or null. */
        String undone;
        boolean operatorStop;
        /** The node an operator stopped the channel on, as a line shows it. */
        String stoppedOn;
        boolean channelStopped;
        /** The node that holds the lease when it is not this one, as a line shows it, or null. */
        String holder;
    }

    final String state;
    final String reason;

    private Status(String state, String reason) {
        this.state = state;
        this.reason = reason;
    }

    /** Decides a channel's status on this node. */
    static Status of(Facts f) {
        if (!f.singleOwner) {
            return new Status(UNMANAGED, null);
        }
        if (f.unprotectedBy != null) {
            return new Status(UNPROTECTED, f.unprotectedBy);
        }
        if (!f.held && f.running) {
            return new Status(UNPROTECTED, f.undone != null ? f.undone : TAKING_OVER);
        }
        if (f.held) {
            return new Status(PROTECTED, f.operatorStop ? "stopped by an operator on " + f.stoppedOn
                : "this node holds the lease");
        }
        if (f.channelStopped) {
            return new Status(PROTECTED, STOPPED_HERE);
        }
        return new Status(PROTECTED, f.holder != null ? f.holder + " holds the lease" : "no node holds the lease yet");
    }
}
