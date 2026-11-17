// SPDX-License-Identifier: MPL-2.0
package dev.pontik.cluster.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mirth.connect.donkey.model.channel.DeployedState;
import com.mirth.connect.donkey.server.channel.Channel;
import com.mirth.connect.server.controllers.DonkeyEngineController;
import java.lang.reflect.Field;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

class GateTest {

    /** Stands in for the engine controller, with the set where the real one keeps it. */
    static class Controller {
        @SuppressWarnings("unused")
        private final Set<Channel> deployingChannels = Collections.synchronizedSet(new HashSet<Channel>());
    }

    static class Subclass extends Controller {
    }

    @Test
    void theSetIsFoundInTheReal460EngineControllerWithTheExpectedType() {
        Field field = Gate.field(DonkeyEngineController.class);

        assertNotNull(field);
        assertEquals(Set.class, field.getType());
    }

    @Test
    void theSetIsFoundByWalkingUpFromASubclass() {
        assertNotNull(Gate.field(Subclass.class));
        assertNull(Gate.field(Object.class));
    }

    @Test
    void aPauseSetsTheInitialStateOfTheEnginesCopyOnly() throws Exception {
        Subclass engine = new Subclass();
        Channel deploying = new Channel();
        deploying.setChannelId("ch-1");
        deploying.setInitialState(DeployedState.STARTED);
        @SuppressWarnings("unchecked")
        Set<Channel> set = (Set<Channel>) Gate.field(Subclass.class).get(engine);
        set.add(deploying);

        Gate gate = Gate.find(engine);

        assertTrue(gate.pause("ch-1"));
        assertEquals(DeployedState.PAUSED, deploying.getInitialState());
        assertFalse(gate.pause("ch-2"));
    }
}
