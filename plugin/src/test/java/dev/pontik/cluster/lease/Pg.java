// SPDX-License-Identifier: MPL-2.0
package dev.pontik.cluster.lease;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * The PostgreSQL server the database tests share: one image, pinned by digest, started once per
 * test run and reached through the engine's own driver.
 */
final class Pg {

    static final String IMAGE =
        "postgres:17-alpine@sha256:b0f9560a2de083e2cc7382e75f808c7381a32852a7ec49117deedb300e552b24";

    private static PostgreSQLContainer shared;

    private Pg() {
    }

    static synchronized PostgreSQLContainer container() {
        if (shared == null) {
            PostgreSQLContainer started = start();
            shared = started;
        }

        return shared;
    }

    /** A server of its own, for a test that stops it. */
    static PostgreSQLContainer start() {
        PostgreSQLContainer container =
            new PostgreSQLContainer(DockerImageName.parse(IMAGE).asCompatibleSubstituteFor("postgres"));
        container.start();
        return container;
    }

    static Connection admin() throws SQLException {
        PostgreSQLContainer c = container();
        return DriverManager.getConnection(c.getJdbcUrl(), c.getUsername(), c.getPassword());
    }
}
