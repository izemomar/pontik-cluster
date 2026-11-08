// SPDX-License-Identifier: MPL-2.0
package dev.pontik.cluster.engine;

import com.mirth.connect.model.Channel;
import dev.pontik.cluster.fault.Messages;
import dev.pontik.cluster.lease.Store;
import java.sql.SQLException;
import java.util.function.Supplier;

/**
 * Sets a channel's mode from a REST call. A protected mode is checked against the saved channel
 * first, so a mode every node would refuse is never written. The deploy-time checks stay as the
 * safety net.
 */
final class Modes {

    /** The write itself, on the second connection, with its one retry after a lost connection. */
    interface Write {
        void set(String channelId, String mode, boolean rereadable) throws SQLException;
    }

    private Modes() {
    }

    /**
     * Sets the mode, or throws the rejection to answer with.
     *
     * @param off whether the coordinator is off on this node
     * @param node the node view a 503 carries
     * @param saved looks up the saved channel, or returns null when there is none
     * @param writer the name of a channel whose Channel Writer sends into this one, or null
     */
    static void set(boolean off, Supplier<Object> node, String channelId, String mode, String rereadable,
            Supplier<Channel> saved, String writer, Messages messages, Write write) throws Rejection {
        if (off) {
            throw new Rejection(503, node.get());
        }
        boolean singleOwner = Store.SINGLE_OWNER.equals(mode);
        if (!singleOwner && !Store.UNMANAGED.equals(mode)) {
            throw new Rejection(400, "The mode must be single-owner or unmanaged.");
        }
        boolean declared;
        if (rereadable == null || "false".equalsIgnoreCase(rereadable)) {
            declared = false;
        } else if ("true".equalsIgnoreCase(rereadable)) {
            declared = true;
        } else {
            throw new Rejection(400, "rereadable must be true or false.");
        }
        Channel channel = saved.get();
        if (channel == null) {
            throw new Rejection(404, "There is no channel with ID " + channelId + ".");
        }
        if (singleOwner) {
            Refusal refusal = Eligibility.judge(channel, declared, writer, messages);
            if (refusal != null) {
                throw new Rejection(422, refusal.message);
            }
        }
        try {
            write.set(channelId, mode, declared);
        } catch (SQLException e) {
            String state = e.getSQLState() == null ? "unknown" : e.getSQLState();
            throw new Rejection(500,
                "The database did not take the change (SQLState " + state + "). Please try again.");
        }
    }
}
