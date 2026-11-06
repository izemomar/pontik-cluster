// SPDX-License-Identifier: MPL-2.0
package dev.pontik.cluster.lease;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Collections;
import java.util.Set;
import org.junit.jupiter.api.Test;

class LocalStateTest {

    private static final Set<String> NONE = Collections.emptySet();
    private static final Set<String> CH1 = Collections.singleton("ch-1");

    private final FakeClock clock = new FakeClock();
    private final LocalState local = new LocalState(Timing.STANDARD);

    @Test
    void oneIdleSweepThatFindsTheChannelGoneIsNotEnoughAndTwoAre() {
        local.deployed("ch-1", "Orders");
        local.undeployed("ch-1");

        sweep(false, NONE);
        assertNull(local.releaseDue("ch-1", clock.getAsLong()));

        sweep(false, NONE);
        assertEquals(LocalState.Release.UNDEPLOYED, local.releaseDue("ch-1", clock.getAsLong()));
    }

    @Test
    void aBusySweepASweepThatFindsTheChannelOrItsDeployHookResetsTheCount() {
        local.deployed("ch-1", "Orders");
        local.undeployed("ch-1");

        sweep(false, NONE);
        sweep(true, NONE);
        sweep(false, NONE);
        assertNull(local.releaseDue("ch-1", clock.getAsLong()));

        sweep(false, CH1);
        sweep(false, NONE);
        assertNull(local.releaseDue("ch-1", clock.getAsLong()));

        local.deployed("ch-1", "Orders");
        sweep(false, NONE);
        assertNull(local.releaseDue("ch-1", clock.getAsLong()));
        assertTrue(local.deployedHere("ch-1"));
    }

    @Test
    void aChannelWhoseDeployNeverArrivesIsUndeployedByTwoIdleSweeps() {
        local.deployed("ch-1", "Orders");

        sweep(false, NONE);
        sweep(false, NONE);

        assertFalse(local.deployedHere("ch-1"));
        assertEquals(LocalState.Release.UNDEPLOYED, local.releaseDue("ch-1", clock.getAsLong()));
    }

    @Test
    void aSweepOlderThanTwoHeartbeatsCountsAsBusy() {
        local.deployed("ch-1", "Orders");
        local.undeployed("ch-1");
        sweep(false, NONE);
        sweep(false, NONE);

        clock.advanceMs(20_001);

        assertNull(local.releaseDue("ch-1", clock.getAsLong()));
    }

    @Test
    void noSweepYetCountsAsBusy() {
        local.deployed("ch-1", "Orders");
        local.undeployed("ch-1");

        assertNull(local.releaseDue("ch-1", clock.getAsLong()));
        assertTrue(local.engineBusy());
    }

    @Test
    void aRefusalAndAFailedStartAreDueAtOnce() {
        local.deployed("ch-1", "Orders");
        local.verdict("ch-1", "PC-114", true);
        assertEquals(LocalState.Release.REFUSED, local.releaseDue("ch-1", clock.getAsLong()));

        local.deployed("ch-2", "Billing");
        local.startFailed("ch-2", clock.getAsLong());
        assertEquals(LocalState.Release.START_FAILED, local.releaseDue("ch-2", clock.getAsLong()));
    }

    @Test
    void anAcquisitionThatRacedTheUndeployIsMarked() {
        local.deployed("ch-1", "Orders");
        local.undeployed("ch-1");
        local.acquired("ch-1", false);
        sweep(false, NONE);
        sweep(false, NONE);

        assertEquals(LocalState.Release.RACED, local.releaseDue("ch-1", clock.getAsLong()));
    }

    @Test
    void eligibilityNeedsADeployedEligibleRunningChannelNobodyStoppedAndNoCoolDown() {
        local.deployed("ch-1", "Orders");
        local.verdict("ch-1", null, true);
        assertTrue(local.eligible("ch-1", clock.getAsLong()));

        local.observed("ch-1", true, false);
        assertFalse(local.eligible("ch-1", clock.getAsLong()));
        local.observed("ch-1", false, true);
        assertFalse(local.eligible("ch-1", clock.getAsLong()));
        local.observed("ch-1", false, false);

        local.startFailed("ch-1", clock.getAsLong());
        assertFalse(local.eligible("ch-1", clock.getAsLong()));
        clock.advanceMs(90_000);
        assertTrue(local.eligible("ch-1", clock.getAsLong()));

        local.verdict("ch-1", "PC-106", true);
        assertFalse(local.eligible("ch-1", clock.getAsLong()));

        assertFalse(local.eligible("unknown", clock.getAsLong()));
    }

