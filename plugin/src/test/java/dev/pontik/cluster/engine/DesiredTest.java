// SPDX-License-Identifier: MPL-2.0
package dev.pontik.cluster.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.mirth.connect.donkey.model.channel.DeployedState;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class DesiredTest {

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', value = {
        // situation, then handBack, held, acquiredStopped, weStopped, mustStop, anotherHolder, starting,
        // stopping, the channel and source states, and the action
        "absent channel needs nothing|false|false|false|false|true|true|false|false|||NONE",
        "a transitional channel waits for our start|false|true|false|true|false|false|true|false|STARTING|STOPPED|WAIT",
        "an untouched transitional channel is left|false|false|false|false|false|true|false|false|PAUSING|STARTED|NONE",
        "a hand-back starts what we stopped|true|false|false|true|false|false|false|false|PAUSED|STOPPED|RESTORE",
        "a hand-back leaves what we did not stop|true|false|false|false|false|false|false|false|PAUSED|STOPPED|NONE",
        "a lease taken stopped stays paused|false|true|true|true|false|false|false|false|PAUSED|STOPPED|LEAVE_PAUSED",
        "the owner starts what it stopped|false|true|false|true|false|false|false|false|PAUSED|STOPPED|START",
        "the owner judges a start in flight|false|true|false|true|false|false|true|false|PAUSED|STOPPED|JUDGE_START",
        "the owner leaves an operator's stop|false|true|false|false|false|false|false|false|PAUSED|STOPPED|NONE",
        "the owner never starts a STOPPED channel|false|true|false|true|false|false|false|false|STOPPED|STOPPED|NONE",
        "held again before the stop clears it|false|true|false|false|true|false|false|false|STARTED|STARTED|CLEAR",
        "a non-owner stops for another holder|false|false|false|false|false|true|false|false|STARTED|STARTED|STOP",
        "a demoted owner stops with nobody holding|false|false|false|false|true|false|false|false|STARTED|STARTED|STOP",
        "nobody holds, no marker: it runs|false|false|false|false|false|false|false|false|STARTED|STARTED|NONE",
        "a stop that landed clears the marker|false|false|false|true|true|false|false|true|PAUSED|STOPPED|CLEAR",
        "a stopped non-owner needs nothing|false|false|false|true|false|true|false|false|PAUSED|STOPPED|NONE",
        "a pending stop on a channel between states waits for the sweep|false|false|false|true|true|false|false|true|PAUSING|STARTED|NONE",
        "a pending stop on a source still stopping waits for the sweep|false|false|false|true|true|false|false|true|PAUSED|STOPPING|NONE",
    })
    void eachSituationOnThisNodeHasOneAction(String situation, boolean handBack, boolean held,
            boolean acquiredStopped, boolean weStopped, boolean mustStop, boolean anotherHolder, boolean starting,
            boolean stopping, DeployedState channel, DeployedState source, Desired.Action expected) {
        Desired.Facts f = new Desired.Facts();
        f.handBack = handBack;
        f.held = held;
        f.acquiredStopped = acquiredStopped;
        f.weStopped = weStopped;
        f.mustStop = mustStop;
        f.anotherHolder = anotherHolder;
        f.starting = starting;
        f.stopping = stopping;
        f.channel = channel;
        f.source = source;

        assertEquals(expected, Desired.of(f), situation);
    }
}
