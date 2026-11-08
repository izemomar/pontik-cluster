// SPDX-License-Identifier: MPL-2.0
package dev.pontik.cluster.engine;

import com.mirth.connect.donkey.model.channel.DeployedState;
import com.mirth.connect.donkey.server.channel.Channel;
import java.lang.reflect.Field;
import java.util.Set;

/**
 * Keeps a channel's source stopped at its deploy, by setting the initial state of the engine's own
 * copy of the channel to Paused. The engine keeps that copy in a private set while it deploys, and
 * reads its initial state only after our deploy hook has run, so no public call reaches it.
 */
final class Gate {

    static final String FIELD = "deployingChannels";

    private final Object engine;
    private final Field field;

    private Gate(Object engine, Field field) {
        this.engine = engine;
        this.field = field;
    }

    /** Returns the gate for this engine controller, or null when its class has no such set. */
    static Gate find(Object engine) {
        Field field = field(engine.getClass());
        return field == null ? null : new Gate(engine, field);
    }

    /** Returns the set field of this class or of a class it extends, made accessible, or null. */
    static Field field(Class<?> type) {
        for (Class<?> c = type; c != null; c = c.getSuperclass()) {
            try {
                Field field = c.getDeclaredField(FIELD);
                if (!Set.class.isAssignableFrom(field.getType())) {
                    return null;
                }
                field.setAccessible(true);
                return field;
            } catch (NoSuchFieldException e) {
                // We look in the class it extends.
            }
        }
        return null;
    }

    /**
     * Pauses the engine's copy of a channel it is deploying, and returns whether that copy was there.
     * The engine guards the set with its own lock, so we take that lock while we walk it.
     */
    boolean pause(String channelId) throws IllegalAccessException {
        Set<?> deploying = (Set<?>) field.get(engine);
        synchronized (deploying) {
            for (Object item : deploying) {
                if (item instanceof Channel && channelId.equals(((Channel) item).getChannelId())) {
                    ((Channel) item).setInitialState(DeployedState.PAUSED);
                    return true;
                }
            }
        }
        return false;
    }
}
