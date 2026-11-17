// SPDX-License-Identifier: MPL-2.0
package dev.pontik.cluster.lease;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.SQLException;
import java.util.Properties;
import org.junit.jupiter.api.Test;

class ConnectionsTest {

    @Test
    void ourTimeoutsAndOurNameBeatTheEnginesUrl() throws SQLException {
        DbSettings db = new DbSettings("postgres",
            "jdbc:postgresql://db1:5433/engine?socketTimeout=60&options=-c%20search_path=x&ApplicationName=engine",
            "engine", "secret");

        Properties p = Connections.keeper(db, Timing.STANDARD);

        assertEquals("1", p.getProperty("loginTimeout"));
        assertEquals("1", p.getProperty("connectTimeout"));
        assertEquals("3", p.getProperty("socketTimeout"));
        assertEquals("-c search_path=x -c default_transaction_isolation=read\\ committed -c statement_timeout=2000",
            p.getProperty("options"));
        assertEquals("pontik-cluster", p.getProperty("ApplicationName"));
        assertEquals("db1", p.getProperty("PGHOST"));
        assertEquals("5433", p.getProperty("PGPORT"));
        assertEquals("engine", p.getProperty("PGDBNAME"));
        assertEquals("engine", p.getProperty("user"));
        assertEquals("secret", p.getProperty("password"));
    }

    @Test
    void aMultiHostUrlKeepsEveryHost() throws SQLException {
        DbSettings db = new DbSettings("postgres",
            "jdbc:postgresql://db1:5432,db2:5433/engine?targetServerType=primary", "engine", "secret");

        Properties p = Connections.keeper(db, Timing.STANDARD);

        assertEquals("db1,db2", p.getProperty("PGHOST"));
        assertEquals("5432,5433", p.getProperty("PGPORT"));
        assertEquals("primary", p.getProperty("targetServerType"));
    }

    @Test
    void sslAndSchemaParametersOfTheEnginesUrlSurviveAndANullPasswordIsLeftOut() throws SQLException {
        DbSettings db = new DbSettings("postgres",
            "jdbc:postgresql://db1/engine?sslmode=verify-full&sslrootcert=/conf/root.crt&currentSchema=mirth",
            "engine", null);

        Properties p = Connections.keeper(db, Timing.STANDARD);

        assertEquals("verify-full", p.getProperty("sslmode"));
        assertEquals("/conf/root.crt", p.getProperty("sslrootcert"));
        assertEquals("mirth", p.getProperty("currentSchema"));
        assertNull(p.getProperty("password"));
    }

    @Test
    void theSecondConnectionHasItsOwnTimeoutsAndNoServerTimeout() throws SQLException {
        Properties p = Connections.second(new DbSettings("postgres", "jdbc:postgresql://db1/engine", "u", "p"));

        assertEquals("5", p.getProperty("connectTimeout"));
        assertEquals("15", p.getProperty("socketTimeout"));
        assertEquals("-c default_transaction_isolation=read\\ committed", p.getProperty("options"));
        assertEquals("pontik-cluster", p.getProperty("ApplicationName"));
    }

    @Test
    void aUrlTheDriverCannotReadIsRefused() {
        assertThrows(SQLException.class,
            () -> Connections.keeper(new DbSettings("postgres", "jdbc:mysql://db1/engine", "u", "p"), Timing.STANDARD));
    }

    @Test
    void onlyConnectionErrorsCountAsGone() {
        assertTrue(Connections.isConnectionError(new SQLException("socket", "08006")));
        assertTrue(Connections.isConnectionError(new SQLException("refused", "08001")));
        assertTrue(Connections.isConnectionError(new SQLException("terminated by an administrator", "57P01")));
        assertFalse(Connections.isConnectionError(new SQLException("statement timeout", "57014")));
        assertFalse(Connections.isConnectionError(new SQLException("no state")));
    }

    @Test
    void theDatabaseSettingsPrintOnlyTheDatabaseType() {
        DbSettings db = new DbSettings("postgres", "jdbc:postgresql://db1/engine", "engine", "secret");

        assertEquals("database postgres", db.toString());
    }
}
