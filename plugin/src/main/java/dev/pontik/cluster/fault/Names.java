// SPDX-License-Identifier: MPL-2.0
package dev.pontik.cluster.fault;

/** The names an operator recognizes, for every line we log. */
public interface Names {

    String nodeId();

    String nodeName();

    /** Returns the channel's name, or its ID when we do not know the name. */
    String channelName(String channelId);
}
