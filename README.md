# Pontik Cluster

[![License: MPL-2.0](https://img.shields.io/badge/license-MPL--2.0-blue.svg)](LICENSE)

## Introduction

Pontik Cluster is a plugin for Eclipse Open Integration Engine™ that makes sure only
one node of your cluster starts a poll for a given channel at a time. When that node
stops, another node picks the channel up automatically.

Out of the box, Open Integration Engine - like Mirth® Connect, which it was forked
from - does not coordinate the nodes of a cluster. If you run it on more than one node,
every node polls every enabled channel, so the same file, SFTP directory, or database
row is picked up once per node. Pontik Cluster lets you run all of your nodes at once,
without keeping a single active node or splitting your channels across nodes by hand.

## How It Works

Pontik Cluster keeps a lease for each protected channel in the database your nodes
already share. The node holding the lease polls, while the other nodes stand by and
take over once the lease expires. Lease expiry is always decided on the database
clock, so clock drift between your nodes can never produce two owners.

You opt in one channel at a time by choosing its mode:

| Mode | Behavior |
| --- | --- |
| `unmanaged` | The plugin does nothing. This is the default for every channel. |
| `single-owner` | At most one node starts a poll for the channel's source connector. |

Installing the plugin changes nothing until you set a channel's mode, and removing it
leaves your engine exactly as it was. Pontik Cluster only ever prevents a read. It
never deletes, moves, acknowledges, or reorders a message, and it never writes to any
of the engine's own tables.

You always know where a `single-owner` channel stands. It is reported as either
`protected` or `unprotected`, and if anything degrades, the channel is reported as
`unprotected` along with a fault code that tells you why.

## Requirements

Pontik Cluster requires Eclipse Open Integration Engine and PostgreSQL. You can find
the supported versions of each in the release notes.

## Limitations

> [!WARNING]
> Pontik Cluster reduces duplicate polling, but it cannot rule it out entirely. A poll
> that is already in flight when a node loses its lease can finish after the new owner
> has started.

Closing that window would require the polled resource itself to honor fencing tokens,
and you cannot ask that of an SFTP server or an HL7 endpoint. If you need exactly-once
processing, you should make sure your destinations are idempotent.

You should also set each channel's lease duration to be longer than its longest single
poll.

Pontik Cluster is provided as-is, without warranty of any kind, and no fitness for any
purpose, clinical or otherwise, is asserted. It is not a medical device, is not
certified as one, and does not replace the safety controls of the systems it runs
alongside. Please validate it in your own environment before it carries live traffic.

## Contributing

Thank you for considering contributing to Pontik Cluster! The contribution guide can
be found in [CONTRIBUTING.md](CONTRIBUTING.md).

## Security Vulnerabilities

Please review [our security policy](SECURITY.md) on how to report security
vulnerabilities.

## License

Pontik Cluster is open-sourced software licensed under the
[Mozilla Public License 2.0](LICENSE).

## Trademarks

Eclipse, Open Integration Engine and OIE are trademarks of Eclipse Foundation AISBL.
Mirth and Mirth Connect are trademarks of NextGen Healthcare. BridgeLink and any other
names belong to their respective owners. They are used here only to identify the
software this plugin works with. Pontik Cluster is not affiliated with, endorsed by,
or sponsored by any of them.
