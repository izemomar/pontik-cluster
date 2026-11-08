// SPDX-License-Identifier: MPL-2.0
package dev.pontik.cluster.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

class StatusTest {

    @Test
    void aChannelWithoutTheModeIsUnmanaged() {
        Status s = Status.of(false, "a fault", false, true, null, false, null, false, null);

        assertEquals(Status.UNMANAGED, s.state);
        assertNull(s.reason);
    }

    @Test
    void aFiringCodeMakesTheChannelUnprotectedWithItsMessage() {
        Status s = Status.of(true, "Refused: ...", true, true, null, false, null, false, null);

        assertEquals(Status.UNPROTECTED, s.state);
        assertEquals("Refused: ...", s.reason);
    }

    @Test
    void aSourceRunningWithoutALeaseIsUnprotectedWithACalmReason() {
        assertEquals(Status.TAKING_OVER, Status.of(true, null, false, true, null, false, null, false, null).reason);
        assertEquals(Status.UNPROTECTED, Status.of(true, null, false, true, null, false, null, false, null).state);
        assertEquals("A start on node-a was undone: node-b owns this channel. Start or stop it on node-b.",
            Status.of(true, null, false, true,
                "A start on node-a was undone: node-b owns this channel. Start or stop it on node-b.", false, null,
                false, "node-b").reason);
    }

    @Test
    void anOperatorsStopIsProtectedAndNamesTheNode() {
        Status s = Status.of(true, null, true, false, null, true, "node-b", false, null);

        assertEquals(Status.PROTECTED, s.state);
        assertEquals("stopped by an operator on node-b", s.reason);
    }

    @Test
    void aNonOwnerStoppedHereSaysItCannotTakeOver() {
        Status s = Status.of(true, null, false, false, null, false, null, true, "node-b");

        assertEquals(Status.PROTECTED, s.state);
        assertEquals(Status.STOPPED_HERE, s.reason);
    }

    @Test
    void aWaitingNonOwnerNamesTheHolder() {
        assertEquals("node-b holds the lease", Status.of(true, null, false, false, null, false, null, false,
            "node-b").reason);
        assertEquals("no node holds the lease yet", Status.of(true, null, false, false, null, false, null, false,
            null).reason);
    }
}
