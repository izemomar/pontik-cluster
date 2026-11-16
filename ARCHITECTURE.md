# Architecture

Pontik Cluster coordinates an active-active cluster that shares one PostgreSQL database: every
node serves traffic, and any node can take over any protected channel. Each protected channel
has at most one owner at a time, granted by a time-bound lease [1] in that database, which is
the only lock service. There is no membership protocol, no quorum and no traffic between nodes.
The safety property is that at most one node starts a poll for a channel at any time, and during
a handover the channel has no owner for a while. The lease is a lock for efficiency, not for
correctness [2]. The systems we poll never check the epoch, so the epoch is a generation number
that fences our own writes to the lease table, not an end-to-end fencing token. An owner fences
itself by timing: it stops starting polls at `T0 + lease - margin` on its monotonic clock, where
`T0` is when its last confirmed renewal was sent, and a poll already running finishes. Only the
database clock decides expiry, so clock skew between nodes plays no part. Like any lease, ours
needs each node's clock to drift within a known bound [1].

This file is a map for reading the code. The README says how to run the plugin; this file says
where each part lives and which rules the code keeps.

## Bird's Eye View

The plugin is one Open Integration Engine™ extension, built for Java 8, that runs inside every
node's engine. It has four parts, kept apart on purpose:

- **The lease keeper** is one scheduled thread that talks only to the database. Every 10 seconds
  it renews the leases this node holds, reads back every lease and every channel's mode, releases
  what this node gave up, records that the node is alive, and reads which other nodes are. Then,
  after a random delay of up to 2 seconds, it tries to take over channels nobody holds. It never
  calls the engine.
- **The fence timer** runs on a single thread of its own. It keeps one deadline per lease, and at
  a lease's deadline it demotes the lease in memory and asks for its source to be stopped. It also
  watches the keeper, so a keeper that stops ticking is fenced like any other.
- **The reconciler** runs one lane per channel, on a pool that grows with the channels. A lane
  carries only the channel's ID: when it runs, it works out what the channel's source should be
  doing from the current lease and the engine's current state, and starts or stops the source only
  when the two differ. The deploy hook and a sweep every 10 seconds feed the lanes.
- **The convergence listener** hears the engine's deployed-state events and queues a lane when a
  protected channel's source starts, so a start by hand on a node that does not own the channel is
  undone.

The REST calls answer from memory. Only the keeper, the startup and shutdown steps, and a mode
change or a channel's deletion touch the database.

## Code Map

All shipped code lives in `plugin/src/main/java/dev/pontik/cluster/`.

### `lease/`

The keeper (`Keeper`), the fence timer (`Fence`), the lease state in memory (`Ownership`,
`LocalState`), the timing and its guards (`Timing`), the connections (`Connections`), the
bootstrap that creates the tables (`Bootstrap`), and every SQL statement the plugin sends
(`Sql`). `Coordinator` starts and stops them.

**Architecture invariant:** nothing in `lease/` depends on the engine. The build compiles
`lease/` and `fault/` a second time with only the PostgreSQL driver on the classpath
(`compileEngineFree`), so an engine import fails the build.

### `fault/`

The fault codes (`Code`), their texts (`Messages`), and the registry that fires each code once and
clears it once (`Faults`). It depends on the engine no more than `lease/` does.

### `engine/`

Everything that talks to the engine. `ClusterPlugin` is the one entry class for both of the
engine's plugin roles. `Engine` names every engine call the reconciler and the sweep make, and
`OieEngine` makes them, so the tests run the reconciler on a fake engine. `ChannelReconciler`
holds the deploy hook, the lanes and the sweep. `Gate` keeps a deploying channel's copy Paused.
`Eligibility`, `Select` and `Targets` decide which channels may be protected, and `Desired` and
`Status` are the pure decisions of a lane and of the status calls.

**Architecture invariant:** the only engine calls that change anything start or stop a channel's
source connector, plus the gate's write of a deploying copy's initial state. `Engine` has no call
that touches a message, a file or a row.

### `api/`

The REST interface and its two views, `pontikChannel` and `pontikNode`, which ship in
`pontik-cluster-shared.jar`, and the servlet that answers the calls.

## Invariants

Each rule below is stated as something the code never does, and each names the test or build
check that fails if it does.

- **No write to an engine table.** Each `INSERT`, `UPDATE`, `DELETE` and table statement in `Sql`
  names a `pcl_` table: `SqlTest.everyWriteNamesAPclTable`. No other source file holds such a
  statement or a `SELECT` in capitals, the way `Sql` writes them:
  `SourceTreeTest.everyStatementLivesInSql`.
- **No node's wall clock in a lease.** No statement calls a time function other than
  `clock_timestamp() AT TIME ZONE 'UTC'`:
  `SqlTest.everyStatementReadsTheTimeOnlyFromTheDatabaseClockInUtc`. Every time the store stamps
  falls between two readings of the database clock taken around the call:
  `JdbcStoreTest.everyStampFallsBetweenSendAndReplyOnTheDatabaseClock`. The build bans the
  wall-clock calls that `config/forbidden-apis/wall-clock.txt` lists in `lease/`, among them
  `System.currentTimeMillis` and `Instant.now()`.
