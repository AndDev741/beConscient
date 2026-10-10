package org.beconscient.common;

import com.fasterxml.jackson.core.JsonProcessingException;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;
import org.jboss.resteasy.reactive.server.ServerExceptionMapper;

/** Maps failures to {"error":"<code>","message":"<human text>"} with a matching status. */
public class ApiErrors {

    private static final Logger LOG = Logger.getLogger(ApiErrors.class);

    public record Body(String error, String message) {
    }

    public static Response of(int status, String code, String message) {
        return Response.status(status).type(MediaType.APPLICATION_JSON).entity(new Body(code, message)).build();
    }

    @ServerExceptionMapper
    public Response api(ApiException e) {
        return of(e.status(), e.code(), e.getMessage());
    }

    @ServerExceptionMapper
    public Response json(JsonProcessingException e) {
        return of(400, "bad_request", "The request body is not valid JSON for this endpoint.");
    }

    @ServerExceptionMapper
    public Response illegal(IllegalArgumentException e) {
        return of(400, "bad_request", e.getMessage() == null ? "Invalid request." : e.getMessage());
    }

    @ServerExceptionMapper
    public Response notFound(NotFoundException e) {
        return of(404, "not_found", "Not found.");
    }

    @ServerExceptionMapper
    public Response web(WebApplicationException e) {
        int status = e.getResponse().getStatus();
        if (status == 401 || status == 403) {
            return e.getResponse();
        }
        return of(status, status == 404 ? "not_found" : "bad_request", "The request was refused.");
    }
}
