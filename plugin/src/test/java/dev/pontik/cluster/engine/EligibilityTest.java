// SPDX-License-Identifier: MPL-2.0
package dev.pontik.cluster.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.mirth.connect.connectors.jdbc.DatabaseReceiverProperties;
import com.mirth.connect.donkey.model.channel.PollConnectorPropertiesInterface;
import com.mirth.connect.model.Channel;
import dev.pontik.cluster.fault.Code;
import dev.pontik.cluster.fault.Messages;
import org.junit.jupiter.api.Test;

class EligibilityTest {

    private final Messages messages = new Messages(24, 90, 30);

    @Test
    void aFileReaderAndAPlainDatabaseReadAreEligible() {
        assertNull(judge(Models.file("ch-1"), false, null));
        assertNull(judge(Models.database("ch-1", "SELECT * FROM orders"), false, null));
    }

    @Test
    void aSourceThatIsNotAPollingReaderIsRefusedWithPc112NamingItsType() {
        Refusal r = judge(Models.channelReader("ch-1"), false, null);

        assertEquals(Code.PC112, r.code);
        assertEquals(messages.pc112("Channel Reader"), r.message);
    }

    @Test
    void pollOnceOnStartIsRefusedWithPc106() {
        Channel model = Models.file("ch-1");
        poll(model).getPollConnectorProperties().setPollOnStart(true);

        Refusal r = judge(model, true, null);

        assertEquals(Code.PC106, r.code);
        assertEquals(messages.pc106(true), r.message);
    }

    @Test
    void postProcessingOnceAfterAllMessagesIsRefusedWithPc106EvenWhenRereadable() {
        Channel model = Models.database("ch-1", "SELECT * FROM orders");
        database(model).setUpdateMode(DatabaseReceiverProperties.UPDATE_ONCE);

        Refusal r = judge(model, true, null);

        assertEquals(Code.PC106, r.code);
        assertEquals(messages.pc106(false), r.message);
    }

    @Test
    void aReadThatMayChangeDataIsRefusedWithPc114UnlessDeclaredRereadable() {
        Channel script = Models.database("ch-1", "SELECT 1");
        database(script).setUseScript(true);
        Channel writing = Models.database("ch-2", "WITH d AS (DELETE FROM q RETURNING *) SELECT * FROM d");

        assertEquals(messages.pc114(Messages.ChangingRead.JAVASCRIPT_READER),
            judge(Models.javaScript("ch-3"), false, null).message);
        assertEquals(messages.pc114(Messages.ChangingRead.SCRIPT_MODE), judge(script, false, null).message);
        assertEquals(messages.pc114(Messages.ChangingRead.WRITING_SELECT), judge(writing, false, null).message);

        assertNull(judge(Models.javaScript("ch-3"), true, null));
        assertNull(judge(script, true, null));
        assertNull(judge(writing, true, null));
    }

    @Test
    void aChannelWriterTargetIsRefusedWithPc115NamingTheWriter() {
        Refusal r = judge(Models.file("ch-1"), false, "Router");

        assertEquals(Code.PC115, r.code);
        assertEquals(messages.pc115("Router"), r.message);
    }

    @Test
    void theFirstCheckThatFailsWins() {
        Channel model = Models.javaScript("ch-1");
        poll(model).getPollConnectorProperties().setPollOnStart(true);

        assertEquals(Code.PC106, judge(model, false, "Router").code);
    }

    private Refusal judge(Channel model, boolean rereadable, String writer) {
        return Eligibility.judge(model, rereadable, writer, messages);
    }

    private static PollConnectorPropertiesInterface poll(Channel model) {
        return (PollConnectorPropertiesInterface) model.getSourceConnector().getProperties();
    }

    private static DatabaseReceiverProperties database(Channel model) {
        return (DatabaseReceiverProperties) model.getSourceConnector().getProperties();
    }
}
