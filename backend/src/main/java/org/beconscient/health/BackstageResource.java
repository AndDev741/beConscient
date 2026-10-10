package org.beconscient.health;

import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import org.beconscient.api.Dto;
import org.beconscient.common.ApiException;

@Path("/api/backstage")
@Produces(MediaType.APPLICATION_JSON)
public class BackstageResource {

    @Inject
    BackstageService backstage;

    @GET
    public Dto.Backstage get() {
        return backstage.backstage();
    }

    @GET
    @Path("/trace")
    public Dto.Trace trace(@QueryParam("segmentId") String segmentId) {
        long id;
        try {
            id = Long.parseLong(segmentId);
        } catch (NumberFormatException | NullPointerException e) {
            throw ApiException.notFound("Segment");
        }
        return backstage.trace(id);
    }
}
