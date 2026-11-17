// SPDX-License-Identifier: MPL-2.0
package dev.pontik.cluster.lease;

import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * The keeper's statements, one method each. The keeper talks to the database only through this
 * interface, so the tests can give it a slow, hanging or failing database.
 */
public interface Store {

    String SINGLE_OWNER = "single-owner";
    String UNMANAGED = "unmanaged";

    /** Renews the held leases by channel and epoch; a returned row confirms its lease. */
    List<Renewed> renew(String[] channelIds, Long[] epochs, Boolean[] stopped, long leaseMs) throws SQLException;

    /** Reads the config and lease rows together, from one snapshot. */
    ReadBack readBack() throws SQLException;

    /** Releases the given leases, fenced by owner and epoch; returns the channels it released. */
    List<String> release(String[] channelIds, Long[] epochs) throws SQLException;

    /** Writes this node's row; returns the instance the row named before, or null. */
    String upsertNode() throws SQLException;

    /** Returns the other nodes seen within the window. */
    List<Peer> peers(long windowMs) throws SQLException;

    /** Takes at most {@code share} of the candidates, in their order, skipping rows another node is taking. */
    List<Acquired> acquire(String[] candidates, int share, long leaseMs) throws SQLException;

    /** Creates a missing lease row, or clears the stop flag of a row nobody holds. */
    void insertLease(String channelId) throws SQLException;

    /** Ages this process's node row out of the peer window, so the survivors stop counting it. */
    void markGone(long windowMs) throws SQLException;

    final class Renewed {
        public final String channelId;
        public final long epoch;

        public Renewed(String channelId, long epoch) {
            this.channelId = channelId;
            this.epoch = epoch;
        }
    }

    final class Acquired {
        public final String channelId;
        public final long epoch;
        public final boolean stopped;

        public Acquired(String channelId, long epoch, boolean stopped) {
            this.channelId = channelId;
            this.epoch = epoch;
            this.stopped = stopped;
        }
    }

    final class Peer {
        public final String nodeId;
        public final String nodeName;
        public final int protocol;

        public Peer(String nodeId, String nodeName, int protocol) {
            this.nodeId = nodeId;
            this.nodeName = nodeName;
            this.protocol = protocol;
        }
    }

    /** One config row, with its lease columns, which are null when the lease row is missing. */
    final class Row {
        public final String channelId;
        public final String mode;
        public final boolean rereadable;
        public final String owner;
        public final Long epoch;
        public final LocalDateTime expiresAt;
        public final Boolean stopped;
        public final LocalDateTime acquiredAt;

        public Row(String channelId, String mode, boolean rereadable, String owner, Long epoch,
                LocalDateTime expiresAt, Boolean stopped, LocalDateTime acquiredAt) {
            this.channelId = channelId;
            this.mode = mode;
            this.rereadable = rereadable;
            this.owner = owner;
            this.epoch = epoch;
            this.expiresAt = expiresAt;
            this.stopped = stopped;
            this.acquiredAt = acquiredAt;
        }

        public boolean singleOwner() {
            return SINGLE_OWNER.equals(mode);
        }

        public boolean hasLease() {
            return expiresAt != null;
        }

        /** Returns whether some process holds a valid lease on this channel at the database time given. */
        public boolean held(LocalDateTime dbNow) {
            return owner != null && expiresAt != null && dbNow != null && expiresAt.isAfter(dbNow);
        }
    }

    final class ReadBack {
        public static final ReadBack EMPTY = new ReadBack(Collections.<String, Row>emptyMap(), null);

        /** The config rows by channel ID, in the order the database returned them. */
        public final Map<String, Row> rows;

        /** The database's UTC time when it read the last row; null when there were no rows. */
        public final LocalDateTime dbNow;

        public ReadBack(Map<String, Row> rows, LocalDateTime dbNow) {
            this.rows = Collections.unmodifiableMap(rows);
            this.dbNow = dbNow;
        }
    }
}
