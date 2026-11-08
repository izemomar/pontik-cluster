// SPDX-License-Identifier: MPL-2.0
package dev.pontik.cluster.engine;

/** Whether a channel is protected on this node, with the reason an operator reads. */
final class Status {

    static final String UNMANAGED = "unmanaged";
    static final String PROTECTED = "protected";
    static final String UNPROTECTED = "unprotected";

    static final String TAKING_OVER = "taking over from another node, settles within about 20 s";
    static final String STOPPED_HERE =
        "cannot take over: stopped on this node; redeploy it here so this node can take over";

    final String state;
    final String reason;

    private Status(String state, String reason) {
        this.state = state;
        this.reason = reason;
    }

    /**
     * Decides a channel's status on this node.
     *
     * @param unprotectedBy the message of a firing code that leaves this channel unprotected, or null
     * @param running whether the source runs here, or a stop of it is still pending
     * @param undone the line of a start this node undid, or null
     * @param stoppedOn where an operator stopped the channel this node holds stopped, or null
     * @param holder the node that holds the lease when it is not this one, or null
     */
    static Status of(boolean singleOwner, String unprotectedBy, boolean held, boolean running, String undone,
            boolean operatorStop, String stoppedOn, boolean channelStopped, String holder) {
        if (!singleOwner) {
            return new Status(UNMANAGED, null);
        }
        if (unprotectedBy != null) {
            return new Status(UNPROTECTED, unprotectedBy);
        }
        if (!held && running) {
            return new Status(UNPROTECTED, undone != null ? undone : TAKING_OVER);
        }
        if (held) {
            return new Status(PROTECTED, operatorStop ? "stopped by an operator on " + stoppedOn
                : "this node holds the lease");
        }
        if (channelStopped) {
            return new Status(PROTECTED, STOPPED_HERE);
        }
        return new Status(PROTECTED, holder != null ? holder + " holds the lease" : "no node holds the lease yet");
    }
}
