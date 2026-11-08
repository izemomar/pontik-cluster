// SPDX-License-Identifier: MPL-2.0
package dev.pontik.cluster.engine;

import com.mirth.connect.client.core.api.util.OperationUtil;
import com.mirth.connect.model.Channel;
import com.mirth.connect.model.DatabaseSettings;
import com.mirth.connect.model.ExtensionPermission;
import com.mirth.connect.model.ServerEventContext;
import com.mirth.connect.model.converters.ObjectXMLSerializer;
import com.mirth.connect.plugins.ChannelPlugin;
import com.mirth.connect.plugins.ServicePlugin;
import com.mirth.connect.server.controllers.ConfigurationController;
import com.mirth.connect.server.controllers.ControllerFactory;
import dev.pontik.cluster.api.ChannelView;
import dev.pontik.cluster.api.ClusterServletInterface;
import dev.pontik.cluster.api.NodeView;
import dev.pontik.cluster.fault.Code;
import dev.pontik.cluster.lease.ConfigWriter;
import dev.pontik.cluster.lease.Coordinator;
import dev.pontik.cluster.lease.DbSettings;
import dev.pontik.cluster.lease.Keeper;
import dev.pontik.cluster.lease.Node;
import dev.pontik.cluster.lease.Store;
import dev.pontik.cluster.lease.Timing;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The plugin as the engine loads it: a service plugin that starts and stops the coordinator, and a
 * channel plugin whose hooks run as the engine deploys and undeploys each channel.
 *
 * The engine lists a plugin once for each of the two roles, so it calls {@link #start()} and
 * {@link #stop()} twice. Only the first call of each does anything.
 */
public final class ClusterPlugin implements ServicePlugin, ChannelPlugin {

    private static final Timing TIMING = Timing.STANDARD;

    private final AtomicBoolean started = new AtomicBoolean();
    private final AtomicBoolean stopped = new AtomicBoolean();

    private volatile Coordinator coordinator;
    private volatile ChannelReconciler reconciler;
    private volatile Targets targets;
    private volatile Report report;
    private EngineSink sink;
    private ExecutorService lanePool;
    private ScheduledExecutorService sweeper;
    private StartListener listener;

    @Override
    public String getPluginPointName() {
        return ClusterServletInterface.PLUGIN_POINT;
    }

    @Override
    public void init(Properties properties) {
    }

    @Override
    public void update(Properties properties) {
    }

    /** Returns no properties: we keep nothing there, since an uninstall on one node deletes them for all. */
    @Override
    public Properties getDefaultProperties() {
        return new Properties();
    }

    @Override
    public ExtensionPermission[] getExtensionPermissions() {
        return new ExtensionPermission[] {
            new ExtensionPermission(ClusterServletInterface.PLUGIN_POINT, ClusterServletInterface.PERMISSION_VIEW,
                "Shows whether each channel is protected on this node, and which node owns it.",
                OperationUtil.getOperationNamesForPermission(ClusterServletInterface.PERMISSION_VIEW,
                    ClusterServletInterface.class), new String[0]),
            new ExtensionPermission(ClusterServletInterface.PLUGIN_POINT, ClusterServletInterface.PERMISSION_SET_MODE,
                "Allows protecting a channel, or handing it back to every node.",
                OperationUtil.getOperationNamesForPermission(ClusterServletInterface.PERMISSION_SET_MODE,
                    ClusterServletInterface.class), new String[0])
        };
    }

    /**
     * Starts the plugin before the engine's startup deploy. If anything fails, the coordinator stays
     * off with a fault, and every channel on this node runs as it does without the plugin.
     */
    @Override
    public void start() {
        if (!started.compareAndSet(false, true)) {
            return;
        }
        EngineSink.enableInfo(EngineSink.LOGGER);
        sink = new EngineSink();
        Coordinator built = null;
        boolean coordinatorStarted = false;
        try {
            ConfigurationController config = ControllerFactory.getFactory().createConfigurationController();
            lanePool = Executors.newCachedThreadPool(r -> daemon(r, "pontik-cluster-lane"));
            Lanes lanes = new Lanes(lanePool, sink);

            Coordinator c = new Coordinator(settings(config), node(config), TIMING, sink, lanes);
            built = c;
            Targets t = new Targets(sink);
            Gate gate = Gate.find(ControllerFactory.getFactory().createEngineController());
            ChannelReconciler r = new ChannelReconciler(new OieEngine(), c.ownership(), c.local(), c.faults(),
                c.messages(), sink, c, TIMING, c::keeper, System::nanoTime, lanes, t,
                gate == null ? null : gate::pause);
            lanes.bind(r::reconcile);
            coordinator = c;
            targets = t;
            reconciler = r;
            report = new Report(c.node(), TIMING, c.ownership(), c.local(), c.faults(), c, r);

            // Before the startup deploy, so a writer and its target deployed together refuse the target.
            try {
                OieEngine engine = new OieEngine();
                t.replace(engine.saved(), engine.enabled());
            } catch (Throwable e) {
                sink.warn("Could not read the saved channels at startup; the first sweep finds the Channel Writer "
                    + "targets.", e);
            }
            ObjectXMLSerializer.getInstance().processAnnotations(new Class<?>[] {
                ChannelView.class, ChannelView.ThisNode.class, NodeView.class, NodeView.Timing.class,
                NodeView.Peer.class, NodeView.Lease.class, NodeView.Fault.class
            });

            coordinatorStarted = true;
            c.start();
            if (c.state() == Coordinator.State.OFF) {
                return;
            }
            if (gate == null) {
                c.faults().raise(Code.PC116, null, c.messages().pc116(true));
            }

            sweeper = Executors.newSingleThreadScheduledExecutor(x -> daemon(x, "pontik-cluster-sweep"));
            sweeper.scheduleWithFixedDelay(() -> {
                try {
                    r.sweep();
                } catch (Throwable e) {
                    sink.warn("The sweep failed; the next one tries again.", e);
                }
            }, 0, TIMING.heartbeatMs, TimeUnit.MILLISECONDS);

            listener = new StartListener(id -> singleOwner(c, id), lanes);
            ControllerFactory.getFactory().createEventController().addListener(listener);
        } catch (Throwable t) {
            if (built != null && !coordinatorStarted) {
                built.faults().raise(Code.PC107, null,
                    built.messages().pc107Unexpected(t.getClass().getSimpleName()));
            }
            sink.warn("Pontik Cluster could not start, so every channel on this node runs as it does without the "
                + "plugin.", t);
        }
    }

    /**
     * Stops the plugin at shutdown, after the engine has undeployed every channel: the sweep and the
     * lanes first, then the keeper and the fence, then the release of every lease. We restore nothing,
     * since the engine is going down.
     */
    @Override
    public void stop() {
        if (!started.get() || !stopped.compareAndSet(false, true)) {
            return;
        }
        try {
            if (listener != null) {
                ControllerFactory.getFactory().createEventController().removeListener(listener);
            }
            if (sweeper != null) {
                sweeper.shutdownNow();
            }
            if (lanePool != null) {
                lanePool.shutdownNow();
            }
            if (coordinator != null) {
                coordinator.stop();
            }
        } catch (Throwable t) {
            sink.warn("Pontik Cluster could not stop cleanly.", t);
        } finally {
            if (sink != null) {
                sink.close();
            }
        }
    }

    @Override
    public void save(Channel channel, ServerEventContext context) {
    }

    @Override
    public void deploy(Channel channel, ServerEventContext context) {
        ChannelReconciler r = reconciler;
        if (r != null) {
            r.deployed(channel);
        }
    }

    @Override
    public void deploy(ServerEventContext context) {
    }

    @Override
    public void undeploy(String channelId, ServerEventContext context) {
        ChannelReconciler r = reconciler;
        if (r != null) {
            r.undeployed(channelId);
        }
    }

    @Override
    public void undeploy(ServerEventContext context) {
    }

    /** Forgets a deleted channel's mode, and keeps its lease row, so its epoch is never handed out twice. */
    @Override
    public void remove(Channel channel, ServerEventContext context) {
        Coordinator c = coordinator;
        ConfigWriter writer = c == null ? null : c.writer();
        if (writer == null) {
            return;
        }
        try {
            writer.remove(channel.getId());
        } catch (Throwable t) {
            sink.warn("Could not forget the mode of the deleted channel " + channel.getName() + " ("
                + channel.getId() + "). GET /channels keeps listing it, and a channel created again under this ID "
                + "starts in that mode.", t);
        }
    }

    /** Returns every channel this node knows, or answers 503 with this node while the plugin is off here. */
    public List<ChannelView> channels() throws Rejection {
        Coordinator c = coordinator;
        Keeper k = c == null ? null : c.keeper();
        if (k == null) {
            throw new Rejection(503, local());
        }
        return report.channels(k, System.nanoTime());
    }

    /** Returns this node as the plugin sees it. */
    public NodeView local() {
        Coordinator c = coordinator;
        if (c == null) {
            NodeView v = new NodeView();
            v.state = Coordinator.State.OFF.name();
            return v;
        }
        return report.node(c.state().name(), c.keeper(), System.nanoTime());
    }

    /** Sets a channel's mode, or throws the rejection to answer with. */
    public void setMode(String channelId, String mode, String rereadable, String user) throws Rejection {
        Coordinator c = coordinator;
        ConfigWriter writer = c == null ? null : c.writer();
        Modes.set(c == null || c.state() == Coordinator.State.OFF || writer == null, this::local, channelId, mode,
            rereadable, () -> ControllerFactory.getFactory().createChannelController().getChannelById(channelId),
            targets == null ? null : targets.writer(channelId), c == null ? null : c.messages(),
            (id, m, declared) -> writer.setMode(id, m, declared, user));
    }

    private static boolean singleOwner(Coordinator c, String channelId) {
        Keeper k = c.keeper();
        Store.Row row = k == null ? null : k.readBack().rows.get(channelId);
        return row != null && row.singleOwner();
    }

    private static DbSettings settings(ConfigurationController config) {
        try {
            DatabaseSettings s = config.getDatabaseSettings();
            return new DbSettings(config.getDatabaseType(), s.getDatabaseUrl(), s.getDatabaseUsername(),
                s.getDatabasePassword());
        } catch (Exception e) {
            // The coordinator then fails to connect and raises PC-107, and the plugin stays off.
            return new DbSettings(config.getDatabaseType(), null, null, null);
        }
    }

    private static Node node(ConfigurationController config) {
        String id = config.getServerId();
        String name = config.getServerName();
        String version = ClusterPlugin.class.getPackage().getImplementationVersion();
        return new Node(id, name == null || name.trim().isEmpty() ? id : name, UUID.randomUUID().toString(),
            version == null ? "unknown" : version);
    }

    private static Thread daemon(Runnable r, String name) {
        Thread t = new Thread(r, name);
        t.setDaemon(true);
        return t;
    }
}
