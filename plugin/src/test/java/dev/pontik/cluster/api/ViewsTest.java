// SPDX-License-Identifier: MPL-2.0
package dev.pontik.cluster.api;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.thoughtworks.xstream.XStream;
import java.util.Collections;
import org.junit.jupiter.api.Test;

class ViewsTest {

    @Test
    void theViewsSerializeUnderTheirAliasesAndNameNoClass() {
        XStream xstream = new XStream();
        xstream.processAnnotations(new Class<?>[] { ChannelView.class, NodeView.class });
        ChannelView channel = new ChannelView();
        channel.channelId = "ch-1";
        channel.codes.add("PC-106");
        NodeView node = new NodeView();
        node.peers.add(new NodeView.Peer());
        node.leases.add(new NodeView.Lease());
        NodeView.Fault fault = new NodeView.Fault();
        fault.code = "PC-102";
        node.faults.add(fault);

        String channels = xstream.toXML(Collections.singletonList(channel));
        String local = xstream.toXML(node);

        assertTrue(channels.contains("<pontikChannel>"), channels);
        assertTrue(channels.contains("<thisNode>"), channels);
        assertTrue(local.startsWith("<pontikNode>"), local);
        assertTrue(local.contains("<pontikPeer>") && local.contains("<pontikLease>") && local.contains("<pontikFault>"),
            local);
        assertFalse(channels.contains("dev.pontik") || local.contains("dev.pontik"), channels + local);
    }
}
