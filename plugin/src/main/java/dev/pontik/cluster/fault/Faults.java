// SPDX-License-Identifier: MPL-2.0
package dev.pontik.cluster.fault;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The firing state of every fault, per code and scope. A fault fires once when it starts and
 * clears once when it ends; we never repeat it as a reminder. The scope is a channel ID, or null
 * for the node.
 */
public final class Faults {

    /** One firing fault, as the status call shows it. */
    public static final class Firing {
        public final Code code;
        public final String channelId;
        public final String message;

        Firing(Code code, String channelId, String message) {
            this.code = code;
            this.channelId = channelId;
            this.message = message;
        }
    }

    private final Sink sink;
    private final Names names;
    private final Map<String, Firing> firing = new LinkedHashMap<>();

    public Faults(Sink sink, Names names) {
        this.sink = sink;
        this.names = names;
    }

    // We build the line before we take our lock. Names can take locks of its own, and one of them
    // may be held by a thread that is waiting to raise a fault here.
    public void raise(Code code, String channelId, String message) {
        String line = line(code, channelId, message);
        synchronized (this) {
            if (!code.oneShot) {
                String key = key(code, channelId);
                if (firing.containsKey(key)) {
                    return;
                }
                firing.put(key, new Firing(code, channelId, message));
            }
            sink.fired(code, channelId, line);
        }
    }

    public void clear(Code code, String channelId) {
        if (!isFiring(code, channelId)) {
            return;
        }
        String line = line(code, channelId, "cleared.");
        synchronized (this) {
            if (firing.remove(key(code, channelId)) != null) {
                sink.cleared(line);
            }
        }
    }

    public synchronized boolean isFiring(Code code, String channelId) {
        return firing.containsKey(key(code, channelId));
    }

    public synchronized List<Firing> firing() {
        return new ArrayList<>(firing.values());
    }

    String line(Code code, String channelId, String message) {
        String channel = channelId == null ? "" : names.channelName(channelId) + " (" + channelId + ") ";
        return "[PONTIK-CLUSTER] " + code.label() + " " + channel + "on " + names.nodeName()
            + " (" + names.nodeId() + "): " + message;
    }

    private static String key(Code code, String channelId) {
        return code.name() + "|" + (channelId == null ? "" : channelId);
    }
}
