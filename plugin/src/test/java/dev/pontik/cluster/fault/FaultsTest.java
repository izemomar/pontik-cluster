// SPDX-License-Identifier: MPL-2.0
package dev.pontik.cluster.fault;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class FaultsTest {

    private final RecordingSink sink = new RecordingSink();
    private final Faults faults = new Faults(sink, new FixedNames("srv-1", "node-a").channel("ch-1", "Orders"));

    @Test
    void aFaultFiresOncePerTransitionAndClearsOnceAtInfo() {
        faults.raise(Code.PC103, "ch-1", "first");
        faults.raise(Code.PC103, "ch-1", "again");
        assertEquals(1, sink.fired.size());
        assertTrue(faults.isFiring(Code.PC103, "ch-1"));

        faults.clear(Code.PC103, "ch-1");
        faults.clear(Code.PC103, "ch-1");
        assertEquals(1, sink.cleared.size());
        assertFalse(faults.isFiring(Code.PC103, "ch-1"));

        faults.raise(Code.PC103, "ch-1", "next time");
        assertEquals(2, sink.fired.size());
    }

    @Test
    void pc101IsOneShot() {
        faults.raise(Code.PC101, "ch-1", "once");
        faults.raise(Code.PC101, "ch-1", "twice");
        assertEquals(2, sink.fired.size());
        assertFalse(faults.isFiring(Code.PC101, "ch-1"));
    }

    @Test
    void theLogLineNamesTheChannelAndTheNodeByNameAndId() {
        faults.raise(Code.PC103, "ch-1", "What happened. What you do.");
        faults.raise(Code.PC102, null, "What happened. What you do.");
        faults.clear(Code.PC103, "ch-1");

        assertEquals(Arrays.asList(
            "[PONTIK-CLUSTER] PC-103 Orders (ch-1) on node-a (srv-1): What happened. What you do.",
            "[PONTIK-CLUSTER] PC-102 on node-a (srv-1): What happened. What you do."), sink.fired);
        assertEquals(Arrays.asList("[PONTIK-CLUSTER] PC-103 Orders (ch-1) on node-a (srv-1): cleared."), sink.cleared);
    }

    @Test
    void onlyCodesMarkedUnprotectedMakeAChannelUnprotected() {
        faults.raise(Code.PC103, "ch-1", "x");
        assertFalse(faults.unprotected("ch-1"));

        faults.raise(Code.PC114, "ch-1", "x");
        assertTrue(faults.unprotected("ch-1"));
        assertFalse(faults.unprotected("ch-2"));

        faults.raise(Code.PC119, null, "x");
        assertTrue(faults.unprotected("ch-2"));
    }

    @Test
    void theFiringListHoldsWhatStillFiresWithItsScopeAndMessage() {
        faults.raise(Code.PC103, "ch-1", "no owner");
        faults.raise(Code.PC119, null, "two processes");
        faults.raise(Code.PC101, "ch-1", "lost");
        faults.clear(Code.PC119, null);

        List<Faults.Firing> firing = faults.firing();

        assertEquals(1, firing.size());
        assertEquals(Code.PC103, firing.get(0).code);
        assertEquals("ch-1", firing.get(0).channelId);
        assertEquals("no owner", firing.get(0).message);
    }

    @Test
    void aFaultAsksForNamesOnlyOutsideItsOwnMonitor() {
        LockProbe names = new LockProbe();
        Faults probed = new Faults(sink, names);
        names.faults = probed;

        probed.raise(Code.PC103, "ch-1", "x");
        probed.clear(Code.PC103, "ch-1");

        assertTrue(names.calls > 0);
        assertFalse(names.heldDuringACall);

        int calls = names.calls;
        probed.clear(Code.PC103, "ch-1");
        assertEquals(calls, names.calls);
    }

    @Test
    void theLevelsAndScopesAreThoseOfTheTable() {
        for (Code code : Code.values()) {
            boolean warn = code == Code.PC103 || code == Code.PC105;
            assertEquals(warn ? Code.Level.WARN : Code.Level.ERROR, code.level, code.label());
        }
        assertEquals("PC-118", Code.PC118.label());
        assertEquals(15, Code.values().length);
    }

    /** Names that note whether the caller held the monitor of the Faults it is handed. */
    private static final class LockProbe implements Names {

        Faults faults;
        int calls;
        boolean heldDuringACall;

        @Override
        public String nodeId() {
            return seen("srv-1");
        }

        @Override
        public String nodeName() {
            return seen("node-a");
        }

        @Override
        public String channelName(String channelId) {
            return seen(channelId);
        }

        private String seen(String name) {
            calls++;
            heldDuringACall |= Thread.holdsLock(faults);
            return name;
        }
    }
}
