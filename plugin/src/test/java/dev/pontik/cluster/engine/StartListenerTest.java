// SPDX-License-Identifier: MPL-2.0
package dev.pontik.cluster.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.mirth.connect.donkey.model.event.DeployedStateEventType;
import com.mirth.connect.donkey.server.event.DeployedStateEvent;
import com.mirth.connect.donkey.server.event.EventType;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;

class StartListenerTest {

    private final List<String> queued = new ArrayList<>();
    private final StartListener listener = new StartListener("ch-1"::equals, queued::add);

    @Test
    void itListensToDeployedStateEventsOnly() {
        assertEquals(Collections.singleton(EventType.DEPLOY_STATE), listener.getEventTypes());
    }

    @Test
    void onlyAStartedSourceOfAProtectedChannelQueuesItsLane() {
        listener.processEvent(event("ch-1", 0, DeployedStateEventType.STARTED));
        listener.processEvent(event("ch-1", 1, DeployedStateEventType.STARTED));
        listener.processEvent(event("ch-1", 0, DeployedStateEventType.STOPPED));
        listener.processEvent(event("ch-2", 0, DeployedStateEventType.STARTED));

        assertEquals(Arrays.asList("ch-1"), queued);
    }

    private static DeployedStateEvent event(String channelId, int metaDataId, DeployedStateEventType state) {
        return new DeployedStateEvent(channelId, "name", metaDataId, "Source", state);
    }
}