- **No deadline anchored at the reply.** A confirmed renewal moves the deadline to its send time
  plus the lease minus the margin:
  `KeeperTest.aConfirmedRenewalMovesTheDeadlineToItsSendTimePlusLeaseMinusMargin`. Each lease
  keeps its own deadline: `FenceTest.eachLeaseIsFencedAtItsOwnDeadline`.
- **No confirmation by row count.** A renewal confirms a lease only by a row it returns: a batch
  that returns one of two rows keeps that lease and demotes the other:
  `KeeperTest.aBatchedRenewalThatReturnsOneOfTwoLeasesDemotesTheOther`.
- **No jitter on the renewal.** The renewal tick runs at a fixed rate, and only the attempt to
  take over channels waits the jitter delay:
  `KeeperTest.theRenewalTickRunsAtAFixedRateAndOnlyTheAcquisitionIsJittered`.
- **No tick that runs late.** A statement that hangs until it fails costs one tick, and one
  failed tick does not fence:
  `KeeperTest.aHangingStatementCostsOneTickAndOneFailedTickDoesNotFence`. `Timing` refuses a
  login and socket timeout above 4 seconds and a tolerance below two failed renewals:
  `TimingTest.loginAndSocketAboveFourSecondsAreRejected` and
  `TimingTest.aToleranceBelowTwoIsRejected`.
- **No shared thread between the fence and the keeper:**
  `CoordinatorTest.theFenceDemotesALeaseOnItsOwnThreadWhileTheKeepersThreadIsStuck`.
- **No unfenced release.** A release names this node and the epoch it holds, and clears
  `acquired_at` but not `lease_expires_at`:
  `JdbcStoreTest.theReleaseIsFencedRowByRowInsideOneBatch` and
  `JdbcStoreTest.theReleaseClearsOwnerAcquiredAtAndStoppedAndKeepsTheExpiry`.
- **No release while our source may still run,** except a deliberate hand-back to the engine's
  own behavior. An undeployed channel keeps its lease while the engine is busy or the latest
  sweep is stale, and releases it after two idle sweeps:
  `KeeperTest.noReleaseWhileTheEngineIsBusyOrTheLatestSweepIsStale` and
  `KeeperTest.twoIdleSweepsThenATickReleaseTheLease`. A hand-back releases at once:
  `KeeperTest.aLeaseIsReleasedOnlyOnceItsSourceIsStoppedExceptTheHandBacks`.
- **No epoch handed out twice,** even for a channel deleted and protected again:
  `JdbcStoreTest.aChannelDeletedAndProtectedAgainNeverHandsOutAnEpochTwice`.
- **No start of a connector the operator stopped.** A hand-back restores only what we stopped:
  `ChannelReconcilerTest.aHandBackStartsWhatWeStoppedOnceAndForgetsTheMarkWhateverTheOutcome` and
  `ChannelReconcilerTest.aHandBackNeverStartsAChannelStoppedMeanwhile`.
- **No half measures on a node that declines to take part.** A node that meets a peer running a
  protocol it does not know releases its leases at the next tick:
  `KeeperTest.aPeerWithAnUnknownProtocolMakesThisNodeDeclineAndReleaseAtTheNextTick`. From then
  on its deploy hook pauses no channel:
  `ChannelReconcilerTest.theHookDoesNothingOnANodeThatDeclined`, and it reports a protected
  channel `unprotected`, with PC-107:
  `ReportTest.aDeclinedNodeReportsEveryProtectedChannelUnprotectedWithPc107`.
- **No repeated fault line.** A code fires once per transition and clears once:
  `FaultsTest.aFaultFiresOncePerTransitionAndClearsOnceAtInfo`.
- **No Java 9 or later in shipped code.** The build compiles with `--release 8`.

## The Safety Argument

Three rules carry it.

1. **Expiry is decided on the database clock, in UTC.** Every statement reads the time as
   `clock_timestamp() AT TIME ZONE 'UTC'`, and each runs alone, in autocommit, except the
   bootstrap at startup, which creates the tables in one transaction and reads no time.
   PostgreSQL's `CURRENT_TIMESTAMP` returns the start of the transaction, which would let two
   owners overlap, so no statement uses it.
2. **The self-fence is anchored at the send, and kept per lease.** A node may keep polling under a
   lease until `T0 + lease - margin` on `System.nanoTime`, where `T0` is when the confirming
   renewal was sent. With a 30-second lease and a 6-second margin, the owner stops starting polls
   at least 6 seconds before any other node can take the lease.
3. **The renewal is never jittered, and no tick may run late.** The guard
   `ceil((lease - margin) / heartbeat) - 1` counts how many renewals in a row may fail before the
   owner fences; with a 10-second heartbeat it is 2, and `Timing` refuses anything below 2. A
   jittered or late renewal would quietly lower that count, so only the attempt to take over
   channels is jittered, and the login and socket timeouts fit inside
   `lease - margin - tolerance * heartbeat`.

> [!WARNING]
> The epoch is not an end-to-end fencing token. The systems we poll never see it, so a node that
> resumes after a long pause can still finish a poll it started. Exactly-once delivery still needs
> idempotent destinations.

## References

[1] C. Gray and D. Cheriton, "Leases: An Efficient Fault-Tolerant Mechanism for Distributed File
Cache Consistency", SOSP 1989.

[2] M. Kleppmann, "How to do distributed locking", 2016.
