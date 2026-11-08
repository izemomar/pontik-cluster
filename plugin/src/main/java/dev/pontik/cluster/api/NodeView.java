// SPDX-License-Identifier: MPL-2.0
package dev.pontik.cluster.api;

import com.thoughtworks.xstream.annotations.XStreamAlias;
import java.util.ArrayList;
import java.util.List;

/**
 * This node as the plugin sees it: whether the plugin runs here, its timing, the peers it counts as
 * live, the leases it holds and every fault that is firing. It is read from memory, so it answers
 * even while the database is down.
 */
@XStreamAlias("pontikNode")
public class NodeView {

    /** The fixed lease timing, in milliseconds, and the renewals a lease survives. */
    @XStreamAlias("pontikTiming")
    public static class Timing {

        public long leaseMs;
        public long heartbeatMs;
        public long marginMs;
        public long jitterMs;
        public int tolerance;
    }

    /** A node whose row is young enough to count as live. */
    @XStreamAlias("pontikPeer")
    public static class Peer {

        public String nodeId;
        public String nodeName;
        public int protocolVersion;
    }

    /** A lease this node holds, with how long it may still read under it. */
    @XStreamAlias("pontikLease")
    public static class Lease {

        public String channelId;
        public long epoch;
        public long validForMs;
    }

    /** A fault that is firing, for one channel or for the node. */
    @XStreamAlias("pontikFault")
    public static class Fault {

        public String code;
        public String channelId;
        public String message;
    }

    public String nodeId;
    public String nodeName;
    public String instanceId;
    public String state;
    public String code;
    public boolean gateAvailable;
    public Timing timing = new Timing();
    public Long lastTickAgeMs;
    public Long lastSweepAgeMs;
    public Boolean engineBusy;
    public List<Peer> peers = new ArrayList<>();
    public List<Lease> leases = new ArrayList<>();
    public List<Fault> faults = new ArrayList<>();
}
