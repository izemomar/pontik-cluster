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

    static final String RENEW = "UPDATE pcl_lease l"
        + " SET lease_expires_at = clock_timestamp() AT TIME ZONE 'UTC' + ? * interval '1 millisecond',"
        + " stopped = h.stopped"
        + " FROM unnest(?::varchar[], ?::bigint[], ?::boolean[]) AS h(channel_id, epoch, stopped)"
        + " WHERE l.channel_id = h.channel_id"
        + " AND l.epoch = h.epoch"
        + " AND l.owner_node_id = ?"
        + " AND l.lease_expires_at > clock_timestamp() AT TIME ZONE 'UTC'"
        + " RETURNING l.channel_id, l.epoch";

    static final String READ_BACK = "SELECT c.channel_id, c.mode, c.rereadable,"
        + " l.owner_node_id, l.epoch, l.lease_expires_at, l.stopped, l.acquired_at,"
        + " clock_timestamp() AT TIME ZONE 'UTC' AS db_now"
        + " FROM pcl_channel_config c"
        + " LEFT JOIN pcl_lease l ON l.channel_id = c.channel_id";

    static final String RELEASE = "UPDATE pcl_lease l"
        + " SET owner_node_id = NULL, acquired_at = NULL, epoch = l.epoch + 1, stopped = false"
        + " FROM unnest(?::varchar[], ?::bigint[]) AS r(channel_id, epoch)"
        + " WHERE l.channel_id = r.channel_id AND l.owner_node_id = ? AND l.epoch = r.epoch"
        + " RETURNING l.channel_id";

    static final String UPSERT_NODE = "WITH prev AS (SELECT instance_id FROM pcl_node WHERE node_id = ?),"
        + " up AS ("
        + "INSERT INTO pcl_node (node_id, node_name, instance_id, protocol_version, plugin_version, last_seen_at)"
        + " VALUES (?, ?, ?, ?, ?, clock_timestamp() AT TIME ZONE 'UTC')"
        + " ON CONFLICT (node_id) DO UPDATE"
        + " SET node_name = EXCLUDED.node_name, instance_id = EXCLUDED.instance_id,"
        + " protocol_version = EXCLUDED.protocol_version, plugin_version = EXCLUDED.plugin_version,"
        + " last_seen_at = EXCLUDED.last_seen_at)"
        + " SELECT (SELECT instance_id FROM prev) AS previous_instance";

    static final String PEERS = "SELECT node_id, node_name, protocol_version FROM pcl_node"
        + " WHERE node_id <> ?"
        + " AND last_seen_at > clock_timestamp() AT TIME ZONE 'UTC' - ? * interval '1 millisecond'";

    static final String ACQUIRE = "UPDATE pcl_lease"
        + " SET owner_node_id = ?, epoch = epoch + 1, acquired_at = clock_timestamp() AT TIME ZONE 'UTC',"
        + " lease_expires_at = clock_timestamp() AT TIME ZONE 'UTC' + ? * interval '1 millisecond'"
        + " WHERE channel_id IN ("
        + "SELECT channel_id FROM pcl_lease"
        + " WHERE channel_id = ANY (?::varchar[])"
        + " AND (owner_node_id IS NULL OR lease_expires_at <= clock_timestamp() AT TIME ZONE 'UTC')"
        + " ORDER BY array_position(?::varchar[], channel_id)"
        + " LIMIT ?"
        + " FOR UPDATE SKIP LOCKED)"
        + " RETURNING channel_id, epoch, stopped";

    static final String INSERT_LEASE = "INSERT INTO pcl_lease AS l"
        + " (channel_id, owner_node_id, epoch, lease_expires_at, acquired_at)"
        + " VALUES (?, NULL, 0, clock_timestamp() AT TIME ZONE 'UTC', NULL)"
        + " ON CONFLICT (channel_id) DO UPDATE SET stopped = false"
        + " WHERE l.owner_node_id IS NULL OR l.lease_expires_at <= clock_timestamp() AT TIME ZONE 'UTC'";

    static final String UPSERT_CHANNEL_CONFIG = "INSERT INTO pcl_channel_config"
        + " (channel_id, mode, rereadable, updated_at, updated_by)"
        + " VALUES (?, ?, ?, clock_timestamp() AT TIME ZONE 'UTC', ?)"
        + " ON CONFLICT (channel_id) DO UPDATE SET mode = EXCLUDED.mode, rereadable = EXCLUDED.rereadable,"
        + " updated_at = EXCLUDED.updated_at, updated_by = EXCLUDED.updated_by";

    static final String MARK_NODE_GONE = "UPDATE pcl_node"
        + " SET last_seen_at = clock_timestamp() AT TIME ZONE 'UTC' - ? * interval '1 millisecond'"
        + " WHERE node_id = ? AND instance_id = ?";

    static final String DELETE_CHANNEL_CONFIG = "DELETE FROM pcl_channel_config WHERE channel_id = ?";

    private Sql() {
    }
}
