// SPDX-License-Identifier: MPL-2.0
package dev.pontik.cluster.fault;

import java.util.HashMap;
import java.util.Map;

/** Names for the tests: one node, and channels named after their IDs unless told otherwise. */
public final class FixedNames implements Names {

    private final String nodeId;
    private final String nodeName;
    private final Map<String, String> channels = new HashMap<>();

    public FixedNames(String nodeId, String nodeName) {
        this.nodeId = nodeId;
        this.nodeName = nodeName;
    }

    public FixedNames channel(String channelId, String name) {
        channels.put(channelId, name);
        return this;
    }

    @Override
    public String nodeId() {
        return nodeId;
    }

    @Override
    public String nodeName() {
        return nodeName;
    }

    @Override
    public String channelName(String channelId) {
        return channels.getOrDefault(channelId, channelId);
    }
}
