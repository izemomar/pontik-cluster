// SPDX-License-Identifier: MPL-2.0
package dev.pontik.cluster.lease;

import dev.pontik.cluster.fault.Code;
import dev.pontik.cluster.fault.Faults;
import dev.pontik.cluster.fault.Messages;
import dev.pontik.cluster.fault.Names;
import dev.pontik.cluster.fault.Sink;
import java.sql.SQLException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/**
 * The start and stop of the lease side of the plugin. {@link #start()} runs before the engine's startup
 * deploy and never throws: if anything fails, the coordinator stays {@code OFF} with a fault, and
 * every channel on this node runs as it does without the plugin.
 */
public final class Coordinator implements Names {

    public enum State { OFF, RUNNING, DECLINED }

    private final DbSettings db;
    private final Node node;
    private final Timing timing;
    private final Sink sink;
    private final Reconciler reconciler;
    private final LongSupplier nanos;
    private final LocalState local;
    private final Ownership ownership;
    private final Faults faults;
    private final Messages messages;

    // Status calls and the engine's shutdown thread read these, and neither runs start().
    private volatile Link keeperLink;
    private volatile Link secondLink;
    private volatile ScheduledExecutorService keeperThread;
    private volatile ScheduledExecutorService fenceThread;
    private volatile Keeper keeper;
    private volatile ConfigWriter writer;

    public Coordinator(DbSettings db, Node node, Timing timing, Sink sink, Reconciler reconciler) {
        this(db, node, timing, sink, reconciler, System::nanoTime);
    }

    Coordinator(DbSettings db, Node node, Timing timing, Sink sink, Reconciler reconciler, LongSupplier nanos) {
        this.db = db;
        this.node = node;
        this.timing = timing;
        this.sink = sink;
        this.reconciler = reconciler;
        this.nanos = nanos;
        this.local = new LocalState(timing);
        this.ownership = new Ownership(reconciler, nanos);
        this.faults = new Faults(sink, this);
        this.messages = new Messages(seconds(timing.validityMs()), seconds(timing.alarmMs()), seconds(timing.leaseMs));
    }

    /**
     * Bootstraps the tables, registers this node and loads the config, all before the engine
     * deploys a channel; then starts the fence and the keeper.
     */
    public void start() {
        try {
            if (!"postgres".equals(db.type)) {
                faults.raise(Code.PC113, null, messages.pc113(String.valueOf(db.type)));
                return;
            }

            secondLink = new Link(Connections.second(db));
            Store.ReadBack initial;
            synchronized (secondLink) {
                Bootstrap.run(secondLink.get());
                JdbcStore startup = new JdbcStore(secondLink, node, Connections.SECOND_QUERY_TIMEOUT_SECONDS);
                startup.upsertNode();
                initial = startup.readBack();
            }
            keeperLink = new Link(Connections.keeper(db, timing));
            writer = new ConfigWriter(secondLink);

            fenceThread = Executors.newSingleThreadScheduledExecutor(r -> daemon(r, "pontik-cluster-fence"));
            keeperThread = Executors.newSingleThreadScheduledExecutor(r -> daemon(r, "pontik-cluster-keeper"));

            Fence fence = new Fence(ownership, Timer.on(fenceThread), nanos, faults, messages, timing, sink);
            Keeper started = new Keeper(new JdbcStore(keeperLink, node, 0), timing, node, ownership, local, fence,
                faults, messages, this, reconciler, sink, nanos,
                () -> ThreadLocalRandom.current().nextLong(timing.jitterMs + 1), initial);
            keeper = started;

            started.start(keeperThread);
            // The watchdog reads the local, never the field: off() clears the field while a run can be in flight.
            fenceThread.scheduleAtFixedRate(() -> fence.checkKeeper(started.lastTickNanos()), 1, 1, TimeUnit.SECONDS);
        } catch (BootstrapException e) {
            off();
            faults.raise(Code.PC107, null, pc107(e));
        } catch (SQLException e) {
            off();
            faults.raise(Code.PC107, null,
                "57014".equals(e.getSQLState()) ? messages.pc107Canceled() : messages.pc107Failed(describe(e)));
        } catch (Throwable t) {
            off();
            faults.raise(Code.PC107, null, messages.pc107Unexpected(t.getClass().getSimpleName()));
        } finally {
            sink.info("Pontik Cluster " + node.pluginVersion + " on " + node.name + " (server ID " + node.id
                + ", instance " + node.instance + "): " + state() + "; " + timing + ".");
        }
    }

