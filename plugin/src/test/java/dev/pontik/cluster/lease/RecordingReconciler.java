// SPDX-License-Identifier: MPL-2.0
package dev.pontik.cluster.lease;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * A reconciler that remembers what was queued, and may check the state at the moment of each
 * queue.
 */
final class RecordingReconciler implements Reconciler {

    final List<String> queued = new ArrayList<>();
    Consumer<String> onQueue = id -> { };

    @Override
    public synchronized void queue(String channelId) {
        onQueue.accept(channelId);
        queued.add(channelId);
    }
}
