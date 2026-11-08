// SPDX-License-Identifier: MPL-2.0
package dev.pontik.cluster.engine;

import com.mirth.connect.donkey.model.channel.DeployedState;
import com.mirth.connect.model.Channel;
import com.mirth.connect.model.DashboardStatus;
import java.util.List;
import java.util.function.Predicate;

/** The engine calls the reconciler and the sweep make. The tests use one of their own. */
interface Engine {

    /** Returns the status of every deployed channel, those being deployed or undeployed included. */
    List<DashboardStatus> statuses();

    /** Returns one channel's status, or null when it is not deployed. */
    DashboardStatus status(String channelId);

    /** Starts the channel's source connector and waits for the engine's task. */
    void startSource(String channelId) throws Exception;

    /** Stops the channel's source connector and waits for the engine's task. */
    void stopSource(String channelId) throws Exception;

    /** Returns the model the engine deployed for this channel, or null. */
    Channel deployed(String channelId);

    /** Returns every saved channel, read through the engine's cache. */
    List<Channel> saved();

    /** Returns which saved channels are enabled. Each call reads the flags from the engine's database once. */
    Predicate<String> enabled();

    /** Returns the state of the status's source connector, or null when the status has none. */
    static DeployedState source(DashboardStatus status) {
        for (DashboardStatus child : status.getChildStatuses()) {
            if (child.getMetaDataId() != null && child.getMetaDataId() == 0) {
                return child.getState();
            }
        }
        return null;
    }

    /** Returns whether a channel state is one the engine rests in: started, paused or stopped. */
    static boolean settled(DeployedState state) {
        return state == DeployedState.STARTED || state == DeployedState.PAUSED || state == DeployedState.STOPPED;
    }

    /** Returns whether a channel state means the engine is deploying, undeploying, starting or stopping. */
    static boolean busy(DeployedState state) {
        return state == DeployedState.DEPLOYING || state == DeployedState.UNDEPLOYING
            || state == DeployedState.STARTING || state == DeployedState.STOPPING;
    }
}
