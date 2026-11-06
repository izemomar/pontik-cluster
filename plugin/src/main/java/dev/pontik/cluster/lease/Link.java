// SPDX-License-Identifier: MPL-2.0
package dev.pontik.cluster.lease;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Properties;

/**
 * One dedicated connection, opened at first use. It is dropped only when the driver says it is
 * gone, and opened again at the next use, so a slow statement that the server cancels costs no
 * new login. Once the link is closed it stays closed, so a late caller cannot open a connection
 * that nobody will close.
 */
public final class Link implements AutoCloseable {

    private final Properties properties;
    private Connection connection;
    private boolean closed;

    public Link(Properties properties) {
        this.properties = properties;
    }

    public synchronized Connection get() throws SQLException {
        if (closed) {
            throw new SQLException("The connection is closed.", "08003");
        }
        if (connection == null || connection.isClosed()) {
            connection = Connections.open(properties);
        }
        return connection;
    }

    /**
     * Drops the connection when the error says it is gone, and keeps it otherwise. JdbcStore and
     * ConfigWriter report each error from this link here.
     */
    public synchronized void failed(SQLException e) {
        if (Connections.isConnectionError(e)) {
            drop();
        }
    }

    @Override
    public synchronized void close() {
        closed = true;
        drop();
    }

    private void drop() {
        if (connection != null) {
            try {
                connection.close();
            } catch (SQLException ignored) {
                // The connection is gone either way.
            }
            connection = null;
        }
    }
}
