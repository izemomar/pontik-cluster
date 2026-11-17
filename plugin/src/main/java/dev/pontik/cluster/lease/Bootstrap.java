// SPDX-License-Identifier: MPL-2.0
package dev.pontik.cluster.lease;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/**
 * The startup step that creates the lease tables a fresh database lacks, then checks that this node
 * may use them. It is the only statement group that runs as one transaction, under an advisory lock,
 * so nodes that start together create each table once.
 */
final class Bootstrap {

    static final long LOCK_KEY = 0x70636C5F6C6F636BL;

    private static final String[] CREATES = {Sql.CREATE_NODE, Sql.CREATE_LEASE, Sql.CREATE_CHANNEL_CONFIG};

    private Bootstrap() {
    }

    static void run(Connection c) throws SQLException {
        boolean autoCommit = c.getAutoCommit();
        c.setAutoCommit(false);

        try {
            try (PreparedStatement lock = c.prepareStatement(Sql.LOCK)) {
                lock.setQueryTimeout(Connections.SECOND_QUERY_TIMEOUT_SECONDS);
                lock.setLong(1, LOCK_KEY);
                lock.execute();
            }
            createMissing(c);
            checkPrivileges(c);
            checkColumns(c);
            c.commit();
        } catch (SQLException | RuntimeException e) {
            try {
                c.rollback();
            } catch (SQLException ignored) {
                // The original error is the one worth reporting.
            }
            throw e;
        } finally {
            c.setAutoCommit(autoCommit);
        }
    }

    // CREATE TABLE IF NOT EXISTS checks the schema privilege before it looks for the table, so we
    // run it only for a table that is missing. A user without CREATE can then use tables a DBA made.
    private static void createMissing(Connection c) throws SQLException {
        boolean[] missing = new boolean[Sql.TABLES.length];

        try (Statement s = statement(c); ResultSet rs = s.executeQuery(Sql.MISSING_TABLES)) {
            rs.next();
            for (int i = 0; i < missing.length; i++) {
                missing[i] = rs.getBoolean(i + 1);
            }
        }

        for (int i = 0; i < missing.length; i++) {
            if (!missing[i]) {
                continue;
            }
            try (Statement s = statement(c)) {
                s.execute(CREATES[i]);
            } catch (SQLException e) {
                if ("42501".equals(e.getSQLState())) {
                    throw BootstrapException.cannotCreate(Sql.TABLES[i]);
                }
                throw e;
            }
        }
    }

    private static void checkPrivileges(Connection c) throws SQLException {
        List<String> lacking = new ArrayList<>();

        try (Statement s = statement(c); ResultSet rs = s.executeQuery(Sql.MISSING_PRIVILEGES)) {
            while (rs.next()) {
                lacking.add(rs.getString(2) + " on " + rs.getString(1));
            }
        }

        if (!lacking.isEmpty()) {
            throw BootstrapException.missingPrivileges(String.join(", ", lacking));
        }
    }

    // A timestamptz column would convert our UTC values in each session's time zone, which the
    // driver sets from each node's JVM, and put every node's zone into the lease predicate.
    private static void checkColumns(Connection c) throws SQLException {
        try (Statement s = statement(c); ResultSet rs = s.executeQuery(Sql.WRONG_COLUMNS)) {
            if (rs.next()) {
                throw BootstrapException.wrongColumn(rs.getString(1) + "." + rs.getString(2), rs.getString(3));
            }
        }
    }

    private static Statement statement(Connection c) throws SQLException {
        Statement s = c.createStatement();
        s.setQueryTimeout(Connections.SECOND_QUERY_TIMEOUT_SECONDS);
        return s;
    }
}
