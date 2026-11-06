// SPDX-License-Identifier: MPL-2.0
package dev.pontik.cluster.fault;

/**
 * The text of every fault: what happened, then what you do. The README's Fault Codes section
 * carries the same texts. The durations come from the lease timing, so the text always says what
 * this node really waits. In a message, a name an operator chose, such as a node or a Channel
 * Writer, appears in double quotes.
 */
public final class Messages {

    /** Why a channel is refused as a reader that may change data. */
    public enum ChangingRead { JAVASCRIPT_READER, SCRIPT_MODE, WRITING_SELECT }

    private final long validitySeconds;
    private final long alarmSeconds;
    private final long leaseSeconds;

    /**
     * Creates the texts for the lease timing this node runs with.
     *
     * @param validitySeconds how long after a renewal is sent this node may keep reading
     * @param alarmSeconds the no-owner alarm and the start-failure cool-down
     * @param leaseSeconds the lease
     */
    public Messages(long validitySeconds, long alarmSeconds, long leaseSeconds) {
        this.validitySeconds = validitySeconds;
        this.alarmSeconds = alarmSeconds;
        this.leaseSeconds = leaseSeconds;
    }

    /**
     * Returns the PC-101 text for a lease the database now gives to another owner or epoch. The
     * owner is null when the database names no owner.
     */
    public String pc101Owner(String owner, long epoch, String time) {
        return pc101("the database now names " + (owner == null ? "no owner" : "\"" + owner + "\" as the owner")
            + " (epoch " + epoch + ")", time);
    }

    public String pc101Release(String time) {
        return pc101("the release matched no row", time);
    }

    private static String pc101(String what, String time) {
        return "This node held the lease on this channel, but " + what + "; this node has stopped its source. "
            + "Please check the destinations of this channel for a message delivered twice around " + time
            + "; if it repeats, check the database clock and who else writes pcl_lease.";
    }

    public String pc102(int sources) {
        return "No renewal was confirmed for " + validitySeconds + " seconds, so this node stopped its " + sources
            + " protected " + (sources == 1 ? "source" : "sources") + "; other nodes take them over. "
            + "Please check this node's connection to the database.";
    }

    public String pc103(boolean stoppedHere) {
        return "No node has owned this channel for " + alarmSeconds + " seconds. "
            + (stoppedHere
                ? "On this node it is stopped. Please start it on a node where it is deployed, and check that "
                    + "every node reaches the database."
                : "On this node it is waiting to take over. Please check that every node reaches the database.");
    }

    /**
     * Returns the PC-104 text. The cause is a fixed phrase or an SQLState, never exception text, and
     * null when none is known.
     */
    public String pc104(String owner, String cause) {
        return "This node could not stop this channel's source" + because(cause) + " although \"" + owner
            + "\" owns it, so both may read. Please stop the channel by hand on this node; if it does not stop, "
            + "Halt it, which can deliver the message in flight twice.";
    }

    /** Returns the cause PC-104 names when the stop is still pending. */
    public String stillStopping() {
        return "still stopping after " + leaseSeconds + " seconds";
    }

    public String pc105(String owner) {
        return "Something keeps starting this channel on this node although \"" + owner
            + "\" owns it, so it may read twice. Please find the script or monitor that starts it, "
            + "and start protected channels on the node that owns them.";
    }

    public String pc106(boolean pollOnStart) {
        return "Refused: " + (pollOnStart
                ? "Poll Once on Start is on, so a start on the wrong node would read at once, before the plugin "
                    + "can stop it"
                : "post-processing runs once after all messages, so a stop partway through a poll would lose rows")
            + ". Please turn the setting off, or set the mode back to unmanaged.";
    }

    public String pc107CannotCreate(String table) {
        return pc107Start("the database user may not create table " + table)
            + "Please run the schema script from the README, then restart this node.";
    }

    public String pc107Privileges(String privileges) {
        return pc107Start("the database user lacks " + privileges)
            + "Please run the GRANT line of the README's schema script, then restart this node.";
    }

    public String pc107Column(String column, String type) {
        return pc107Start("column " + column + " is " + type + ", and the plugin needs timestamp without time zone")
            + "Please change it as the README's schema script shows, then restart this node.";
    }

