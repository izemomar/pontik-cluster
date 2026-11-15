// SPDX-License-Identifier: MPL-2.0
package dev.pontik.cluster.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mirth.connect.server.api.CheckAuthorizedChannelId;
import java.util.Arrays;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class ClusterServletTest {

    @Test
    void settingAModeChecksTheCallersAccessToThatChannel() throws Exception {
        assertTrue(ClusterServlet.class.getMethod("setMode", String.class, String.class, String.class)
            .isAnnotationPresent(CheckAuthorizedChannelId.class));
    }

    @Test
    void aCallerLimitedToSomeChannelsSeesNoLeaseAndNoFaultOfTheOthers() {
        NodeView v = new NodeView();
        v.leases.add(lease("ch-1"));
        v.leases.add(lease("ch-2"));
        v.faults.add(fault(null));
        v.faults.add(fault("ch-1"));
        v.faults.add(fault("ch-2"));

        ClusterServlet.redact(v, "ch-2"::equals);

        assertEquals(Arrays.asList("ch-1"), v.leases.stream().map(l -> l.channelId).collect(Collectors.toList()));
        assertEquals(Arrays.asList(null, "ch-1"),
            v.faults.stream().map(f -> f.channelId).collect(Collectors.toList()));
    }

    private static NodeView.Lease lease(String channelId) {
        NodeView.Lease lease = new NodeView.Lease();
        lease.channelId = channelId;
        return lease;
    }

    private static NodeView.Fault fault(String channelId) {
        NodeView.Fault fault = new NodeView.Fault();
        fault.code = "PC-103";
        fault.channelId = channelId;
        return fault;
    }
}
