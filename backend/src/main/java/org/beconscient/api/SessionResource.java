package org.beconscient.api;

import io.quarkus.security.identity.SecurityIdentity;
import jakarta.annotation.security.PermitAll;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.NewCookie;
import jakarta.ws.rs.core.Response;
import org.jboss.resteasy.reactive.RestResponse;

/** Lets the page ask "am I signed in?" and sign out. Login itself is Quarkus form auth. */
@Path("/api")
@Produces(MediaType.APPLICATION_JSON)
public class SessionResource {

    static final String COOKIE = "beconscient-session";

    @Inject
    SecurityIdentity identity;

    /**
     * Typed on purpose. A bare Response hides the body type, so the native image
     * never registers it for reflection.
     */
    @GET
    @Path("/session")
    @PermitAll
    public RestResponse<Dto.Session> get() {
        if (identity == null || identity.isAnonymous()) {
            return RestResponse.status(RestResponse.Status.UNAUTHORIZED);
        }
        return RestResponse.ok(new Dto.Session(identity.getPrincipal().getName()));
    }

    /** Expires the session cookie; the browser then has nothing to send. */
    @POST
    @Path("/logout")
    @PermitAll
    public Response logout() {
        return Response.noContent()
                .cookie(new NewCookie.Builder(COOKIE)
                        .value("")
                        .path("/")
                        .maxAge(0)
                        .httpOnly(true)
                        .sameSite(NewCookie.SameSite.STRICT)
                        .build())
                .build();
    }
}
