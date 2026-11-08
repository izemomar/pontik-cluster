// SPDX-License-Identifier: MPL-2.0
package dev.pontik.cluster.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class HookTest {

    private static final long FRESH_MS = 14_000; // a 24 s validity minus one 10 s heartbeat

    @ParameterizedTest(name = "singleOwner={0} refused={1} started={2} left={3} -> {4}")
    @CsvSource({
        // Not protected.
        "false, false, true,  -1,     NOTHING",
        // Refused: the fault is raised and the channel starts as configured.
        "true,  true,  true,  -1,     REFUSE",
        // Configured paused or stopped: the operator's intent.
        "true,  false, false, -1,     NOTHING",
        // This node holds a lease confirmed within the last heartbeat: a redeploy keeps it.
        "true,  false, true,  14000,  KEEP",
        "true,  false, true,  20000,  KEEP",
        // A lease older than one heartbeat, or none: pause until this node owns the channel.
        "true,  false, true,  13999,  PAUSE",
        "true,  false, true,  -1,     PAUSE",
    })
    void theHookActsOnlyOnAProtectedChannelConfiguredToStartThatThisNodeDoesNotFreshlyHold(boolean singleOwner,
            boolean refused, boolean started, long leftMs, Hook.Action expected) {
        assertEquals(expected, Hook.decide(singleOwner, refused, started, leftMs, FRESH_MS));
    }
}
