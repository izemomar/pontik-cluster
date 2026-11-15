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
gave up stay with their new owners.

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

## Limitations

> [!WARNING]
> Pontik Cluster reduces duplicate polling, but it cannot rule it out entirely. A poll
> that is already in flight when a node loses its lease can finish after the new owner
> has started. Two nodes can also poll at the same time if a node freezes for several
> seconds at the wrong moment - during a long garbage-collection pause, for example -
> or if its clock runs much slower than the database's.

Closing that window would require the polled system itself to turn away a node that has
lost its lease, and you cannot ask that of an SFTP server or a partner's database. If
you need each message processed exactly once, you should make sure your destinations
are idempotent, which means that receiving the same message twice has the same effect
as receiving it once.

Channels that listen depend on each message reaching one node. A JMS Listener that
subscribes to a topic can receive every message on every node, and we refuse to protect
it, because it does not poll. If you can, you should have it read from a queue instead,
since a queue hands each message to one node.

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
