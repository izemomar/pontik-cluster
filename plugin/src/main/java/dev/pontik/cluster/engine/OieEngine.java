// SPDX-License-Identifier: MPL-2.0
package dev.pontik.cluster.engine;

import com.mirth.connect.model.Channel;
import com.mirth.connect.model.ChannelMetadata;
import com.mirth.connect.model.DashboardStatus;
import com.mirth.connect.server.channel.ChannelTaskHandler;
import com.mirth.connect.server.controllers.ControllerFactory;
import com.mirth.connect.server.controllers.EngineController;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/** The engine calls, through the engine's own controllers. */
final class OieEngine implements Engine {

    @Override
    public List<DashboardStatus> statuses() {
        return engine().getChannelStatusList();
    }

    @Override
    public DashboardStatus status(String channelId) {
        List<DashboardStatus> statuses = engine().getChannelStatusList(Collections.singleton(channelId));
        return statuses.isEmpty() ? null : statuses.get(0);
    }

    @Override
    public void startSource(String channelId) throws Exception {
        run(true, channelId);
    }

    @Override
    public void stopSource(String channelId) throws Exception {
        run(false, channelId);
    }

    @Override
    public Channel deployed(String channelId) {
        return ControllerFactory.getFactory().createChannelController().getDeployedChannelById(channelId);
    }

    @Override
    public List<Channel> saved() {
        return ControllerFactory.getFactory().createChannelController().getChannels(null);
    }

    @Override
    public Predicate<String> enabled() {
        Map<String, ChannelMetadata> all =
            ControllerFactory.getFactory().createConfigurationController().getChannelMetadata();
        return id -> {
            ChannelMetadata metadata = all.get(id);
            return metadata == null || metadata.isEnabled();
        };
    }

    // The engine reports a failed task to the handler and returns normally, so we rethrow it.
    private static void run(boolean start, String channelId) throws Exception {
        Map<String, List<Integer>> source = Collections.singletonMap(channelId, Collections.singletonList(0));
        Exception[] failure = new Exception[1];
        ChannelTaskHandler handler = new ChannelTaskHandler() {
            @Override
            public void taskErrored(String id, Integer metaDataId, Exception e) {
                failure[0] = e;
            }
        };
        if (start) {
            engine().startConnector(source, handler);
        } else {
            engine().stopConnector(source, handler);
        }
        if (failure[0] != null) {
            throw failure[0];
        }
    }

    private static EngineController engine() {
        return ControllerFactory.getFactory().createEngineController();
    }
}
