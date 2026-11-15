// SPDX-License-Identifier: MPL-2.0
package dev.pontik.cluster.api;

import com.thoughtworks.xstream.annotations.XStreamAlias;
import java.util.ArrayList;
import java.util.List;

/**
 * One channel as this node sees it: its mode, whether it is protected here and why, its lease as
 * the last read-back found it, and what this node is doing with its source. Times are ISO-8601 in
 * UTC. A field without a value is left out of both the XML and the JSON.
 */
@XStreamAlias("pontikChannel")
public class ChannelView {

    /** What this node is doing with the channel's source. */
    public static class ThisNode {

        public String role;
        public String sourceState;
        public boolean stoppedByUs;
        public boolean stoppedByOperator;
    }

    public String channelId;
    public String name;
    public String sourceType;
    public String mode;
    public boolean rereadable;
    public String status;
    public String reason;
    public List<String> codes = new ArrayList<>();
    public String owner;
    public String ownerName;
    public Long epoch;
    public String heldSince;
    public String leaseExpiresAt;
    public Boolean stopped;
    public String asOf;
    public Long readBackAgeMs;
    public ThisNode thisNode = new ThisNode();
}
