// SPDX-License-Identifier: MPL-2.0
package dev.pontik.cluster.api;

import com.mirth.connect.client.core.api.MirthApiException;
import com.mirth.connect.model.User;
import com.mirth.connect.server.api.CheckAuthorizedChannelId;
import com.mirth.connect.server.api.MirthServlet;
import com.mirth.connect.server.controllers.ControllerFactory;
import dev.pontik.cluster.engine.ClusterPlugin;
import dev.pontik.cluster.engine.Rejection;
import java.util.List;
import java.util.function.Predicate;
import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.SecurityContext;

/**
 * The plugin's REST calls. The engine checks the caller's login and permission before each one. A
 * caller limited to some channels may set the mode of those channels only, and sees no row, lease or
 * fault of the others.
 */
public class ClusterServlet extends MirthServlet implements ClusterServletInterface {

    public ClusterServlet(@Context HttpServletRequest request, @Context SecurityContext sc) {
        super(request, sc, PLUGIN_POINT);
    }

    @Override
    @CheckAuthorizedChannelId
    public void setMode(String channelId, String mode, String rereadable) {
        try {
            plugin().setMode(channelId, mode, rereadable, user());
        } catch (Rejection r) {
            throw answer(r, this::isChannelRedacted);
        }
    }

    @Override
    public List<ChannelView> getChannels() {
        try {
            List<ChannelView> views = plugin().channels();
            views.removeIf(v -> isChannelRedacted(v.channelId));
            return views;
        } catch (Rejection r) {
            throw answer(r, this::isChannelRedacted);
        }
    }

    @Override
    public NodeView getLocal() {
        return redact(plugin().local(), this::isChannelRedacted);
    }

    // A user limited to some channels sees no lease and no fault of the others; a node fault stays.
    static NodeView redact(NodeView v, Predicate<String> hidden) {
        v.leases.removeIf(l -> hidden.test(l.channelId));
        v.faults.removeIf(f -> f.channelId != null && hidden.test(f.channelId));
        return v;
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
    private static MirthApiException answer(Rejection r, Predicate<String> hidden) {
        Object body = r.body instanceof NodeView ? redact((NodeView) r.body, hidden) : r.body;
        Response.ResponseBuilder response = Response.status(r.status).entity(body);
        if (body instanceof String) {
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
