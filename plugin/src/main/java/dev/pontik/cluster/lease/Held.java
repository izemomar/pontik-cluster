// SPDX-License-Identifier: MPL-2.0
package dev.pontik.cluster.lease;

/** A lease this process holds: its epoch, and the nanoTime until which this node may read. */
public final class Held {

    public final long epoch;
    public final long validUntil;

    public Held(long epoch, long validUntil) {
        this.epoch = epoch;
        this.validUntil = validUntil;
    }
}
