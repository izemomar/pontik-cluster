// SPDX-License-Identifier: MPL-2.0
package dev.pontik.cluster.lease;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Properties;
import org.postgresql.Driver;
import org.postgresql.util.PSQLState;

/**
 * The plugin's two dedicated connections, built from the engine's database settings.
 *
 * We let the driver parse the engine's URL, set our values over the result, and connect with a URL
 * that carries no host and no parameters. The driver ranks URL parameters above passed properties,
 * so a timeout in the engine's URL would otherwise win over ours.
 */
public final class Connections {

    public static final String APPLICATION_NAME = "pontik-cluster";

    /** The query timeout of every statement on the second connection, in seconds. */
    static final int SECOND_QUERY_TIMEOUT_SECONDS = 5;

    private static final String HOSTLESS_URL = "jdbc:postgresql:///";

    /** Sent at startup, so a session starts in Read Committed without a round trip of its own. */
    private static final String READ_COMMITTED_OPTION = "-c default_transaction_isolation=read\\ committed";

    private Connections() {
    }

    /**
     * Returns the keeper connection's properties. Every attempt fits the tick's time budget, and the
     * server cancels a slow statement.
     */
    public static Properties keeper(DbSettings db, Timing timing) throws SQLException {
        Properties p = parse(db);
        p.setProperty("loginTimeout", seconds(timing.loginTimeoutMs));
        p.setProperty("connectTimeout", seconds(timing.loginTimeoutMs));
        p.setProperty("socketTimeout", seconds(timing.socketTimeoutMs));
        String options = merge(p.getProperty("options"), READ_COMMITTED_OPTION);
        p.setProperty("options", merge(options, "-c statement_timeout=" + timing.statementTimeoutMs));
        return p;
    }

    /**
     * Returns the second connection's properties, for startup and for writes outside the tick. None
     * of its statements renews a lease.
     */
    public static Properties second(DbSettings db) throws SQLException {
        Properties p = parse(db);
        p.setProperty("connectTimeout", "5");
        p.setProperty("socketTimeout", "15");
        p.setProperty("options", merge(p.getProperty("options"), READ_COMMITTED_OPTION));
        return p;
    }

    public static Connection open(Properties properties) throws SQLException {
        Connection c = new Driver().connect(HOSTLESS_URL, properties);

        if (c == null) {
            throw new SQLException("The driver did not accept the connection properties.", "08001");
        }

        try {
            c.setAutoCommit(true);
            return c;
        } catch (SQLException e) {
            c.close();
            throw e;
        }
    }

    /**
     * Returns whether the connection is gone, so it is closed and reopened at its next use. Besides the
     * driver's own connection errors, the server ends a session with 57P01 when an administrator
     * terminates it and with 57P02 or 57P03 when it shuts down, and the driver then closes the
     * connection. A canceled statement, 57014, keeps a live connection.
     */
    public static boolean isConnectionError(SQLException e) {
        String state = e.getSQLState();
        return PSQLState.isConnectionError(state) || state != null && state.startsWith("57P");
    }

    private static Properties parse(DbSettings db) throws SQLException {
        Properties p = db.url == null ? null : Driver.parseURL(db.url, null);

        if (p == null) {
            throw new SQLException("The engine's database URL is not a PostgreSQL URL.", "08001");
        }

        if (db.user != null) {
            p.setProperty("user", db.user);
        }
        if (db.password != null) {
            p.setProperty("password", db.password);
        }
        p.setProperty("ApplicationName", APPLICATION_NAME);
        return p;
    }

    static String merge(String existing, String ours) {
        return existing == null || existing.trim().isEmpty() ? ours : existing + " " + ours;
    }

    private static String seconds(long ms) {
        return String.valueOf(ms / 1000);
    }
}
