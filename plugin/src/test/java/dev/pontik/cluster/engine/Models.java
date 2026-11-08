// SPDX-License-Identifier: MPL-2.0
package dev.pontik.cluster.engine;

import com.mirth.connect.connectors.file.FileReceiverProperties;
import com.mirth.connect.connectors.jdbc.DatabaseReceiverProperties;
import com.mirth.connect.connectors.js.JavaScriptReceiverProperties;
import com.mirth.connect.connectors.vm.VmDispatcherProperties;
import com.mirth.connect.connectors.vm.VmReceiverProperties;
import com.mirth.connect.donkey.model.channel.ConnectorProperties;
import com.mirth.connect.donkey.model.channel.DeployedState;
import com.mirth.connect.model.Channel;
import com.mirth.connect.model.Connector;

/** Channel models as the engine hands them to a hook, built in a line. */
final class Models {

    private Models() {
    }

    static Channel file(String id) {
        return channel(id, new FileReceiverProperties(), "File Reader");
    }

    static Channel database(String id, String select) {
        DatabaseReceiverProperties properties = new DatabaseReceiverProperties();
        properties.setSelect(select);
        properties.setUpdateMode(DatabaseReceiverProperties.UPDATE_EACH);
        return channel(id, properties, "Database Reader");
    }

    static Channel javaScript(String id) {
        return channel(id, new JavaScriptReceiverProperties(), "JavaScript Reader");
    }

    static Channel channelReader(String id) {
        return channel(id, new VmReceiverProperties(), "Channel Reader");
    }

    static Channel channel(String id, ConnectorProperties source, String transport) {
        Channel model = new Channel();
        model.setId(id);
        model.setName("name of " + id);
        Connector connector = new Connector("Source");
        connector.setMetaDataId(0);
        connector.setProperties(source);
        connector.setTransportName(transport);
        model.setSourceConnector(connector);
        model.getProperties().setInitialState(DeployedState.STARTED);
        return model;
    }

    /** Adds a Channel Writer that sends into the target, and returns the writing channel. */
    static Channel writingTo(Channel writer, String target) {
        VmDispatcherProperties properties = new VmDispatcherProperties();
        properties.setChannelId(target);
        Connector destination = new Connector("Destination 1");
        destination.setProperties(properties);
        destination.setTransportName("Channel Writer");
        writer.addDestination(destination);
        return writer;
    }
}
