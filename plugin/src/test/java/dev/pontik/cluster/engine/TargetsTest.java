// SPDX-License-Identifier: MPL-2.0
package dev.pontik.cluster.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.mirth.connect.model.Channel;
import dev.pontik.cluster.fault.RecordingSink;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import org.junit.jupiter.api.Test;

class TargetsTest {

    private final RecordingSink sink = new RecordingSink();
    private final Targets targets = new Targets(sink);

    @Test
    void aWriterNamesItsTargetByTheWritingChannelsName() {
        targets.replace(Collections.singletonList(Models.writingTo(Models.file("router"), "ch-1")), id -> true);

        assertEquals("name of router", targets.writer("ch-1"));
        assertNull(targets.writer("router"));
    }

    @Test
    void aDeployAddsItsTargetsAndSaysWhichAreNew() {
        Channel router = Models.writingTo(Models.file("router"), "ch-1");

        assertEquals(Collections.singleton("ch-1"), targets.add(router));
        assertEquals(Collections.emptySet(), targets.add(router));
    }

    @Test
    void aWriterThatDoesNotCountNamesNoTarget() {
        Channel router = Models.writingTo(Models.file("router"), "ch-1");

        targets.replace(Collections.singletonList(router), id -> false);

        assertNull(targets.writer("ch-1"));
    }

    @Test
    void aReplaceReportsTargetsThatCameAndWent() {
        targets.replace(Collections.singletonList(Models.writingTo(Models.file("a"), "ch-1")), id -> true);

        assertEquals(new HashSet<>(Arrays.asList("ch-1", "ch-2")),
            targets.replace(Collections.singletonList(Models.writingTo(Models.file("b"), "ch-2")), id -> true));
        assertNull(targets.writer("ch-1"));
    }

    @Test
    void aTargetChosenWhileTheChannelRunsIsLoggedOnceAndNamesNothing() {
        Channel router = Models.writingTo(Models.file("router"), "${target}");

        targets.add(router);
        targets.add(router);

        assertEquals(1, sink.warnings.size());
        assertNull(targets.writer("${target}"));
    }

    @Test
    void noTargetIsNoTarget() {
        targets.add(Models.writingTo(Models.file("router"), "none"));

        assertNull(targets.writer("none"));
    }
}
