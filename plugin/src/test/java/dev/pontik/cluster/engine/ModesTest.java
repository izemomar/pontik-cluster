// SPDX-License-Identifier: MPL-2.0
package dev.pontik.cluster.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mirth.connect.model.Channel;
import dev.pontik.cluster.fault.Messages;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class ModesTest {

    private final Messages messages = new Messages(24, 90, 30);
    private final List<String> writes = new ArrayList<>();
    private final Object node = "the node view";

    @Test
    void aProtectedModeOnAnEligibleChannelIsWritten() throws Exception {
        set(false, "single-owner", "true", Models.file("ch-1"), null);
        set(false, "unmanaged", null, Models.javaScript("ch-1"), null);

        assertEquals(Arrays.asList("ch-1 single-owner true", "ch-1 unmanaged false"), writes);
    }

    @Test
    void whileThePluginIsOffTheCallAnswers503WithTheNodeView() {
        Rejection r = assertThrows(Rejection.class, () -> set(true, "single-owner", null, Models.file("ch-1"), null));

        assertEquals(503, r.status);
        assertEquals(node, r.body);
        assertTrue(writes.isEmpty());
    }

    @Test
    void anyOtherModeOrRereadableValueAnswers400() {
        assertEquals(400, assertThrows(Rejection.class,
            () -> set(false, "observe", null, Models.file("ch-1"), null)).status);
        assertEquals(400, assertThrows(Rejection.class,
            () -> set(false, null, null, Models.file("ch-1"), null)).status);
        assertEquals(400, assertThrows(Rejection.class,
            () -> set(false, "single-owner", "yes", Models.file("ch-1"), null)).status);
        assertTrue(writes.isEmpty());
    }

    @Test
    void aChannelThatIsNotSavedAnswers404() {
        assertEquals(404, assertThrows(Rejection.class, () -> set(false, "single-owner", null, null, null)).status);
    }

    @Test
    void eachRefusalAnswers422WithItsMessageAndWritesNothing() {
        Channel pollOnStart = Models.file("ch-1");
        ((com.mirth.connect.donkey.model.channel.PollConnectorPropertiesInterface) pollOnStart.getSourceConnector()
            .getProperties()).getPollConnectorProperties().setPollOnStart(true);

        assertRefused(Models.channelReader("ch-1"), null, messages.pc112("Channel Reader"));
        assertRefused(pollOnStart, null, messages.pc106(true));
        Channel updateOnce = Models.database("ch-1", "select 1");
        ((com.mirth.connect.connectors.jdbc.DatabaseReceiverProperties) updateOnce.getSourceConnector()
            .getProperties()).setUpdateMode(com.mirth.connect.connectors.jdbc.DatabaseReceiverProperties.UPDATE_ONCE);
        assertRefused(updateOnce, null, messages.pc106(false));
        assertRefused(Models.javaScript("ch-1"), null, messages.pc114(Messages.ChangingRead.JAVASCRIPT_READER));
        assertRefused(Models.file("ch-1"), "Router", messages.pc115("Router"));
        assertTrue(writes.isEmpty());
    }

    @Test
    void anUnmanagedModeIsWrittenWhateverTheChannelIs() throws Exception {
        set(false, "unmanaged", null, Models.channelReader("ch-1"), "Router");

        assertEquals(1, writes.size());
    }

    @Test
    void aWriteThatFailsAnswers500WithItsSqlState() {
        Rejection r = assertThrows(Rejection.class, () -> Modes.set(false, () -> node, "ch-1", "single-owner", null,
            () -> Models.file("ch-1"), null, messages, (id, mode, rereadable) -> {
                throw new SQLException("gone", "08006");
            }));

        assertEquals(500, r.status);
        assertTrue(String.valueOf(r.body).contains("SQLState 08006"));
    }

    private void assertRefused(Channel saved, String writer, String message) {
        Rejection r = assertThrows(Rejection.class, () -> set(false, "single-owner", null, saved, writer));
        assertEquals(422, r.status);
        assertEquals(message, r.body);
    }

    private void set(boolean off, String mode, String rereadable, Channel saved, String writer) throws Rejection {
        Modes.set(off, () -> node, "ch-1", mode, rereadable, () -> saved, writer, messages,
            (id, m, declared) -> writes.add(id + " " + m + " " + declared));
    }
}
