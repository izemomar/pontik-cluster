// SPDX-License-Identifier: MPL-2.0
package dev.pontik.cluster.lease;

/**
 * The reconciler, which hears of each channel whose desired state may have changed.
 * Implementations return at once and never throw. The fence timer and the lease keeper call
 * queue while they demote leases, and a call that blocks or throws would hold up the other
 * deadlines.
 */
public interface Reconciler {

    void queue(String channelId);
}
