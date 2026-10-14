package org.beconscient.label;

import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import org.beconscient.api.Dto;
import org.jboss.resteasy.reactive.ResponseStatus;

@Path("/api/computer-labels")
@Produces(MediaType.APPLICATION_JSON)
public class ComputerLabelResource {

    @Inject
    ComputerLabelService labels;

    @Consumes(MediaType.APPLICATION_JSON)
    @POST
    @ResponseStatus(201)
    public Dto.ComputerLabelView create(Dto.ComputerLabelRequest req) {
        return labels.create(req);
    }

    @DELETE
    @Path("/{id}")
    public void remove(@PathParam("id") String id) {
        labels.remove(id);
    }
}
