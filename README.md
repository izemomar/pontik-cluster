# Pontik Cluster

[![License: MPL-2.0](https://img.shields.io/badge/license-MPL--2.0-blue.svg)](LICENSE)

## Introduction

Pontik Cluster is a plugin for Eclipse Open Integration Engine™, the open-source
healthcare integration engine. It makes sure at most one node of your cluster starts a
poll for each channel you protect, with automatic failover. It can protect a channel
whose source connector polls - a File Reader, a Database Reader or a JavaScript
Reader - and when a channel's settings rule that out, it tells you why. When the node
polling a channel stops, another node takes the channel over.

Out of the box, Open Integration Engine™ - like Mirth® Connect, which it was forked
from - does not coordinate the nodes of a cluster. When you run it active-active, with
every node sharing one database and running the same channels at the same time, every
node polls those channels on its own, so two nodes can read the same file or row.
Channels that listen, such as an HTTP or TCP Listener, do not have this problem when
your load balancer sends each message to one node. Pontik Cluster lets you keep every
node active, instead of falling back to active-passive, where one node works while the
others wait, or splitting your channels across nodes by hand.

<picture>
  <source media="(max-width: 767px) and (prefers-reduced-motion: reduce) and (prefers-color-scheme:dark)" srcset="art/pontik-cluster-polling-narrow-dark.png 2x">
  <source media="(max-width: 767px) and (prefers-reduced-motion: reduce)" srcset="art/pontik-cluster-polling-narrow-light.png 2x">
  <source media="(max-width: 767px) and (prefers-color-scheme:dark)" srcset="art/pontik-cluster-polling-narrow-dark.svg">
  <source media="(max-width: 767px)" srcset="art/pontik-cluster-polling-narrow-light.svg">
  <source media="(prefers-reduced-motion: reduce) and (prefers-color-scheme:dark)" srcset="art/pontik-cluster-polling-dark.png 2x">
  <source media="(prefers-reduced-motion: reduce)" srcset="art/pontik-cluster-polling-light.png 2x">
  <source media="(prefers-color-scheme: dark)" srcset="art/pontik-cluster-polling-dark.svg">
  <source media="(prefers-color-scheme: light)" srcset="art/pontik-cluster-polling-light.svg">
  <img src="art/pontik-cluster-polling-light.png" alt="Two hand-drawn diagrams of the same three-node cluster. Without Pontik Cluster, every node polls the SFTP inbox and the EHR can get 1001.hl7 three times. With Pontik Cluster, a lease in the engine's PostgreSQL database names node-1 as the owner: only node-1 polls, while node-2 and node-3 wait on standby, Paused and ready to take over.">
</picture>

## How It Works

Your cluster stays active-active: every node keeps running and serving traffic. What
changes is which node polls a protected channel. For each one, Pontik Cluster keeps a
lease in your engine's database - a record that lets one node, the channel's owner,
poll the channel for a limited time - and the owner renews it as long as it keeps
running and reaches the database. Every other node is on standby for that channel. It
keeps the channel deployed with its source connector stopped, so the channel shows as
Paused there, and it takes the channel over once the lease runs out. Your nodes never
connect to each other. The database alone decides who owns each channel.

The database's clock decides when a lease runs out, so your nodes' clocks do not need
to agree on the time of day. The owner stops polling six seconds before its lease could
run out, timed on its own monotonic clock, which measures elapsed time and ignores any
change to the time of day.

When you stop a node cleanly or undeploy a channel on its owner, the owner hands the
channel over. It gives up the lease once its source connector has stopped, and a node
on standby takes the channel over. When a node fails or loses its database, the
channel fails over instead. The other nodes wait for the lease to run out, and then one
of them takes the channel over. Either way, the new owner polls at the channel's next
scheduled poll. There is no automatic failback: when a node comes back, the channels it
gave up stay with their new owners. After you restart every node at once, each
protected channel polls again about 20 seconds after it is deployed.

<picture>
  <source media="(max-width: 767px) and (prefers-reduced-motion: reduce) and (prefers-color-scheme:dark)" srcset="art/pontik-cluster-failover-narrow-dark.png 2x">
  <source media="(max-width: 767px) and (prefers-reduced-motion: reduce)" srcset="art/pontik-cluster-failover-narrow-light.png 2x">
  <source media="(max-width: 767px) and (prefers-color-scheme:dark)" srcset="art/pontik-cluster-failover-narrow-dark.svg">
  <source media="(max-width: 767px)" srcset="art/pontik-cluster-failover-narrow-light.svg">
  <source media="(prefers-reduced-motion: reduce) and (prefers-color-scheme:dark)" srcset="art/pontik-cluster-failover-dark.png 2x">
  <source media="(prefers-reduced-motion: reduce)" srcset="art/pontik-cluster-failover-light.png 2x">
  <source media="(prefers-color-scheme: dark)" srcset="art/pontik-cluster-failover-dark.svg">
  <source media="(prefers-color-scheme: light)" srcset="art/pontik-cluster-failover-light.svg">
  <img src="art/pontik-cluster-failover-light.png" alt="Two hand-drawn diagrams of node-1 failing. With one active node, files wait in the inbox until someone starts node-2 by hand. With Pontik Cluster, node-1's lease runs out on the database clock, node-2 takes the lease and picks up the waiting files, and node-1 comes back on standby without taking the channel back.">
</picture>

You opt in one channel at a time by choosing its mode:

| Mode | Behavior |
| --- | --- |
| `unmanaged` | The plugin does nothing. This is the default for every channel. |
| `single-owner` | At most one node starts a poll for the channel's source connector. |

Only a channel whose source connector polls can be protected. A channel that listens
keeps running on every node behind your load balancer, as it does today. If you try to
set its mode to `single-owner`, we refuse the change, and the channel stays `unmanaged`.
If you later change a `single-owner` channel's source to a listener and redeploy it,
each node runs the channel as it would without the plugin and reports it as
`unprotected`, with fault code PC-112.

Installing the plugin changes no channel's behavior until you set its mode, and
removing it from every node returns every channel to the engine's own behavior. The
plugin keeps its state in three tables of its own in the engine's database, `pcl_node`,
`pcl_lease` and `pcl_channel_config`, which stay there until you drop them. Pontik
Cluster only ever prevents a read. It never deletes, moves, acknowledges or reorders a
message, and it never writes to any of the engine's own tables.

You always know where a `single-owner` channel stands. Each node reports it as either
`protected` or `unprotected`, with the reason, and raises a fault code when you must
act.

## Requirements

Pontik Cluster runs on two or more Open Integration Engine™ 4.6.0 servers - we call each
one a node - that share one PostgreSQL engine database. It adds no server and no
network path between your nodes: each node opens two more connections to the database
it already uses.

Before you install it, please make sure that:

- Every node runs Open Integration Engine™ 4.6.0 exactly, and every node runs the
  plugin, at the same version. A node without the plugin polls every channel, whatever
  its mode.
- Each node keeps its own `appdata` and `conf` directories from one start to the next,
  so it keeps its own server ID and its keystore passwords, and the
  `server.id.ephemeral` setting stays off.
- The database's clock runs continuously and is kept on time by NTP, and so is the clock
  of its standby server, if you run one.
- Each node reaches the database in well under a second, as it does on the same network
  or in the same cloud region.
- Each node connects to PostgreSQL directly, not through PgBouncer or another connection
  pooler.
- Nothing but the plugin locks rows in its three tables.
- The engine's database user may create tables, or your database administrator runs the
  schema script from [Installation](#installation) first.
- Every channel you protect is deployed on every node that may take it over. A channel
  deployed on only one node has no failover, and no other node can warn you when that
  node fails.
- Every node reaches the same source - the same share, directory, database or endpoint -
  because the new owner reads from its own side.
- Each node can run every protected channel on its own, because after a restart one
  node may own them all.

## Installation

You install Pontik Cluster like any other Open Integration Engine™ extension, from its
zip, `pontik-cluster-0.1.0-alpha.zip`. You may build the zip yourself: open this
repository in its devcontainer, as [CONTRIBUTING.md](CONTRIBUTING.md) describes, and run
`./gradlew pluginZip`. The zip lands in `plugin/build/distributions/`.

Please repeat these steps on every node. First, open Extensions in the Administrator,
choose the zip under Install Extension from File System, and click Install. Next,
restart the engine on that node. Finally, check that the Extensions list on that node
shows Pontik Cluster.

At its first start, the plugin creates its three tables in the engine's database:
`pcl_node`, `pcl_lease` and `pcl_channel_config`. If the engine's database user may not
create tables, the node raises fault code PC-107 instead and runs every channel as it
does without the plugin. In that case, please ask your database administrator to run
this schema script once, in the schema your engine's own tables use, replacing
`engine_user` with the user your engine connects as, and then restart the node:

```sql
-- Keep timestamp without time zone: the plugin compares these columns
-- with the database clock in UTC and refuses any other type.

CREATE TABLE pcl_node (
    node_id varchar(255) PRIMARY KEY,
    node_name varchar(255) NULL,
    instance_id varchar(36) NOT NULL,
    protocol_version int NOT NULL,
    plugin_version varchar(32) NOT NULL,
    last_seen_at timestamp NOT NULL
);

CREATE TABLE pcl_lease (
    channel_id varchar(36) PRIMARY KEY,
    owner_node_id varchar(255) NULL,
    epoch bigint NOT NULL DEFAULT 0,
    lease_expires_at timestamp NOT NULL,
    acquired_at timestamp NULL,
    stopped boolean NOT NULL DEFAULT false
);

CREATE TABLE pcl_channel_config (
    channel_id varchar(36) PRIMARY KEY,
    mode varchar(16) NOT NULL,
    rereadable boolean NOT NULL DEFAULT false,
    updated_at timestamp NOT NULL,
    updated_by varchar(255) NULL
);

GRANT SELECT, INSERT, UPDATE ON pcl_node, pcl_lease, pcl_channel_config TO engine_user;
GRANT DELETE ON pcl_channel_config TO engine_user;
```

> [!NOTE]
> If a time column was created as `timestamp with time zone`, the node raises PC-107 and
> names the column. You may convert it in place, keeping its values in UTC, with
> `ALTER TABLE pcl_lease ALTER COLUMN lease_expires_at TYPE timestamp USING lease_expires_at AT TIME ZONE 'UTC';`,
> using the table and column the message names.

> [!WARNING]
> Please keep every node on Open Integration Engine™ 4.6.0 while you use this release.
> The engine loads an extension only on the exact engine version the extension names,
> and it skips one that does not match with a single line in its log. A node on another
> engine version then polls every protected channel, while the other nodes keep
> reporting it `protected`. After you upgrade the engine or the plugin, please check the
> Extensions list on every node.

## Uninstalling

If you remove Pontik Cluster, please hand every protected channel back first, so each
node starts polling again because you decided it, not because of the order of your
uninstalls.

First, set the mode of every protected channel to `unmanaged`, as
[Protecting a Channel](#protecting-a-channel) shows, and wait 20 seconds. Next, on each
node in turn, open Extensions in the Administrator, click Uninstall on Pontik Cluster,
and restart the engine. Finally, once every node is done, you may drop the plugin's
tables:

```sql
DROP TABLE pcl_lease, pcl_channel_config, pcl_node;
```

Your channels then run as they do without the plugin.

## Protecting a Channel

You protect a channel by setting its mode to `single-owner` through our REST call, and
hand it back by setting it to `unmanaged`. You may call it on any node; every node picks
the change up within about 10 seconds:

```
PUT /api/extensions/pontik-cluster/channels/{channelId}/mode?mode=single-owner
```

For example, with curl:

```shell
curl -X PUT -u admin -H 'X-Requested-With: OpenAPI' \
  'https://engine-1.example.org:8443/api/extensions/pontik-cluster/channels/CHANNEL_ID/mode?mode=single-owner'
```

curl asks for the user's password. The examples assume that the machine you run curl
on trusts your engine's certificate. A successful call answers `204 No Content`. On a
stock engine every user who may log in may also make this call; its permission, Set
cluster mode, matters only if you run an extension that restricts permissions.

We check the saved channel before we change anything, and we refuse a channel we cannot
protect with `422` and the fault code that says why, in plain text:

| Code | We Refuse the Channel When | What You May Do |
| --- | --- | --- |
| PC-106 | Poll Once on Start is on, or the Database Reader runs its post-processing once after all messages | Turn the setting off, or keep the channel `unmanaged` |
| PC-112 | Its source connector is not a File Reader, a Database Reader or a JavaScript Reader | Keep the channel `unmanaged` |
| PC-114 | Its read may change data - a JavaScript Reader, a Database Reader with Use JavaScript set to Yes, or a query that writes - and you have not declared it rereadable | Declare it rereadable if that is safe, or keep it `unmanaged` |
| PC-115 | A Channel Writer sends messages into it | Route those messages another way, or keep the channel `unmanaged` |

A channel is rereadable when a read we stop partway leaves its data in place to be read
again, such as a script that only reads files and never deletes them. You declare it in
the same call:

```
PUT /api/extensions/pontik-cluster/channels/{channelId}/mode?mode=single-owner&rereadable=true
```

The call also answers `400` for any other mode, `404` for a channel that does not exist,
and `503` while the plugin is off on that node. A change the database refuses twice
answers `500`, with the database's SQLState.

Once you protect a channel, your nodes settle on one owner within about 20 seconds.
Until then, a node that has not caught up may still poll it, and that node reports the
channel as `unprotected`.

## Checking Status

Each node reports what it sees, so please ask each node. Both calls answer from the
node's memory and never touch the database, so they answer even while the database is
down.

### Each Node's Channels

`GET /api/extensions/pontik-cluster/channels` lists every channel deployed on the node,
protected or not, and every channel with a mode:

```shell
curl -u admin -H 'X-Requested-With: OpenAPI' -H 'Accept: application/json' \
  'https://engine-1.example.org:8443/api/extensions/pontik-cluster/channels'
```

For example, a node answers like this for a channel it owns and for one you have not
protected:

```json
{
  "list": {
    "pontikChannel": [
      {
        "channelId": "6f1d3c2a-0b5e-4c7d-9a21-7c0d00004001",
        "name": "inbox-reader",
        "sourceType": "File Reader",
        "mode": "single-owner",
        "rereadable": false,
        "status": "protected",
        "reason": "this node holds the lease",
        "codes": null,
        "owner": "b48de3b8-658c-4f95-ad20-172a0abddc0a",
        "ownerName": "node-1",
        "epoch": 1,
        "heldSince": "2026-11-16T10:56:31.897863Z",
        "leaseExpiresAt": "2026-11-16T10:58:10.000261Z",
        "stopped": false,
        "asOf": "2026-11-16T10:57:40.002195Z",
        "readBackAgeMs": 7075,
        "thisNode": {
          "role": "owner",
          "sourceState": "STARTED",
          "stoppedByUs": false,
          "stoppedByOperator": false
        }
      },
      {
        "channelId": "6f1d3c2a-0b5e-4c7d-9a21-7c0d00004004",
        "name": "adt-feed",
        "sourceType": "JavaScript Reader",
        "mode": "unmanaged",
        "rereadable": false,
        "status": "unmanaged",
        "codes": null,
        "asOf": "2026-11-16T10:57:40.002195Z",
        "readBackAgeMs": 7075,
        "thisNode": {
          "role": "none",
          "sourceState": "STARTED",
          "stoppedByUs": false,
          "stoppedByOperator": false
        }
      }
    ]
  }
}
```

The engine shapes its JSON after its XML, as it does on every call: a list of one item
comes back as an object, an empty list as `null`, and a field without a value is left
out. For each channel:

- `status` is `protected`, `unprotected` or `unmanaged`, and `reason` says why.
- `codes` lists the fault codes firing for the channel.
- `owner` and `ownerName` name the node that holds the lease, and `epoch` is a number
  that goes up each time a node takes the lease.
- `heldSince`, `leaseExpiresAt` and `asOf` are the database's times, in UTC.
- `readBackAgeMs` is how long ago this node last read the lease tables. It stays at
  about 10 seconds or less while the node reaches the database, and grows while it
  cannot.
- `thisNode.role` is `owner`, `standby`, or `none` for an `unmanaged` channel.

While the plugin is off on a node, this call answers `503` with the node's own view
below as its body, so a monitor never reads an empty list as all clear. Please alert on
that answer too.

### This Node

`GET /api/extensions/pontik-cluster/local` describes the node itself: whether the plugin
runs there (`state` is `RUNNING`, `OFF` or `DECLINED`), its fixed timing, the other
nodes it counts as live, the leases it holds with how long it may still poll under each,
and every fault code firing on it:

```json
{
  "pontikNode": {
    "nodeId": "b48de3b8-658c-4f95-ad20-172a0abddc0a",
    "nodeName": "node-1",
    "instanceId": "7ad14a7f-190c-439f-884d-2359aba9004e",
    "state": "RUNNING",
    "gateAvailable": true,
    "timing": {
      "leaseMs": 30000,
      "heartbeatMs": 10000,
      "marginMs": 6000,
      "jitterMs": 2000,
      "tolerance": 2
    },
    "lastTickAgeMs": 7103,
    "lastSweepAgeMs": 6981,
    "engineBusy": false,
    "peers": {
      "pontikPeer": {
        "nodeId": "dfc9ac2c-e35b-45ab-ac46-727cba73d20a",
        "nodeName": "node-2",
        "protocolVersion": 1
      }
    },
    "leases": {
      "pontikLease": [
        {
          "channelId": "6f1d3c2a-0b5e-4c7d-9a21-7c0d00004001",
          "epoch": 1,
          "validForMs": 16896
        },
        {
          "channelId": "6f1d3c2a-0b5e-4c7d-9a21-7c0d00004220",
          "epoch": 1,
          "validForMs": 16896
        }
      ]
    },
    "faults": null
  }
}
```

### The Whole Cluster

For one view of every protected channel, you may run this read-only query against the
engine's database. It shows which node owns each channel, and `held` is false for a
lease nobody holds:

```sql
SELECT l.channel_id, n.node_name AS owner, l.epoch, l.stopped,
       l.owner_node_id IS NOT NULL
         AND l.lease_expires_at > clock_timestamp() AT TIME ZONE 'UTC' AS held
  FROM pcl_lease l
  JOIN pcl_channel_config c ON c.channel_id = l.channel_id
  LEFT JOIN pcl_node n ON n.node_id = l.owner_node_id
 ORDER BY l.channel_id;
```

> [!NOTE]
> On every node except its owner, a protected channel shows as Paused, with its
> source stopped. That is how we keep the other nodes from reading. If your monitoring
> alerts on channels that are not Started, please leave protected channels out of that
> check and use our `GET /channels` call instead. Each node reports whether the channel
> is `protected` or `unprotected` there, and which node owns it.

### Alerts and the Engine Log

Every fault code is written to the engine's log with the tag `[PONTIK-CLUSTER]` and sent
to the engine as an error event, so you may create an engine alert for any error whose
text matches `\[PONTIK-CLUSTER\]`. Each node loads its alerts when it starts, so please
restart each node after you create the alert. We also recommend alerting on PC-102, and
on a `readBackAgeMs` above 30000 on any node.

Pontik Cluster logs under `dev.pontik.cluster`, at INFO even though the engine logs only
errors by default: one line when it starts, and one line each time a channel changes
hands. If you would rather keep only its warnings and errors, you may add these lines to
the node's `conf/log4j2.properties` and restart the node:

```properties
logger.pontik.name = dev.pontik.cluster
logger.pontik.level = WARN
```

Its fault codes still reach your alerts, because each one is also an error event.

## Starting and Stopping Protected Channels

Pontik Cluster respects how you set each channel up. A channel set to start Paused or
Stopped starts that way on every node.

When you Pause, Stop or Halt a protected channel on its owner, it stops everywhere: the
owner keeps its lease, so no other node takes the channel over. The stop also outlasts
a failure. If the owner fails or loses its database, another node takes the channel
over and keeps it Paused until you resume it there. If you undeploy the channel or stop
that node's engine instead, the stop is forgotten, and the new owner starts the channel,
just as a single engine starts it again after a restart. A redeploy forgets your stops
too, as the engine always does.

After you halt a channel on a node, please restart that node's engine before you start
the channel there again. Open Integration Engine™ 4.6.0 keeps the halted poll's job
until the engine restarts, so a start of the channel on that node can fail, even after
a redeploy. If that node takes the channel over first, it raises PC-118 and gives the
channel up again.

Please start or resume a protected channel on its owner. If you do it on any other node,
we stop the channel there again straight away. [Checking Status](#checking-status) shows
which node owns it.

Please do not stop a protected channel on a node that does not own it. That node can
then never take the channel over, and its status says so. If that leaves the channel
without anyone to poll it, we warn you with PC-103 after 90 seconds.

A redeploy on the owner keeps the channel on that node. To move a channel to another
node, please follow [Moving a Channel](#moving-a-channel).

## Operating Procedures

### Protecting a New Channel

Please protect a new channel before you first deploy it, so that no node polls it
before the nodes agree on its owner. First, save the channel. Next, set its mode to
`single-owner` and wait 20 seconds. Finally, deploy it on every node that may take it
over.

### Moving a Channel

First, undeploy the channel on its owner. Next, wait until `GET /channels` on another
node names that node as the new owner. Finally, deploy the channel again on the node you
undeployed it from, so that node is on standby for it again. A redeploy within a few
seconds of the undeploy keeps the channel where it was, so please wait for the new owner
first.

### Restarting or Patching a Node

First, stop the node's engine cleanly. The other nodes take its channels over within
about 12 seconds after its engine has stopped. Next, patch or restart the node, keeping
it on Open Integration Engine™ 4.6.0. Finally, start its engine again. The other nodes
keep the channels they took over, and the restarted node is on standby for them. A
channel you had stopped on it is started by its new owner, as a single engine starts it
after a restart.

### Protecting a Channel That Already Runs on One Node

If you run active-passive today, with one node working and a standby you start by hand,
you may move to active-active one channel at a time. First, set the channel's mode to
`single-owner` and wait until `GET /channels` names its owner, the node that already
polls it. Next, redeploy the channel on each other node where it shows as Stopped. That
node then keeps it Paused, on standby, and can take it over.

### Stopping a Node That Has Stopped Working

A node can keep its leases while it no longer does its work: its poll may hang on a
share that stopped answering, or a channel may be stuck starting or stopping, which
also holds back the handover of channels you undeploy there. Please kill that node's
engine process. The other nodes take its channels over within about a minute.

## Fault Codes

A fault code fires once, when something needs you: one line in the engine's log, which
starts with `[PONTIK-CLUSTER]` and the code, and one engine error event. It clears with
one INFO line once it no longer applies. PC-103 and PC-105 are warnings; every other
code is an error. While PC-104 to PC-107, PC-112 to PC-116 or PC-119 fires, the
channel is reported as `unprotected`, and for PC-107, PC-113 and PC-119, every channel
on the node is.

| Code | What Happened | What You Should Do |
| --- | --- | --- |
| <a name="pc-101"></a>PC-101 | This node held the channel's lease, but the database then named another owner, or the node's release matched no row. It stopped the channel's source at once. | Check the channel's destinations for a message delivered twice around the time the message names. If it happens again, check the database clock and whether anything else writes to `pcl_lease`. |
| <a name="pc-102"></a>PC-102 | No lease renewal was confirmed for 24 seconds, so this node stopped every protected source it polled, and the other nodes take the channels over. | Check this node's connection to the database. |
| <a name="pc-103"></a>PC-103 | No node has owned the channel for 90 seconds. | If the channel is stopped on this node, start it on a node where it is deployed. Either way, check that every node reaches the database. |
| <a name="pc-104"></a>PC-104 | This node could not stop the channel's source although another node owns it, so both may poll. | Stop the channel by hand on this node. If it does not stop, Halt it, which can deliver the message in flight twice. |
| <a name="pc-105"></a>PC-105 | Something keeps starting the channel on this node although another node owns it. | Find the script or monitor that starts it, and start protected channels on the node that owns them. |
| <a name="pc-106"></a>PC-106 | We refused the channel: Poll Once on Start is on, or its post-processing runs once after all messages. | Turn the setting off, or keep the channel `unmanaged`. |
| <a name="pc-107"></a>PC-107 | The plugin is off on this node, because it could not create or use its tables, or could not start. Every channel on the node runs as it does without the plugin. | Do what the message says: run the schema script or its GRANT lines from [Installation](#installation), convert the column it names, check the node's connection to the database, or install the same plugin version on every node. Then restart the node. |
| <a name="pc-110"></a>PC-110 | This node has stopped starting lease renewals. It stops each source it polls before that channel's lease runs out, and the other nodes take the channels over. | Take a thread dump of this node, then restart it. |
| <a name="pc-112"></a>PC-112 | We refused the channel: its source connector is not a File Reader, a Database Reader or a JavaScript Reader. | Keep the channel `unmanaged`. |
| <a name="pc-113"></a>PC-113 | The engine's database is not PostgreSQL, so the plugin does nothing on this node. | Uninstall the plugin, or move the engine to PostgreSQL. |
| <a name="pc-114"></a>PC-114 | We refused the channel: its read may change data, and it is not declared rereadable. | Declare it rereadable if a read we stop partway leaves its data in place, or keep the channel `unmanaged`. |
| <a name="pc-115"></a>PC-115 | We refused the channel: a Channel Writer sends messages into it, and they would fail on every node but the owner. | Route those messages another way, or keep the channel `unmanaged`. |
| <a name="pc-116"></a>PC-116 | This node cannot reach the engine's running copy of the channel, or of any channel, so it cannot keep it Paused, and the channel runs as configured there. | Redeploy the channel, or restart the node when every channel is affected. If it happens again, please report it with the engine log. |
| <a name="pc-118"></a>PC-118 | This node could not start the channel after taking it over. It gave the lease up and stays out for 90 seconds while another node takes over. | Check this node's access to the channel's source: the share, the database or the script. If the channel was halted on this node since its engine last started, restart the engine. |
| <a name="pc-119"></a>PC-119 | Another process uses this node's server ID, so leases cannot tell the two apart, and every channel on the node is reported `unprotected`. | Give this node its own server ID: stop it, delete `appdata/server.id`, and start it. |

## Limitations

> [!WARNING]
> **A message can be read twice when a node loses its database.** A node that loses its
> connection to the database stops reading before its lease runs out. That leaves the
> message it is working on at least six seconds to finish before any other node can take
> over. A message that takes longer may be read again by the new owner. If some of your
> messages take more than a few seconds to process, please make sure the systems you
> deliver them to can recognize a message they have already received.

> [!WARNING]
> **Starting a protected channel on the wrong node.** If you start or resume a protected
> channel on a node that does not own it, we stop it again straight away. It can still
> read a few messages first, and more on a busy server. Please start protected channels
> on the node that owns them. [Checking Status](#checking-status) shows which node that
> is.

> [!WARNING]
> **A database failover to an asynchronous standby server.** If your
> PostgreSQL database fails over to an asynchronous standby server, that server may be
> missing the last lease changes. For a short time, usually a few seconds and at most
> about 24 seconds, two nodes may then read the same channel. The same failover can also
> lose messages the engine has already saved. If you need either guarantee to hold
> through a failover, please use synchronous replication.

**Channels that send messages into a protected channel.** A protected channel accepts
messages only on the node that owns it. On every other node, messages that another
channel sends into it fail, wait in a queue, or are lost, depending on how they are
sent. We refuse to protect a channel that a Channel Writer names. We cannot see a target
that is chosen while a channel runs, or one that a script sends to. Please do not
protect a channel that other channels send messages to.

**Readers that change data as they read.** Some readers change what they read, such as a
query that marks rows as taken or a script that deletes the files it fetched. If we stop
such a reader partway through, the data it has already taken may never be delivered. We
refuse to protect these readers unless you tell us they can safely be read twice. We
judge a query by its text, setting comments and quoted text aside, so a query that
changes rows through a database function looks to us like a plain read, and PostgreSQL's
`E'...'` and `$$...$$` strings can make us judge a query wrongly. Please keep the query
of a protected Database Reader to one plain `SELECT`.

**A node that freezes.** If a node freezes for about 20 seconds or more, another node
may take over its channels. This can happen during a very long garbage collection pause,
or while its virtual machine is suspended. When the frozen node resumes, it can read
briefly before it notices and stops. The same can happen if a node's clock runs much
slower than the database's, because the node then stops polling later than it should.
Please keep your engine's garbage collection pauses well under 20 seconds.

**Work outside the source.** We control only when a protected channel's source reads.
Everything else the channel does still runs on every node where it is deployed: its
deploy and undeploy scripts, your global scripts, and any timers your scripts start.
Values your scripts keep in memory, such as the global channel map, stay on the node
that wrote them and do not follow the channel to its new owner. If we stop a File Reader
partway through a batch, that batch never marks its last file as complete
(`pollComplete`).

**Messages waiting in a destination queue.** The engine keeps each destination's queue
with the node that queued the messages. When a node fails, the messages waiting in its
queues stay there until that node starts again with the same server ID, while the new
owner sends the channel's new messages, so a destination can receive them out of
order. No other node sends them. If a destination needs its messages in order, please
do not queue messages to it from a protected channel, and bring a failed node back with
its own `appdata`, so it can send what it holds.

**Exactly-once delivery.** We keep more than one node from reading a channel at the same
time. We do not promise that each message is delivered exactly once. The cases above can
still produce a duplicate, and so can a node that crashes partway through a message,
just as a single engine can when it restarts. Closing those windows would require the
polled system itself to turn away a node that has lost its lease, and you cannot ask
that of an SFTP server or a partner's database. If a duplicate would cause harm, you
should make your destinations idempotent, which means that receiving the same message
twice has the same effect as receiving it once.

**Handover takes time, and the timings are fixed.** When a node shuts down cleanly, the
other nodes take over its channels within about 12 seconds after its engine has stopped;
stopping the engine waits for each channel's current poll to finish. When you undeploy a
channel on its owner, another node takes it over within about 42 seconds, usually about
30. When a node fails, it takes about 55 seconds. When you restart every node at once,
each protected channel reads again about 20 seconds after it is deployed. After the
takeover, each channel reads at its next scheduled poll. You cannot change these
timings.

**Channels that poll at a time of day.** A channel that polls at a set time, or on a
cron schedule, reads at its next scheduled time after a takeover. If the takeover
happens in the minutes before that time, the next read is the following day. If a daily
poll must not be missed, please avoid maintenance on its owner just before it, or poll
on an interval instead.

**Long global deploy scripts.** When you redeploy channels, we keep each one on its node
while the engine works through the redeploy. If your global deploy script runs for more
than about 10 seconds, a Redeploy All can still move some channels to other nodes.
Nothing is read twice; the channels then run on their new owners.

**Channels stay where they are.** We do not move channels around to balance the load. A
node that rejoins takes over a channel only when another node gives it up, so over time
a few nodes may own most channels. [Moving a Channel](#moving-a-channel) explains how to
move one.

**Channels that listen.** Channels that listen depend on each message reaching one node.
A JMS Listener that subscribes to a topic can receive every message on every node, and
we refuse to protect it, because it does not poll. If you can, you should have it read
from a queue instead, since a queue hands each message to one node.

Pontik Cluster is provided as-is, without warranty of any kind, and no fitness for any
purpose, clinical or otherwise, is asserted. It is not a medical device, is not
certified as one, and does not replace the safety controls of the systems it runs
alongside. Please validate it in your own environment before it carries live traffic.

## Trademarks

Eclipse, Open Integration Engine and OIE are trademarks of Eclipse Foundation AISBL.
Mirth and Mirth Connect are trademarks of NXGN Management, LLC. We use these names
only to identify the engine this plugin runs on and the software that engine was
forked from. Other names belong to their respective owners. Pontik Cluster is not
affiliated with, endorsed by, or sponsored by any of them.

## Contributing

Thank you for considering contributing to Pontik Cluster! The contribution guide can
be found in [CONTRIBUTING.md](CONTRIBUTING.md).

## Security Vulnerabilities

Please review [our security policy](SECURITY.md) on how to report security
vulnerabilities.

## License

Pontik Cluster is open-sourced software licensed under the
[Mozilla Public License 2.0](LICENSE).
