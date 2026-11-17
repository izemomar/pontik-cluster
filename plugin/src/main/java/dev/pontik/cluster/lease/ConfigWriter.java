// SPDX-License-Identifier: MPL-2.0
package dev.pontik.cluster.lease;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;

/**
 * The writes outside the keeper's tick, on the second connection: setting a channel's mode and
 * forgetting a deleted channel. Both are idempotent, so a write that fails with a connection error
 * reopens the connection and runs once more.
 */
public final class ConfigWriter {

    private interface Write {
        void run(Connection c) throws SQLException;
    }

    private final Link link;

    public ConfigWriter(Link link) {
        this.link = link;
    }

    /**
     * Sets the mode. For {@code single-owner} the lease row is written first, so it exists before
     * any node reads the new mode; on a row nobody holds, that write also forgets an earlier stop.
     */
    public void setMode(String channelId, String mode, boolean rereadable, String user) throws SQLException {
        retryOnce(c -> {
            if (Store.SINGLE_OWNER.equals(mode)) {
                update(c, Sql.INSERT_LEASE, channelId);
            }
            try (PreparedStatement s = prepare(c, Sql.UPSERT_CHANNEL_CONFIG)) {
                s.setString(1, channelId);
                s.setString(2, mode);
                s.setBoolean(3, rereadable);
                s.setString(4, user);
                s.executeUpdate();
            }
        });
    }

    /**
     * Deletes the config row and keeps the lease row, so a channel created again under the same ID
     * continues its epoch and no epoch is ever handed out twice. A node that holds the lease reads
     * the channel as unmanaged and releases it as usual.
     */
    public void remove(String channelId) throws SQLException {
        retryOnce(c -> update(c, Sql.DELETE_CHANNEL_CONFIG, channelId));
    }

    private void retryOnce(Write write) throws SQLException {
        synchronized (link) {
            try {
                write.run(link.get());
            } catch (SQLException e) {
                link.failed(e);
                if (!Connections.isConnectionError(e)) {
                    throw e;
                }
                try {
                    write.run(link.get());
                } catch (SQLException again) {
                    link.failed(again);
                    throw again;
                }
            }
        }
    }

    private static void update(Connection c, String sql, String channelId) throws SQLException {
        try (PreparedStatement s = prepare(c, sql)) {
            s.setString(1, channelId);
            s.executeUpdate();
        }
    }

    private static PreparedStatement prepare(Connection c, String sql) throws SQLException {
        PreparedStatement s = c.prepareStatement(sql);
        s.setQueryTimeout(Connections.SECOND_QUERY_TIMEOUT_SECONDS);
        return s;
    }
}
