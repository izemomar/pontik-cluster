// SPDX-License-Identifier: MPL-2.0
package dev.pontik.cluster.lease;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import org.junit.jupiter.api.Test;

class ConnectionsDatabaseTest {

    @Test
    void theHostlessUrlReachesTheEnginesDatabaseWithOurSettings() throws SQLException {
        try (Connection c = Connections.open(Pg.keeper())) {
            assertEquals("pontik-cluster", setting(c, "application_name"));
            assertEquals("2s", setting(c, "statement_timeout"));
            assertEquals("read committed", setting(c, "transaction_isolation"));
            assertEquals(true, c.getAutoCommit());
        }
    }

    @Test
    void theEnginesUrlCannotLoosenOurStatementTimeoutOrIsolation() throws SQLException {
        DbSettings db = Pg.settings();
        String separator = db.url.contains("?") ? "&" : "?";
        String options = "options=-c%20statement_timeout%3D60000%20-c%20default_transaction_isolation%3Dserializable";
        DbSettings loose = new DbSettings(db.type, db.url + separator + options, db.user, db.password);

        try (Connection c = Connections.open(Pg.keeper(loose))) {
            assertEquals("2s", setting(c, "statement_timeout"));
            assertEquals("read committed", setting(c, "transaction_isolation"));
        }
    }

    @Test
    void aDatabaseDefaultOfSerializableDoesNotReachUs() throws SQLException {
        Pg.execute("ALTER DATABASE test SET default_transaction_isolation = 'serializable'");
        try (Connection c = Connections.open(Connections.second(Pg.settings()))) {
            assertEquals("read committed", setting(c, "transaction_isolation"));
        } finally {
            Pg.execute("ALTER DATABASE test RESET default_transaction_isolation");
        }
    }

    @Test
    void aServerTimeoutKeepsTheConnectionAndALostOneReopens() throws SQLException {
        try (Link link = new Link(Pg.keeper())) {
            int pid = pid(link.get());

            SQLException timeout = assertThrows(SQLException.class, () -> query(link, "SELECT pg_sleep(3)"));
            assertEquals("57014", timeout.getSQLState());
            link.failed(timeout);
            assertEquals(pid, pid(link.get()));

            Pg.execute("SELECT pg_terminate_backend(" + pid + ")");
            SQLException gone = assertThrows(SQLException.class, () -> query(link, "SELECT 1"));
            link.failed(gone);
            assertNotEquals(pid, pid(link.get()));
        }
    }

    @Test
    void aClosedLinkStaysClosedInsteadOfOpeningAnotherConnection() throws SQLException {
        Link link = new Link(Pg.keeper());
        Connection open = link.get();

        link.close();

        assertTrue(open.isClosed());
        SQLException closed = assertThrows(SQLException.class, link::get);
        assertEquals("08003", closed.getSQLState());
        assertEquals("The connection is closed.", closed.getMessage());
        link.close();
    }

    private static void query(Link link, String sql) throws SQLException {
        try (Statement s = link.get().createStatement()) {
            s.execute(sql);
        }
    }

    private static int pid(Connection c) throws SQLException {
        try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery("SELECT pg_backend_pid()")) {
            rs.next();
            return rs.getInt(1);
        }
    }

    private static String setting(Connection c, String name) throws SQLException {
        try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery("SHOW " + name)) {
            rs.next();
            return rs.getString(1);
        }
    }
}