    /**
     * Stops the keeper and waits for its thread, stops the fence, then releases every lease and marks
     * this node gone on the keeper connection. Call it only after the engine has undeployed every
     * channel. We restore nothing, since the engine is going down. If the keeper's thread does not end
     * in time, we release nothing, and the leases run out on their own within one lease.
     */
    public void stop() {
        try {
            boolean ended = true;
            try {
                if (keeperThread != null) {
                    keeperThread.shutdownNow();
                    ended = keeperThread.awaitTermination(timing.statementBudgetMs() + 1000, TimeUnit.MILLISECONDS);
                }
            } finally {
                // The fence stops even if the wait is interrupted, and before the release, so its watchdog
                // cannot raise a fault while we retry against a database that is down.
                if (fenceThread != null) {
                    fenceThread.shutdownNow();
                }
            }
            Keeper current = keeper;
            if (current != null) {
                // We never release while a tick may still be running: the leases expire on their own instead.
                if (ended) {
                    current.shutdown();
                } else {
                    sink.warn("The lease keeper did not stop in time, so its leases expire on their own within "
                        + "one lease.", null);
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Throwable t) {
            sink.warn("Pontik Cluster could not stop cleanly.", t);
        } finally {
            close(keeperLink);
            close(secondLink);
        }
    }

    public State state() {
        Keeper current = keeper;
        if (current == null) {
            return State.OFF;
        }
        return current.state() == Keeper.State.DECLINED ? State.DECLINED : State.RUNNING;
    }

    public LocalState local() {
        return local;
    }

    public Ownership ownership() {
        return ownership;
    }

    public Messages messages() {
        return messages;
    }

    public Faults faults() {
        return faults;
    }

    public Node node() {
        return node;
    }

    /** Returns the writer for mode changes and deleted channels, or null while the coordinator is {@code OFF}. */
    public ConfigWriter writer() {
        return keeper == null ? null : writer;
    }

    /** Returns the keeper, or null while the coordinator is {@code OFF}. */
    public Keeper keeper() {
        return keeper;
    }

    @Override
    public String nodeId() {
        return node.id;
    }

    @Override
    public String nodeName() {
        return node.name;
    }

    @Override
    public String channelName(String channelId) {
        return local.name(channelId);
    }

    private String pc107(BootstrapException e) {
        switch (e.kind) {
            case CANNOT_CREATE:
                return messages.pc107CannotCreate(e.subject);
            case MISSING_PRIVILEGE:
                return messages.pc107Privileges(e.subject);
            default:
                return messages.pc107Column(e.subject, e.detail);
        }
    }

    // The driver's own message can name the host or the user, so we report the SQLState only.
    private static String describe(SQLException e) {
        String state = e.getSQLState() == null ? "" : e.getSQLState();
        if (state.startsWith("08")) {
            return "the database could not be reached (SQLState " + state + ")";
        }
        if (state.startsWith("28")) {
            return "the database refused the login (SQLState " + state + ")";
        }
        if (state.equals("3D000")) {
            return "the database does not exist (SQLState " + state + ")";
        }
        return "the database answered SQLState " + (state.isEmpty() ? "unknown" : state);
    }

    private void off() {
        if (keeperThread != null) {
            keeperThread.shutdownNow();
        }
        if (fenceThread != null) {
            fenceThread.shutdownNow();
        }
        keeper = null;
        writer = null;
        close(keeperLink);
        close(secondLink);
    }

    private static void close(Link link) {
        if (link != null) {
            link.close();
        }
    }

    private static Thread daemon(Runnable r, String name) {
        Thread t = new Thread(r, name);
        t.setDaemon(true);
        return t;
    }

    private static long seconds(long ms) {
        return TimeUnit.MILLISECONDS.toSeconds(ms);
    }
}
