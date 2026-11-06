// SPDX-License-Identifier: MPL-2.0
package dev.pontik.cluster.lease;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The statements of {@link Store} on one {@link Link}. Every timestamp is read as a
 * {@link LocalDateTime}, the value as stored: reading it as a {@code java.sql.Timestamp} would
 * shift it into this JVM's time zone.
 */
final class JdbcStore implements Store {

    private interface Work<T> {
        T run(Connection c) throws SQLException;
    }

    private final Link link;
    private final Node node;
    private final int queryTimeoutSeconds;

    /**
     * Creates the store on one link. A query timeout of 0 leaves timing to the server, as on the
     * keeper connection.
     */
    JdbcStore(Link link, Node node, int queryTimeoutSeconds) {
        this.link = link;
        this.node = node;
        this.queryTimeoutSeconds = queryTimeoutSeconds;
    }

    @Override
    public List<Renewed> renew(String[] channelIds, Long[] epochs, Boolean[] stopped, long leaseMs)
            throws SQLException {
        return run(c -> {
            try (PreparedStatement s = prepare(c, Sql.RENEW)) {
                s.setLong(1, leaseMs);
                s.setArray(2, c.createArrayOf("varchar", channelIds));
                s.setArray(3, c.createArrayOf("bigint", epochs));
                s.setArray(4, c.createArrayOf("boolean", stopped));
                s.setString(5, node.id);
                List<Renewed> renewed = new ArrayList<>();
                try (ResultSet rs = s.executeQuery()) {
                    while (rs.next()) {
                        renewed.add(new Renewed(rs.getString(1), rs.getLong(2)));
                    }
                }
                return renewed;
            }
        });
    }

    @Override
    public ReadBack readBack() throws SQLException {
        return run(c -> {
            try (PreparedStatement s = prepare(c, Sql.READ_BACK); ResultSet rs = s.executeQuery()) {
                Map<String, Row> rows = new LinkedHashMap<>();
                LocalDateTime dbNow = null;
                while (rs.next()) {
                    long epoch = rs.getLong(5);
                    Long nullableEpoch = rs.wasNull() ? null : epoch;
                    boolean stopped = rs.getBoolean(7);
                    Boolean nullableStopped = rs.wasNull() ? null : stopped;
                    Row row = new Row(rs.getString(1), rs.getString(2), rs.getBoolean(3), rs.getString(4),
                        nullableEpoch, rs.getObject(6, LocalDateTime.class), nullableStopped,
                        rs.getObject(8, LocalDateTime.class));
                    rows.put(row.channelId, row);
                    dbNow = rs.getObject(9, LocalDateTime.class);
                }
                return new ReadBack(rows, dbNow);
            }
        });
    }

    @Override
    public List<String> release(String[] channelIds, Long[] epochs) throws SQLException {
        return run(c -> {
            try (PreparedStatement s = prepare(c, Sql.RELEASE)) {
                s.setArray(1, c.createArrayOf("varchar", channelIds));
                s.setArray(2, c.createArrayOf("bigint", epochs));
                s.setString(3, node.id);
                List<String> released = new ArrayList<>();
                try (ResultSet rs = s.executeQuery()) {
                    while (rs.next()) {
                        released.add(rs.getString(1));
                    }
                }
                return released;
            }
        });
    }

    @Override
    public String upsertNode() throws SQLException {
        return run(c -> {
            try (PreparedStatement s = prepare(c, Sql.UPSERT_NODE)) {
                s.setString(1, node.id);
                s.setString(2, node.id);
                s.setString(3, node.name);
                s.setString(4, node.instance);
                s.setInt(5, Node.PROTOCOL);
                s.setString(6, node.pluginVersion);
                try (ResultSet rs = s.executeQuery()) {
                    rs.next();
                    return rs.getString(1);
                }
            }
        });
    }

    @Override
    public List<Peer> peers(long windowMs) throws SQLException {
        return run(c -> {
            try (PreparedStatement s = prepare(c, Sql.PEERS)) {
                s.setString(1, node.id);
                s.setLong(2, windowMs);
                List<Peer> peers = new ArrayList<>();
                try (ResultSet rs = s.executeQuery()) {
                    while (rs.next()) {
                        peers.add(new Peer(rs.getString(1), rs.getString(2), rs.getInt(3)));
                    }
                }
                return peers;
            }
        });
    }

    @Override
    public List<Acquired> acquire(String[] candidates, int share, long leaseMs) throws SQLException {
        return run(c -> {
            try (PreparedStatement s = prepare(c, Sql.ACQUIRE)) {
                s.setString(1, node.id);
                s.setLong(2, leaseMs);
                s.setArray(3, c.createArrayOf("varchar", candidates));
                s.setArray(4, c.createArrayOf("varchar", candidates));
                s.setInt(5, share);
                List<Acquired> acquired = new ArrayList<>();
                try (ResultSet rs = s.executeQuery()) {
                    while (rs.next()) {
                        acquired.add(new Acquired(rs.getString(1), rs.getLong(2), rs.getBoolean(3)));
                    }
                }
                return acquired;
            }
        });
    }

    @Override
    public void insertLease(String channelId) throws SQLException {
        run(c -> {
            try (PreparedStatement s = prepare(c, Sql.INSERT_LEASE)) {
                s.setString(1, channelId);
                return s.executeUpdate();
            }
        });
    }

    @Override
    public void markGone(long windowMs) throws SQLException {
        run(c -> {
            try (PreparedStatement s = prepare(c, Sql.MARK_NODE_GONE)) {
                s.setLong(1, windowMs);
                s.setString(2, node.id);
                s.setString(3, node.instance);
                return s.executeUpdate();
            }
        });
    }

    private PreparedStatement prepare(Connection c, String sql) throws SQLException {
        PreparedStatement s = c.prepareStatement(sql);
        if (queryTimeoutSeconds > 0) {
            s.setQueryTimeout(queryTimeoutSeconds);
        }
        return s;
    }

    private <T> T run(Work<T> work) throws SQLException {
        try {
            return work.run(link.get());
        } catch (SQLException e) {
            link.failed(e);
            throw e;
        }
    }
}
