// SPDX-License-Identifier: MPL-2.0
package dev.pontik.cluster.lease;

/**
 * The lease timing every node runs with. Every value is in milliseconds. The constructor rejects a
 * broken value at once instead of letting it fail in silence. Among its checks, it rejects a fence
 * margin that does not lie between zero and the lease, a timing in which one failed renewal would
 * already fence a lease, and a statement budget that would let the last renewal before the fence get
 * its reply after the fence fires. The tests pass shorter values through it.
 */
public final class Timing {

    /** The timing every node runs with. */
    public static final Timing STANDARD = new Timing(30_000, 10_000, 6_000, 2_000, 1_000, 3_000, 2_000);

    public final long leaseMs;
    public final long heartbeatMs;
    public final long marginMs;
    public final long jitterMs;
    public final long loginTimeoutMs;
    public final long socketTimeoutMs;
    public final long statementTimeoutMs;

    public Timing(long leaseMs, long heartbeatMs, long marginMs, long jitterMs,
            long loginTimeoutMs, long socketTimeoutMs, long statementTimeoutMs) {
        require(heartbeatMs > 0, "The heartbeat must be longer than zero.");
        require(marginMs > 0 && marginMs < leaseMs, "The fence margin must lie between zero and the lease.");
        require(jitterMs >= 0 && jitterMs < heartbeatMs, "The acquisition jitter must be shorter than the heartbeat.");
        require(wholeSeconds(loginTimeoutMs) && wholeSeconds(socketTimeoutMs),
            "The driver takes its login and socket timeouts in whole seconds.");
        require(statementTimeoutMs > 0 && statementTimeoutMs < socketTimeoutMs,
            "The statement timeout must be shorter than the socket timeout, so the server cancels first.");

        this.leaseMs = leaseMs;
        this.heartbeatMs = heartbeatMs;
        this.marginMs = marginMs;
        this.jitterMs = jitterMs;
        this.loginTimeoutMs = loginTimeoutMs;
        this.socketTimeoutMs = socketTimeoutMs;
        this.statementTimeoutMs = statementTimeoutMs;

        require(tolerance() >= 2, "Only " + tolerance() + " renewals fit before the fence fires,"
            + " so one failed renewal would fence the lease; at least 2 must fit.");
        require(tolerance() * heartbeatMs + statementBudgetMs() <= validityMs(),
            "The last renewal before the fence must get its reply before the fence fires.");
    }

    /**
     * Returns how many renewals this node sends between a confirmed renewal and its fence. A lease
     * survives one fewer failed renewals in a row than this.
     */
    public int tolerance() {
        return (int) ((validityMs() + heartbeatMs - 1) / heartbeatMs) - 1;
    }

    /** Returns how long after a renewal is sent this node may keep reading. */
    public long validityMs() {
        return leaseMs - marginMs;
    }

    /** Returns the longest one statement can take on the keeper connection, login included. */
    public long statementBudgetMs() {
        return loginTimeoutMs + socketTimeoutMs;
    }

    /** Returns how recently a lease must have been confirmed for a deploy to keep the source started. */
    public long freshMs() {
        return validityMs() - heartbeatMs;
    }

    /** Returns how young a peer's node row must be for the peer to count as live. */
    public long peerWindowMs() {
        return 3 * heartbeatMs;
    }

    /** Returns the no-owner alarm, which is also the start-failure cool-down and the start-fight window. */
    public long alarmMs() {
        return 3 * leaseMs;
    }

    /** Returns the age past which a sweep counts as busy, so releases wait for a fresh one. */
    public long sweepMaxAgeMs() {
        return 2 * heartbeatMs;
    }

    /** Returns how long the keeper may go without starting a tick before the watchdog alerts. */
    public long watchdogMs() {
        return 2 * heartbeatMs;
    }

    @Override
    public String toString() {
        int survives = tolerance() - 1;
        return "lease " + leaseMs + " ms, a renewal every " + heartbeatMs + " ms, polling stops " + marginMs
            + " ms before a lease could run out, acquisition jitter 0 to " + jitterMs + " ms, a lease survives "
            + survives + (survives == 1 ? " failed renewal" : " failed renewals") + " in a row";
    }

    private static boolean wholeSeconds(long ms) {
        return ms >= 1000 && ms % 1000 == 0;
    }

    private static void require(boolean rule, String message) {
        if (!rule) {
            throw new IllegalArgumentException(message);
        }
    }
}
