// SPDX-License-Identifier: MPL-2.0
package dev.pontik.cluster.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import com.mirth.connect.model.DashboardStatus;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.Collections;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;

class OieEngineTest {

    @Test
    void aStatusReadNamesTheAskedChannelWhenTheEngineListsAnotherFirst() {
        DashboardStatus deploying = status("ch-2");
        DashboardStatus asked = status("ch-1");

        assertSame(asked, OieEngine.pick(Arrays.asList(deploying, asked), "ch-1"));
    }

    @Test
    void aStatusReadThatListsOnlyOtherChannelsFindsNothing() {
        assertNull(OieEngine.pick(Collections.singletonList(status("ch-2")), "ch-1"));
        assertNull(OieEngine.pick(Collections.<DashboardStatus>emptyList(), "ch-1"));
    }

    @Test
    void theEngineSeamOffersOnlyReadsAndSourceStartsAndStops() {
        Set<String> calls = new TreeSet<>();
        for (Method m : Engine.class.getDeclaredMethods()) {
            if (!Modifier.isStatic(m.getModifiers()) && !m.isSynthetic()) {
                calls.add(m.getName());
            }
        }

        // A call that touches a message, a file or a row would add its name here.
        assertEquals(new TreeSet<>(Arrays.asList("deployed", "enabled", "saved", "startSource", "status", "statuses",
            "stopSource")), calls);
    }

    private static DashboardStatus status(String channelId) {
        DashboardStatus s = new DashboardStatus();
        s.setChannelId(channelId);
        return s;
    }
}
