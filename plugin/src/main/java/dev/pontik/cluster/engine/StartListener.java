// SPDX-License-Identifier: MPL-2.0
package dev.pontik.cluster.engine;

import com.mirth.connect.donkey.model.event.DeployedStateEventType;
import com.mirth.connect.donkey.model.event.Event;
import com.mirth.connect.donkey.server.event.DeployedStateEvent;
import com.mirth.connect.donkey.server.event.EventType;
import com.mirth.connect.server.event.EventListener;
import dev.pontik.cluster.lease.Reconciler;
import java.util.Collections;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Hears every source that starts, and queues the lane of a protected channel, so a start on a node
 * that does not own the channel is undone within moments. It runs on its own consumer thread, which
 * the engine starts for every event listener, and only queues work. An event the engine never
 * delivers is caught by the next sweep.
 */
final class StartListener extends EventListener {

    private final Predicate<String> singleOwner;
    private final Reconciler lanes;

    StartListener(Predicate<String> singleOwner, Reconciler lanes) {
        this.singleOwner = singleOwner;
        this.lanes = lanes;
    }

    @Override
    public Set<EventType> getEventTypes() {
        return Collections.singleton(EventType.DEPLOY_STATE);
    }

    @Override
    protected void processEvent(Event event) {
        if (!(event instanceof DeployedStateEvent)) {
            return;
        }
        DeployedStateEvent deployed = (DeployedStateEvent) event;
        if (deployed.getState() == DeployedStateEventType.STARTED && Integer.valueOf(0).equals(deployed.getMetaDataId())
                && singleOwner.test(deployed.getChannelId())) {
            lanes.queue(deployed.getChannelId());
        }
    }

    @Override
    protected void onShutdown() {
    }
}
