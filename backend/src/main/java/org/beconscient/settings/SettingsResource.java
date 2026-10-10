package org.beconscient.settings;

import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import org.beconscient.api.Dto;

@Path("/api")
@Produces(MediaType.APPLICATION_JSON)
public class SettingsResource {

    @Inject
    SettingsService settings;

    @GET
    @Path("/settings")
    public Dto.SettingsView get() {
        return settings.view();
    }

    @Consumes(MediaType.APPLICATION_JSON)
    @PUT
    @Path("/settings/work-window")
    public Dto.SettingsView work(Dto.WorkWindowRequest req) {
        return settings.setWork(req);
    }

    @Consumes(MediaType.APPLICATION_JSON)
    @PUT
    @Path("/settings/awake-window")
    public Dto.SettingsView awake(Dto.AwakeWindowRequest req) {
        return settings.setAwake(req);
    }

    @Consumes(MediaType.APPLICATION_JSON)
    @POST
    @Path("/non-workdays")
    public Dto.SettingsView addNonWorkday(Dto.NonWorkdayRequest req) {
        return settings.addNonWorkday(req);
    }

    @DELETE
    @Path("/non-workdays/{date}")
    public Dto.SettingsView removeNonWorkday(@PathParam("date") String date) {
        return settings.removeNonWorkday(date);
    }
}
