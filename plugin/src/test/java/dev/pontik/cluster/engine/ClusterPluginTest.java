// SPDX-License-Identifier: MPL-2.0
package dev.pontik.cluster.engine;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mirth.connect.model.ExtensionPermission;
import com.mirth.connect.model.converters.ObjectXMLSerializer;
import com.thoughtworks.xstream.mapper.ClassAliasingMapper;
import dev.pontik.cluster.api.ChannelView;
import dev.pontik.cluster.api.NodeView;
import dev.pontik.cluster.fault.RecordingSink;
import dev.pontik.cluster.lease.ConfigWriter;
import dev.pontik.cluster.lease.Connections;
import dev.pontik.cluster.lease.DbSettings;
import dev.pontik.cluster.lease.Link;
import java.util.Arrays;
import java.util.Collections;
import java.util.Properties;
import org.junit.jupiter.api.Test;

class ClusterPluginTest {

    private final ClusterPlugin plugin = new ClusterPlugin();

    @Test
    void thePluginKeepsNothingInPluginProperties() {
        assertTrue(plugin.getDefaultProperties().isEmpty());
        assertEquals("Pontik Cluster", plugin.getPluginPointName());
    }

    @Test
    void theTwoPermissionsCoverTheirCalls() {
        ExtensionPermission[] permissions = plugin.getExtensionPermissions();

        assertEquals(2, permissions.length);
        assertEquals("View cluster status", permissions[0].getDisplayName());
        assertEquals(Arrays.asList("getPontikClusterChannels", "getPontikClusterNode"),
            Arrays.asList(sorted(permissions[0].getOperationNames())));
        assertEquals("Set cluster mode", permissions[1].getDisplayName());
        assertEquals(Arrays.asList("setPontikClusterMode"), Arrays.asList(permissions[1].getOperationNames()));
    }

    @Test
    void beforeTheCoordinatorRunsTheChannelsCallAnswers503WithTheNodeView() {
        Rejection r = assertThrows(Rejection.class, plugin::channels);

        assertEquals(503, r.status);
        assertEquals("OFF", ((NodeView) r.body).state);
        assertEquals(503, assertThrows(Rejection.class,
            () -> plugin.setMode("ch-1", "single-owner", null, "admin")).status);
    }

    @Test
    void aStopBeforeAnyStartDoesNothing() {
        assertDoesNotThrow(plugin::stop);

        assertEquals("OFF", plugin.local().state);
    }

    @Test
    void aPluginThatNeverStartedReportsNoTimingAndNoGate() {
        NodeView v = plugin.local();

        assertEquals("OFF", v.state);
        assertNull(v.timing);
        assertNull(v.gateAvailable);
    }

    @Test
    void initRegistersTheAliasesOfTheTwoViews() {
        plugin.init(new Properties());

        // The engine's outer mapper reaches into Rhino, so we ask the aliasing mapper under it.
        ClassAliasingMapper aliases = (ClassAliasingMapper) ObjectXMLSerializer.getInstance().getXStream()
            .getMapper().lookupMapperOfType(ClassAliasingMapper.class);
        assertEquals("pontikNode", aliases.serializedClass(NodeView.class));
        assertEquals("pontikChannel", aliases.serializedClass(ChannelView.class));
    }

    private static String[] sorted(String[] names) {
        String[] copy = names.clone();
        Arrays.sort(copy);
        return copy;
    }

    @Test
    void aModeThatCannotBeForgottenIsLoggedWithItsSqlStateAndNoDriverText() throws Exception {
        RecordingSink sink = new RecordingSink();
        ConfigWriter writer = new ConfigWriter(new Link(Connections.second(
            new DbSettings("postgres", "jdbc:postgresql://127.0.0.1:1/engine", "pcl_user", "pw"))));

        ClusterPlugin.forget(writer, Models.file("ch-1"), sink);

        assertEquals(Collections.singletonList("This node could not forget the mode of the deleted channel \"name of "
            + "ch-1\" (ch-1): the database answered SQLState 08001. GET /channels keeps listing it, and a channel "
            + "created again under this ID starts in that mode. null"), sink.warnings);
        assertFalse(sink.warnings.get(0).contains("127.0.0.1"));
    }
}
