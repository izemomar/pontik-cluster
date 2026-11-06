// SPDX-License-Identifier: MPL-2.0
package dev.pontik.cluster.lease;

/**
 * Every statement the plugin sends. Each one reads the time only as
 * {@code clock_timestamp() AT TIME ZONE 'UTC'}, the database's own clock in UTC, written out in
 * full every time, so no node's clock and no transaction start time takes part in a lease. Each
 * runs as one autocommit statement, except the bootstrap, which is one transaction.
 */
final class Sql {

    static final String[] TABLES = {"pcl_node", "pcl_lease", "pcl_channel_config"};

    static final String CREATE_NODE = "CREATE TABLE IF NOT EXISTS pcl_node ("
        + " node_id varchar(255) PRIMARY KEY,"
        + " node_name varchar(255) NULL,"
        + " instance_id varchar(36) NOT NULL,"
        + " protocol_version int NOT NULL,"
        + " plugin_version varchar(32) NOT NULL,"
        + " last_seen_at timestamp NOT NULL)";

    static final String CREATE_LEASE = "CREATE TABLE IF NOT EXISTS pcl_lease ("
        + " channel_id varchar(36) PRIMARY KEY,"
        + " owner_node_id varchar(255) NULL,"
        + " epoch bigint NOT NULL DEFAULT 0,"
        + " lease_expires_at timestamp NOT NULL,"
        + " acquired_at timestamp NULL,"
        + " stopped boolean NOT NULL DEFAULT false)";

    static final String CREATE_CHANNEL_CONFIG = "CREATE TABLE IF NOT EXISTS pcl_channel_config ("
        + " channel_id varchar(36) PRIMARY KEY,"
        + " mode varchar(16) NOT NULL,"
        + " rereadable boolean NOT NULL DEFAULT false,"
        + " updated_at timestamp NOT NULL,"
        + " updated_by varchar(255) NULL)";

    static final String LOCK = "SELECT pg_advisory_xact_lock(?)";

    static final String MISSING_TABLES = "SELECT to_regclass('pcl_node') IS NULL,"
        + " to_regclass('pcl_lease') IS NULL, to_regclass('pcl_channel_config') IS NULL";

    static final String MISSING_PRIVILEGES = "SELECT t, p"
        + " FROM unnest(ARRAY['pcl_node','pcl_lease','pcl_channel_config']) t"
        + " CROSS JOIN unnest(ARRAY['SELECT','INSERT','UPDATE','DELETE']) p"
        + " WHERE NOT has_table_privilege(t, p)";

    static final String WRONG_COLUMNS = "SELECT x.t, x.c, format_type(a.atttypid, a.atttypmod)"
        + " FROM (VALUES ('pcl_node','last_seen_at'), ('pcl_lease','lease_expires_at'),"
        + " ('pcl_lease','acquired_at'), ('pcl_channel_config','updated_at')) AS x(t, c)"
        + " LEFT JOIN pg_attribute a"
        + " ON a.attrelid = to_regclass(x.t) AND a.attname = x.c AND NOT a.attisdropped"
        + " WHERE a.atttypid IS DISTINCT FROM 'timestamp'::regtype";

    private Sql() {
    }
}
