package org.beconscient.correction;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import org.beconscient.api.Dto;
import org.beconscient.common.ApiException;

import java.util.UUID;

@Path("/api/corrections")
@Produces(MediaType.APPLICATION_JSON)
public class CorrectionResource {

    @Inject
    CorrectionService corrections;

    @GET
    public Dto.Corrections list() {
        return corrections.list();
    }

    @Consumes(MediaType.APPLICATION_JSON)
    @POST
    @Path("/redactions")
    public Dto.RedactionView redact(JsonNode req) {
        return corrections.redact(req);
    }

    @Consumes(MediaType.APPLICATION_JSON)
    @POST
    @Path("/exclusions")
    public Dto.ExclusionView exclude(JsonNode req) {
        return corrections.exclude(req);
    }

    @DELETE
    @Path("/exclusions/{id}")
    public void remove(@PathParam("id") String id) {
        UUID uuid;
        try {
            uuid = UUID.fromString(id);
        } catch (IllegalArgumentException e) {
            throw ApiException.notFound("Exclusion");
        }
        corrections.removeExclusion(uuid);
    }
}
