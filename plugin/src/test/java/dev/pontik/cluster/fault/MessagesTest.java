// SPDX-License-Identifier: MPL-2.0
package dev.pontik.cluster.fault;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class MessagesTest {

    private final Messages m = new Messages(24, 90, 30);

    @Test
    void pc101SaysWhoOwnsTheChannelNowAndWhereToLookForADuplicate() {
        assertEquals("This node held the lease on this channel, but the database now names \"node-b\" as the owner "
            + "(epoch 7); this node has stopped its source. Please check the destinations of this channel for a "
            + "message delivered twice around 2026-11-04T10:00:00Z; if it repeats, check the database clock and who "
            + "else writes pcl_lease.", m.pc101Owner("node-b", 7, "2026-11-04T10:00:00Z"));
        assertEquals("This node held the lease on this channel, but the database now names no owner (epoch 7); this "
            + "node has stopped its source. Please check the destinations of this channel for a message delivered "
            + "twice around 2026-11-04T10:00:00Z; if it repeats, check the database clock and who else writes "
            + "pcl_lease.", m.pc101Owner(null, 7, "2026-11-04T10:00:00Z"));
        assertEquals("This node held the lease on this channel, but the release matched no row; this node has "
            + "stopped its source. Please check the destinations of this channel for a message delivered twice "
            + "around 2026-11-04T10:00:00Z; if it repeats, check the database clock and who else writes pcl_lease.",
            m.pc101Release("2026-11-04T10:00:00Z"));
    }

    @Test
    void pc102To105SayWhatThisNodeStoppedAndWhatToCheck() {
        assertEquals("No renewal was confirmed for 24 seconds, so this node stopped its 3 protected sources; other "
            + "nodes take them over. Please check this node's connection to the database.", m.pc102(3));
        assertEquals("No node has owned this channel for 90 seconds. On this node it is stopped. Please start it on "
            + "a node where it is deployed, and check that every node reaches the database.", m.pc103(true));
        assertEquals("No node has owned this channel for 90 seconds. On this node it is waiting to take over. Please "
            + "check that every node reaches the database.", m.pc103(false));
        assertEquals("This node could not stop this channel's source (still stopping after 30 seconds) although "
            + "\"node-b\" owns it, so both may read. Please stop the channel by hand on this node; if it does not "
            + "stop, Halt it, which can deliver the message in flight twice.",
            m.pc104("\"node-b\"", m.stillStopping()));
        assertEquals("This node could not stop this channel's source although another node owns it, so both may read. "
            + "Please stop the channel by hand on this node; if it does not stop, Halt it, which can deliver the "
            + "message in flight twice.", m.pc104("another node", null));
        assertEquals("Something keeps starting this channel on this node although \"node-b\" owns it, so it may "
            + "read twice. Please find the script or monitor that starts it, and start protected channels on the node "
            + "that owns them.", m.pc105("\"node-b\""));
    }

    @Test
    void pc106AndPc107SayWhatIsRefusedOrOffAndHowToFixIt() {
        assertEquals("Refused: Poll Once on Start is on, so a start on the wrong node would read at once, before the "
            + "plugin can stop it. Please turn the setting off, or use unmanaged for this channel.", m.pc106(true));
        assertEquals("Refused: post-processing runs once after all messages, so a stop partway through a poll would "
            + "lose rows. Please turn the setting off, or use unmanaged for this channel.", m.pc106(false));
        assertEquals("The plugin is off on this node: the database user may not create table pcl_channel_config. "
            + "Every channel on this node runs as it does without the plugin. Please run the schema script from the "
            + "README, then restart this node.", m.pc107CannotCreate("pcl_channel_config"));
        assertEquals("The plugin is off on this node: the database user lacks DELETE on pcl_lease. Every channel on "
            + "this node runs as it does without the plugin. Please run the GRANT line of the README's schema script, "
            + "then restart this node.", m.pc107Privileges("DELETE on pcl_lease"));
        assertEquals("The plugin is off on this node: column pcl_lease.acquired_at is timestamp with time zone, and "
            + "the plugin needs timestamp without time zone. Every channel on this node runs as it does without the "
            + "plugin. Please change it as the README's schema script shows, then restart this node.",
            m.pc107Column("pcl_lease.acquired_at", "timestamp with time zone"));
        assertEquals("The plugin is off on this node: the database could not be reached (SQLState 08001). Every "
            + "channel on this node runs as it does without the plugin. Please check this node's connection to the "
            + "database, then restart this node.", m.pc107Failed("the database could not be reached (SQLState 08001)"));
        assertEquals("The plugin is off on this node: a setup statement was canceled before it finished (SQLState "
            + "57014). Every channel on this node runs as it does without the plugin. Please restart this node, which "
            + "is enough if another node was starting at the same moment; if it happens again, check this node's "
            + "connection to the database.", m.pc107Canceled());
        assertEquals("The plugin is off on this node: an unexpected NoSuchMethodError stopped its start. Every "
            + "channel on this node runs as it does without the plugin. Please restart this node; if it happens "
            + "again, please report it with the engine log.", m.pc107Unexpected("NoSuchMethodError"));
        assertEquals("The plugin is off on this node: node \"node-b\" runs protocol 2, which this version does not "
            + "know. Every channel on this node runs as it does without the plugin. Please install the same plugin "
            + "version on every node, then restart this one.", m.pc107Peer("node-b", 2));
    }

    @Test
    void pc110To119SayWhatHappenedAndWhatYouDo() {
        assertEquals("This node has not started a lease renewal for 21 seconds. It stops the source of each channel it "
            + "holds before that channel's lease runs out, and the other nodes take the channels over. Please take a "
            + "thread dump of this node and restart it.", m.pc110(21));
        assertEquals("Refused: the source type is HTTP Listener, not a File, Database or JavaScript Reader. Please use "
            + "unmanaged for this channel.", m.pc112("HTTP Listener"));
        assertEquals("The engine database is mysql; the plugin runs only on PostgreSQL and does nothing on this node. "
            + "Please uninstall it, or move the engine to PostgreSQL.", m.pc113("mysql"));
        assertEquals("Refused: the read may change data (a JavaScript Reader), and the channel is not declared "
            + "rereadable. If a read that this node stops partway leaves its data in place to be read again, set "
            + "rereadable=true; otherwise, please use unmanaged for this channel.",
            m.pc114(Messages.ChangingRead.JAVASCRIPT_READER));
        assertEquals("Refused: the read may change data (a Database Reader with Use JavaScript set to Yes), and "
            + "the channel is not declared rereadable. If a read that this node stops partway leaves its data in place "
            + "to be read again, set rereadable=true; otherwise, please use unmanaged for this channel.",
            m.pc114(Messages.ChangingRead.SCRIPT_MODE));
        assertEquals("Refused: the read may change data (a select that writes), and the channel is not declared "
            + "rereadable. If a read that this node stops partway leaves its data in place to be read again, set "
            + "rereadable=true; otherwise, please use unmanaged for this channel.",
            m.pc114(Messages.ChangingRead.WRITING_SELECT));
        assertEquals("Refused: Channel Writer \"Router\" sends messages into this channel, and they would fail on "
            + "every node but the owner. Please route them another way, or use unmanaged for this channel.",
            m.pc115("Router"));
        assertEquals("This node cannot reach the engine's runtime copy of this channel, so it cannot keep this channel "
            + "Paused; this channel runs as configured here. Please redeploy this channel; if it happens again, "
            + "please report it with the engine log.", m.pc116(false));
        assertEquals("This node cannot reach the engine's runtime copy of any channel, so it cannot keep any channel "
            + "Paused; every protected channel runs as configured here. Please restart this node; if it happens "
            + "again, please report it with the engine log.", m.pc116(true));
        assertEquals("This node could not start this channel after taking it over (STOPPED); it released the lease "
            + "and stays out for 90 seconds while another node takes over. Please check this node's access to the "
            + "channel's source: the share, the database or the script.", m.pc118("STOPPED"));
        assertEquals("This node could not start this channel after taking it over; it released the lease and stays "
            + "out for 90 seconds while another node takes over. Please check this node's access to the channel's "
            + "source: the share, the database or the script.", m.pc118(null));
        assertEquals("Another process uses this server ID (instance 0f3c), so leases cannot tell the two apart; "
            + "every channel on this node is reported unprotected. Please give this node its own server ID: stop "
            + "it, delete appdata/server.id, start it.", m.pc119("0f3c"));
    }
}
