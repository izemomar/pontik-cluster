// SPDX-License-Identifier: MPL-2.0
package dev.pontik.cluster.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

class StatusTest {

    @Test
    void aChannelWithoutTheModeIsUnmanaged() {
        Status.Facts f = facts();
        f.singleOwner = false;
        f.unprotectedBy = "a fault";
        f.running = true;

        Status s = Status.of(f);

        assertEquals(Status.UNMANAGED, s.state);
        assertNull(s.reason);
    }

    @Test
    void aFiringCodeMakesTheChannelUnprotectedWithItsMessage() {
        Status.Facts f = facts();
        f.unprotectedBy = "Refused: ...";
        f.held = true;
        f.running = true;

        Status s = Status.of(f);

        assertEquals(Status.UNPROTECTED, s.state);
        assertEquals("Refused: ...", s.reason);
    }

    @Test
    void aSourceRunningWithoutALeaseIsUnprotectedWithACalmReason() {
        Status.Facts f = facts();
        f.running = true;

        assertEquals(Status.UNPROTECTED, Status.of(f).state);
        assertEquals(Status.TAKING_OVER, Status.of(f).reason);

        f.undone = "A start on \"node-a\" was undone: \"node-b\" owns this channel. Start or stop it on "
            + "\"node-b\".";
        f.holder = "\"node-b\"";
        assertEquals(f.undone, Status.of(f).reason);
    }

    @Test
    void theTakingOverReasonSaysTwoRenewalIntervals() {
        assertEquals("taking over from another node, settles within about 20 s", Status.TAKING_OVER);
    }

    @Test
    void anOperatorsStopIsProtectedAndNamesTheNode() {
        Status.Facts f = facts();
        f.held = true;
        f.operatorStop = true;
        f.stoppedOn = "\"node-b\"";

        Status s = Status.of(f);

        assertEquals(Status.PROTECTED, s.state);
        assertEquals("stopped by an operator on \"node-b\"", s.reason);
    }

    @Test
    void aNonOwnerStoppedHereSaysItCannotTakeOver() {
        Status.Facts f = facts();
        f.channelStopped = true;
        f.holder = "\"node-b\"";

        Status s = Status.of(f);

        assertEquals(Status.PROTECTED, s.state);
        assertEquals(Status.STOPPED_HERE, s.reason);
    }

    @Test
    void aWaitingNonOwnerNamesTheHolder() {
        Status.Facts f = facts();
        f.holder = "\"node-b\"";
        assertEquals("\"node-b\" holds the lease", Status.of(f).reason);

        f.holder = null;
        assertEquals("no node holds the lease yet", Status.of(f).reason);
    }

    private static Status.Facts facts() {
        Status.Facts f = new Status.Facts();
        f.singleOwner = true;
        return f;
    }
}
