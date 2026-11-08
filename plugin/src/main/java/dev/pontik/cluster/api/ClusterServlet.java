// SPDX-License-Identifier: MPL-2.0
package dev.pontik.cluster.api;

import com.mirth.connect.client.core.api.MirthApiException;
import com.mirth.connect.model.User;
import com.mirth.connect.server.api.MirthServlet;
import com.mirth.connect.server.controllers.ControllerFactory;
import dev.pontik.cluster.engine.ClusterPlugin;
import dev.pontik.cluster.engine.Rejection;
import java.util.List;
import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.SecurityContext;

/** The plugin's REST calls. The engine checks the caller's login and permission before each one. */
public class ClusterServlet extends MirthServlet implements ClusterServletInterface {

    public ClusterServlet(@Context HttpServletRequest request, @Context SecurityContext sc) {
        super(request, sc, PLUGIN_POINT);
    }

    @Override
    public void setMode(String channelId, String mode, String rereadable) {
        try {
            plugin().setMode(channelId, mode, rereadable, user());
        } catch (Rejection r) {
            throw answer(r);
        }
    }

    @Override
    public List<ChannelView> getChannels() {
        try {
            return plugin().channels();
        } catch (Rejection r) {
            throw answer(r);
        }
    }

    @Override
    public NodeView getLocal() {
        return plugin().local();
    }

    private static ClusterPlugin plugin() {
        Object plugin =
            ControllerFactory.getFactory().createExtensionController().getServicePlugins().get(PLUGIN_POINT);
        if (!(plugin instanceof ClusterPlugin)) {
            throw new MirthApiException(Response.Status.SERVICE_UNAVAILABLE);
        }
        return (ClusterPlugin) plugin;
    }

    // A text body explains a refusal in the words of the fault message; a 503 carries this node's view.
    private static MirthApiException answer(Rejection r) {
        Response.ResponseBuilder response = Response.status(r.status).entity(r.body);
        if (r.body instanceof String) {
            response.type(MediaType.TEXT_PLAIN_TYPE);
        }
        return new MirthApiException(response.build());
    }

    private String user() {
        try {
            User user = ControllerFactory.getFactory().createUserController().getUser(getCurrentUserId(), null);
            if (user != null && user.getUsername() != null) {
                return user.getUsername();
            }
        } catch (Exception e) {
            // We record the user's ID instead.
        }
        return String.valueOf(getCurrentUserId());
    }
}
