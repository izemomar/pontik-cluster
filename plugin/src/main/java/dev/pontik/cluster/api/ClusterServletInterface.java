// SPDX-License-Identifier: MPL-2.0
package dev.pontik.cluster.api;

import com.mirth.connect.client.core.ClientException;
import com.mirth.connect.client.core.api.BaseServletInterface;
import com.mirth.connect.client.core.api.MirthOperation;
import com.mirth.connect.client.core.api.Param;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import javax.ws.rs.Consumes;
import javax.ws.rs.GET;
import javax.ws.rs.PUT;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.MediaType;

/**
 * The plugin's REST calls, under /api/extensions/pontik-cluster. You set a mode with query
 * parameters and no body. Both reads answer from this node's memory and never touch the database.
 */
@Path("/extensions/pontik-cluster")
@Tag(name = "Extension Services")
@Consumes({ MediaType.APPLICATION_XML, MediaType.APPLICATION_JSON })
@Produces({ MediaType.APPLICATION_XML, MediaType.APPLICATION_JSON })
public interface ClusterServletInterface extends BaseServletInterface {

    String PLUGIN_POINT = "Pontik Cluster";

    String PERMISSION_VIEW = "View cluster status";
    String PERMISSION_SET_MODE = "Set cluster mode";

    @PUT
    @Path("/channels/{channelId}/mode")
    @Operation(summary = "Sets a channel's cluster mode: single-owner protects it, unmanaged hands it back.")
    @MirthOperation(name = "setPontikClusterMode", display = "Set cluster mode", permission = PERMISSION_SET_MODE)
    void setMode(
        @Param("channelId") @Parameter(description = "The channel ID.", required = true)
        @PathParam("channelId") String channelId,
        @Param("mode") @Parameter(description = "single-owner or unmanaged.", required = true)
        @QueryParam("mode") String mode,
        @Param("rereadable") @Parameter(description = "true when a read stopped partway may be read again.")
        @QueryParam("rereadable") String rereadable) throws ClientException;

    @GET
    @Path("/channels")
    @Operation(summary = "Returns every channel deployed on this node and every channel with a mode, as this node "
        + "sees them.")
    @MirthOperation(name = "getPontikClusterChannels", display = "Get cluster channels", permission = PERMISSION_VIEW,
        auditable = false)
    List<ChannelView> getChannels() throws ClientException;

    @GET
    @Path("/local")
    @Operation(summary = "Returns this node as the plugin sees it.")
    @MirthOperation(name = "getPontikClusterNode", display = "Get cluster node", permission = PERMISSION_VIEW,
        auditable = false)
    NodeView getLocal() throws ClientException;
}