    /**
     * Returns the PC-107 text for a start that failed. The reason is a fixed phrase or an SQLState,
     * never exception text.
     */
    public String pc107Failed(String reason) {
        return pc107Start(reason) + "Please check this node's connection to the database, then restart this node.";
    }

    /**
     * Returns the PC-107 text for a setup statement that was canceled, by our query timeout, a
     * shorter server timeout or an administrator. The SQLState cannot tell which, nor whether the
     * statement waited for another node's setup, so the text names no cause.
     */
    public String pc107Canceled() {
        return pc107Start("a setup statement was canceled before it finished (SQLState 57014)")
            + "Please restart this node, which is enough if another node was starting at the same moment; "
            + "if it happens again, check this node's connection to the database.";
    }

    /**
     * Returns the PC-107 text for a start that something unexpected stopped. The type is the simple
     * class name of what stopped it, never its message.
     */
    public String pc107Unexpected(String type) {
        return pc107Start("an unexpected " + type + " stopped its start")
            + "Please restart this node; if it happens again, please report it with the engine log.";
    }

    public String pc107Peer(String peer, int protocol) {
        return pc107Start("node \"" + peer + "\" runs protocol " + protocol + ", which this version does not know")
            + "Please install the same plugin version on every node, then restart this one.";
    }

    private static String pc107Start(String reason) {
        return "The plugin is off on this node: " + reason + ". "
            + "Every channel on this node runs as it does without the plugin. ";
    }

    public String pc110(long seconds) {
        return "This node has not started a lease renewal for " + seconds + " seconds. It stops the source of each "
            + "channel it holds before that channel's lease runs out, and the other nodes take the channels over. "
            + "Please take a thread dump of this node and restart it.";
    }

    public String pc112(String sourceType) {
        return "Refused: the source type is " + sourceType + ", not a File, Database or JavaScript Reader. "
            + "Please set the mode back to unmanaged.";
    }

    /** Returns the PC-113 text. The database type is the engine's own name for its database, never exception text. */
    public String pc113(String databaseType) {
        return "The engine database is " + databaseType + "; the plugin runs only on PostgreSQL and does "
            + "nothing on this node. Please uninstall it, or move the engine to PostgreSQL.";
    }

    public String pc114(ChangingRead read) {
        String kind;
        switch (read) {
            case JAVASCRIPT_READER:
                kind = "a JavaScript Reader";
                break;
            case SCRIPT_MODE:
                kind = "a Database Reader in script mode";
                break;
            default:
                kind = "a select that writes";
                break;
        }
        return "Refused: the read may change data (" + kind + "), and the channel is not declared rereadable. "
            + "If a read that this node stops partway leaves its data in place to be read again, set "
            + "rereadable=true; otherwise, please set the mode back to unmanaged.";
    }

    public String pc115(String writer) {
        return "Refused: Channel Writer \"" + writer + "\" sends messages into this channel, and they would fail "
            + "on every node but the owner. Please route them another way, or set the mode back to unmanaged.";
    }

    public String pc116(boolean wholeNode) {
        return "This node cannot reach the engine's runtime copy " + (wholeNode ? "of any channel" : "of this channel")
            + ", so it cannot keep " + (wholeNode ? "any source" : "its source") + " paused; "
            + (wholeNode ? "every protected channel" : "this channel") + " runs as configured here. "
            + (wholeNode ? "Please restart this node" : "Please redeploy this channel")
            + "; if it happens again, please report it with the engine log.";
    }

    /**
     * Returns the PC-118 text. The cause is a fixed phrase or an SQLState, never exception text, and
     * null when none is known.
     */
    public String pc118(String cause) {
        return "This node could not start this channel after taking it over" + because(cause) + "; it released the "
            + "lease and stays out for " + alarmSeconds + " seconds while another node takes over. Please check "
            + "this node's access to the channel's source: the share, the database or the script.";
    }

    public String pc119(String otherInstance) {
        return "Another process uses this server ID (instance " + otherInstance + "), so leases cannot tell "
            + "the two apart; every channel on this node is reported unprotected. Please give this node its "
            + "own server ID: stop it, delete appdata/server.id, start it.";
    }

    private static String because(String cause) {
        return cause == null ? "" : " (" + cause + ")";
    }
}
