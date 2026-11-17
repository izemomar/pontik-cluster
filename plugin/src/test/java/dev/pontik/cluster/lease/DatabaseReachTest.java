// SPDX-License-Identifier: MPL-2.0
package dev.pontik.cluster.lease;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import org.junit.jupiter.api.Test;

class DatabaseReachTest {

    @Test
    void theTestsReachTheirDatabaseThroughTheEnginesDriver() throws Exception {
        try (Connection c = Pg.admin(); Statement s = c.createStatement();
                ResultSet rs = s.executeQuery("SELECT version()")) {
            assertTrue(rs.next());
            assertTrue(rs.getString(1).startsWith("PostgreSQL 17.11"), rs.getString(1));
            assertEquals("42.7.8", c.getMetaData().getDriverVersion());
        }
    }
}
