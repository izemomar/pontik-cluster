// SPDX-License-Identifier: MPL-2.0
package dev.pontik.cluster.lease;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class TimingTest {

    @Test
    void theStandardValuesPassAndDeriveTheRest() {
        Timing t = Timing.STANDARD;

        assertEquals(2, t.tolerance());
        assertEquals(24_000, t.validityMs());
        assertEquals(4_000, t.statementBudgetMs());
        assertEquals(14_000, t.freshMs());
        assertEquals(30_000, t.peerWindowMs());
        assertEquals(90_000, t.alarmMs());
        assertEquals(20_000, t.sweepMaxAgeMs());
        assertEquals(20_000, t.watchdogMs());
    }

    @Test
    void aToleranceBelowTwoIsRejected() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
            () -> new Timing(30_000, 12_000, 6_000, 2_000, 1_000, 3_000, 2_000));
        assertTrue(e.getMessage().contains("at least 2"), e.getMessage());
    }

    @Test
    void loginAndSocketAboveFourSecondsAreRejected() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
            () -> new Timing(30_000, 10_000, 6_000, 2_000, 2_000, 3_000, 2_000));
        assertTrue(e.getMessage().contains("The last renewal before the fence must get its reply"), e.getMessage());
    }

    @Test
    void everyOtherBrokenRuleIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> new Timing(30_000, 0, 6_000, 0, 1_000, 3_000, 2_000));
        assertThrows(IllegalArgumentException.class,
            () -> new Timing(30_000, 10_000, 30_000, 2_000, 1_000, 3_000, 2_000));
        assertThrows(IllegalArgumentException.class,
            () -> new Timing(30_000, 10_000, 6_000, 10_000, 1_000, 3_000, 2_000));
        assertThrows(IllegalArgumentException.class,
            () -> new Timing(30_000, 10_000, 6_000, 2_000, 1_500, 2_000, 1_000));
        assertThrows(IllegalArgumentException.class,
            () -> new Timing(30_000, 10_000, 6_000, 2_000, 1_000, 3_000, 3_000));
    }

    @Test
    void theBudgetAndJitterEdgesAreAccepted() {
        // The last renewal before the fence gets its reply exactly as the fence fires.
        assertEquals(2, new Timing(30_000, 10_000, 6_000, 2_000, 1_000, 3_000, 2_000).tolerance());
        assertEquals(0, new Timing(30_000, 10_000, 6_000, 0, 1_000, 3_000, 2_000).jitterMs);
    }

    @Test
    void aBrokenHeartbeatMarginOrStatementTimeoutIsRejectedForItsOwnReason() {
        assertRejected("The heartbeat must be longer than zero.", 30_000, 0, 6_000, 2_000);
        assertRejected("The fence margin must lie between zero and the lease.", 30_000, 10_000, 0, 2_000);
        assertRejected("The fence margin must lie between zero and the lease.", 30_000, 10_000, 30_000, 2_000);
        assertRejected("The statement timeout must be shorter than the socket timeout, so the server cancels first.",
            30_000, 10_000, 6_000, 0);
    }

    private static void assertRejected(String reason, long leaseMs, long heartbeatMs, long marginMs,
            long statementTimeoutMs) {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
            () -> new Timing(leaseMs, heartbeatMs, marginMs, 0, 1_000, 3_000, statementTimeoutMs));
        assertEquals(reason, e.getMessage());
    }
}
