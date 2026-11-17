// SPDX-License-Identifier: MPL-2.0
package dev.pontik.cluster.engine;

import com.mirth.connect.connectors.file.FileReceiverProperties;
import com.mirth.connect.connectors.jdbc.DatabaseReceiverProperties;
import com.mirth.connect.connectors.js.JavaScriptReceiverProperties;
import com.mirth.connect.donkey.model.channel.ConnectorProperties;
import com.mirth.connect.donkey.model.channel.PollConnectorPropertiesInterface;
import com.mirth.connect.model.Channel;
import dev.pontik.cluster.fault.Code;
import dev.pontik.cluster.fault.Messages;

/**
 * Decides whether a channel may be protected, from its saved or deployed model. It reads the
 * model and never changes it. The first check that fails wins.
 */
final class Eligibility {

    private Eligibility() {
    }

    /**
     * Returns why the channel is refused, or null when it may be protected.
     *
     * @param rereadable whether the operator declared that a read stopped partway may be read again
     * @param writer the name of a channel whose Channel Writer sends into this one, or null
     */
    static Refusal judge(Channel model, boolean rereadable, String writer, Messages messages) {
        ConnectorProperties source = model.getSourceConnector().getProperties();

        if (!(source instanceof FileReceiverProperties || source instanceof DatabaseReceiverProperties
                || source instanceof JavaScriptReceiverProperties)) {
            return new Refusal(Code.PC112, messages.pc112(model.getSourceConnector().getTransportName()));
        }
        if (((PollConnectorPropertiesInterface) source).getPollConnectorProperties().isPollOnStart()) {
            return new Refusal(Code.PC106, messages.pc106(true));
        }
        if (source instanceof DatabaseReceiverProperties
                && ((DatabaseReceiverProperties) source).getUpdateMode() == DatabaseReceiverProperties.UPDATE_ONCE) {
            return new Refusal(Code.PC106, messages.pc106(false));
        }
        Messages.ChangingRead changing = rereadable ? null : changingRead(source);
        if (changing != null) {
            return new Refusal(Code.PC114, messages.pc114(changing));
        }
        if (writer != null) {
            return new Refusal(Code.PC115, messages.pc115(writer));
        }
        return null;
    }

    private static Messages.ChangingRead changingRead(ConnectorProperties source) {
        if (source instanceof JavaScriptReceiverProperties) {
            return Messages.ChangingRead.JAVASCRIPT_READER;
        }
        if (source instanceof DatabaseReceiverProperties) {
            DatabaseReceiverProperties database = (DatabaseReceiverProperties) source;
            if (database.isUseScript()) {
                return Messages.ChangingRead.SCRIPT_MODE;
            }
            if (!Select.plainRead(database.getSelect())) {
                return Messages.ChangingRead.WRITING_SELECT;
            }
        }
        return null;
    }
}