    @Test
    void theDeployHookClearsTheMarksOfThePreviousRuntimeCopy() {
        local.deployed("ch-1", "Orders");
        local.setWeStopped("ch-1", true);
        local.startFailed("ch-1", clock.getAsLong());

        local.deployed("ch-1", "Orders");

        assertFalse(local.weStopped("ch-1"));
        assertNull(local.releaseDue("ch-1", clock.getAsLong()));
        assertTrue(local.eligible("ch-1", clock.getAsLong()));
    }

    @Test
    void theOperatorStopStartsAsTheRowSaysAndEveryReleaseForgetsIt() {
        local.deployed("ch-1", "Orders");
        local.acquired("ch-1", true);
        assertTrue(local.operatorStop("ch-1"));
        assertTrue(local.acquiredStopped("ch-1"));

        local.released("ch-1");

        assertFalse(local.operatorStop("ch-1"));
        assertFalse(local.acquiredStopped("ch-1"));
    }

    @Test
    void theUndeployHookStartsTheCountAgain() {
        local.deployed("ch-1", "Orders");
        sweep(false, NONE);
        local.undeployed("ch-1");
        sweep(false, NONE);
        assertNull(local.releaseDue("ch-1", clock.getAsLong()));
        sweep(false, NONE);
        assertEquals(LocalState.Release.UNDEPLOYED, local.releaseDue("ch-1", clock.getAsLong()));
    }

    @Test
    void aSweepExactlyTwoHeartbeatsOldIsStillFresh() {
        local.deployed("ch-1", "Orders");
        local.undeployed("ch-1");
        sweep(false, NONE);
        sweep(false, NONE);

        clock.advanceMs(20_000);

        assertEquals(LocalState.Release.UNDEPLOYED, local.releaseDue("ch-1", clock.getAsLong()));
    }

    @Test
    void theStopMarksReadAsSetUntilTheyAreCleared() {
        local.deployed("ch-1", "Orders");
        local.setWeStopped("ch-1", true);
        local.acquired("ch-1", true);
        assertTrue(local.weStopped("ch-1"));
        assertTrue(local.acquiredStopped("ch-1"));
        assertFalse(local.weStopped("unknown"));

        local.setWeStopped("ch-1", false);

        assertFalse(local.weStopped("ch-1"));
    }

    @Test
    void theVerdictTellsWhetherTheChannelIsConfiguredToStart() {
        local.verdict("ch-1", null, false);
        assertFalse(local.configuredStarted("ch-1"));

        local.verdict("ch-1", null, true);
        assertTrue(local.configuredStarted("ch-1"));
    }

    @Test
    void whatTheSweepObservedTellsWhetherTheChannelIsStoppedHere() {
        local.deployed("ch-1", "Orders");
        assertFalse(local.stoppedHere("ch-1"));

        local.observed("ch-1", true, false);
        assertTrue(local.stoppedHere("ch-1"));

        local.observed("ch-1", false, true);
        assertTrue(local.stoppedHere("ch-1"));

        assertFalse(local.stoppedHere("unknown"));
    }

    @Test
    void theLatestSweepTellsWhetherTheEngineWasBusy() {
        sweep(false, NONE);

        assertFalse(local.engineBusy());
    }

    @Test
    void aChannelIsNamedByItsDeployHookElseByItsId() {
        local.deployed("ch-1", "Orders");
        local.verdict("ch-2", null, true);

        assertEquals("Orders", local.name("ch-1"));
        assertEquals("ch-2", local.name("ch-2"));
        assertEquals("ch-3", local.name("ch-3"));
    }

    private void sweep(boolean busy, Set<String> present) {
        clock.advanceMs(10_000);
        local.swept(clock.getAsLong(), busy, present);
    }
}
