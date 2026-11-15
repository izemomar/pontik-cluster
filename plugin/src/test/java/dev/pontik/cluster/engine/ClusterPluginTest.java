// SPDX-License-Identifier: MPL-2.0
package dev.pontik.cluster.engine;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mirth.connect.model.ExtensionPermission;
import dev.pontik.cluster.api.NodeView;
import java.util.Arrays;
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

    private static String[] sorted(String[] names) {
        String[] copy = names.clone();
        Arrays.sort(copy);
        return copy;
    }
}
