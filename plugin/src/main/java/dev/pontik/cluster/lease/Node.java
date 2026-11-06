// SPDX-License-Identifier: MPL-2.0
package dev.pontik.cluster.lease;

/**
 * This node as the lease tables know it: the engine's server ID, the name an operator recognizes,
 * a random ID for this process start, and the plugin version.
 */
public final class Node {

    /** The protocol this version speaks. A live peer that advertises another makes this node decline. */
    public static final int PROTOCOL = 1;

    public final String id;
    public final String name;
    public final String instance;
    public final String pluginVersion;

    public Node(String id, String name, String instance, String pluginVersion) {
        this.id = id;
        this.name = name;
        this.instance = instance;
        this.pluginVersion = pluginVersion;
    }
}
