// SPDX-License-Identifier: MPL-2.0
package dev.pontik.cluster.engine;

import com.mirth.connect.donkey.model.channel.DeployedState;
import com.mirth.connect.model.Channel;
import com.mirth.connect.model.DashboardStatus;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.function.Predicate;

/**
 * An engine for the reconciler's tests. A start or stop moves the channel the way the engine does,
 * unless the test says otherwise, and every call is recorded.
 */
final class FakeEngine implements Engine {

    /** What a start or stop does to the channel. */
    interface Outcome {
        void apply(FakeEngine engine, String channelId) throws Exception;
    }

    final Map<String, DeployedState[]> states = new LinkedHashMap<>();
    final Map<String, Channel> models = new LinkedHashMap<>();
    // The model each channel was last deployed with, which a later save does not change.
    final Map<String, Channel> running = new LinkedHashMap<>();
    final Set<String> disabled = new HashSet<>();
    final List<String> calls = new ArrayList<>();
    Outcome onStart = (e, id) -> e.set(id, DeployedState.STARTED, DeployedState.STARTED);
    Outcome onStop = (e, id) -> e.set(id, DeployedState.PAUSED, DeployedState.STOPPED);
    boolean statusFails;
    boolean savedFails;
    Runnable onEnabled = () -> { };
    Runnable onStatus = () -> { };
    Runnable onDeployed = () -> { };
    int metadataReads;
    // A test sets a gate to make the call block, as a real engine does while a poll runs. The entered latch opens
    // when the call has arrived.
    volatile CountDownLatch startGate;
    volatile CountDownLatch stopGate;
    final CountDownLatch startEntered = new CountDownLatch(1);
    final CountDownLatch stopEntered = new CountDownLatch(1);

    /** Deploys the channel in the given channel and source states. */
    FakeEngine deploy(Channel model, DeployedState channel, DeployedState source) {
        models.put(model.getId(), model);
        running.put(model.getId(), model);
        set(model.getId(), channel, source);
        return this;
    }

    void set(String channelId, DeployedState channel, DeployedState source) {
        states.put(channelId, new DeployedState[] { channel, source });
    }

    void undeploy(String channelId) {
        states.remove(channelId);
    }

    long count(String call) {
        return calls.stream().filter(call::equals).count();
    }

    @Override
    public synchronized List<DashboardStatus> statuses() {
        List<DashboardStatus> all = new ArrayList<>();
        for (String id : states.keySet()) {
            all.add(statusOf(id));
        }
        return all;
    }

    @Override
    public synchronized DashboardStatus status(String channelId) {
        onStatus.run();
        return statusOf(channelId);
    }

    private DashboardStatus statusOf(String channelId) {
        if (statusFails) {
            throw new IllegalStateException("status");
        }
        DeployedState[] s = states.get(channelId);
        if (s == null) {
            return null;
        }
        DashboardStatus channel = new DashboardStatus();
        channel.setChannelId(channelId);
        channel.setName(models.containsKey(channelId) ? models.get(channelId).getName() : channelId);
        channel.setState(s[0]);
        DashboardStatus source = new DashboardStatus();
        source.setMetaDataId(0);
        source.setState(s[1]);
        channel.getChildStatuses().add(source);
        return channel;
    }

    @Override
    public void startSource(String channelId) throws Exception {
        synchronized (this) {
            calls.add("start " + channelId);
        }
        await(startGate, startEntered);
        synchronized (this) {
            onStart.apply(this, channelId);
        }
    }

    @Override
    public void stopSource(String channelId) throws Exception {
        synchronized (this) {
            calls.add("stop " + channelId);
        }
        await(stopGate, stopEntered);
        synchronized (this) {
            onStop.apply(this, channelId);
        }
    }

    // The wait holds no lock, so the sweep can read the channel while the call blocks.
    private static void await(CountDownLatch gate, CountDownLatch entered) throws InterruptedException {
        if (gate != null) {
            entered.countDown();
            gate.await();
        }
    }

    @Override
    public synchronized Channel deployed(String channelId) {
        onDeployed.run();
        if (!states.containsKey(channelId)) {
            return null;
        }
        return running.containsKey(channelId) ? running.get(channelId) : models.get(channelId);
    }

    @Override
    public synchronized List<Channel> saved() {
        if (savedFails) {
            throw new IllegalStateException("saved");
        }
        return new ArrayList<>(models.values());
    }

    @Override
    public synchronized Predicate<String> enabled() {
        metadataReads++;
        onEnabled.run();
        return id -> !disabled.contains(id);
    }
}
