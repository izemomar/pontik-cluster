// SPDX-License-Identifier: MPL-2.0
package dev.pontik.cluster.engine;

import com.mirth.connect.connectors.vm.VmDispatcherProperties;
import com.mirth.connect.model.Channel;
import com.mirth.connect.model.Connector;
import dev.pontik.cluster.fault.Sink;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/**
 * The channels a Channel Writer sends into, each with the name of a channel that sends. A protected
 * channel accepts messages only on its owner, so we refuse to protect a channel named here. We know
 * only the targets a writer names as a literal; one named with a variable is logged once.
 */
final class Targets {

    private final Sink sink;
    private final Set<String> warned = new HashSet<>();
    private Map<String, String> writers = new HashMap<>();

    Targets(Sink sink) {
        this.sink = sink;
    }

    /** Returns the name of a channel whose Channel Writer sends into this one, or null. */
    synchronized String writer(String channelId) {
        return writers.get(channelId);
    }

    /**
     * Adds the targets of a channel being deployed, and returns the channels this makes targets for
     * the first time. The deploy hook calls this for every channel, protected or not.
     */
    synchronized Set<String> add(Channel model) {
        Set<String> added = new HashSet<>();
        for (Map.Entry<String, String> e : scan(model).entrySet()) {
            if (writers.putIfAbsent(e.getKey(), e.getValue()) == null) {
                added.add(e.getKey());
            }
        }
        return added;
    }

    /**
     * Replaces the whole set with the writers among the given models that count, the saved channels and
     * the copies deployed here, and returns the channels that became or stopped being targets. A
     * disabled channel counts only while it is deployed here, since the engine never deploys a disabled
     * channel and a channel disabled after its deploy still sends.
     */
    synchronized Set<String> replace(Collection<Channel> models, Predicate<String> counts) {
        Map<String, String> fresh = new HashMap<>();
        for (Channel model : models) {
            if (counts.test(model.getId())) {
                for (Map.Entry<String, String> e : scan(model).entrySet()) {
                    fresh.putIfAbsent(e.getKey(), e.getValue());
                }
            }
        }
        Set<String> changed = new HashSet<>(fresh.keySet());
        changed.addAll(writers.keySet());
        changed.removeIf(id -> fresh.containsKey(id) == writers.containsKey(id));
        writers = fresh;
        return changed;
    }

    // Called with this object's lock held, so the warned set needs no lock of its own.
    private Map<String, String> scan(Channel model) {
        Map<String, String> found = new HashMap<>();
        for (Connector destination : model.getDestinationConnectors()) {
            if (!(destination.getProperties() instanceof VmDispatcherProperties)) {
                continue;
            }
            String target = ((VmDispatcherProperties) destination.getProperties()).getChannelId();
            if (target == null || target.isEmpty() || "none".equals(target)) {
                continue;
            }
            if (target.contains("${")) {
                if (warned.add(model.getId())) {
                    sink.warn("A Channel Writer in \"" + model.getName() + "\" chooses its target channel while "
                        + "it runs, so we cannot refuse to protect that target.", null);
                }
                continue;
            }
            found.put(target, model.getName());
        }
        return found;
    }
}
