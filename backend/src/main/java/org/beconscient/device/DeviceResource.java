package org.beconscient.device;

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

import java.util.List;
import java.util.UUID;

@Path("/api")
@Produces(MediaType.APPLICATION_JSON)
public class DeviceResource {

    @Inject
    DeviceService devices;

    @GET
    @Path("/devices")
    public List<Dto.DeviceView> list() {
        return devices.list();
    }

    @Consumes(MediaType.APPLICATION_JSON)
    @POST
    @Path("/devices")
    public Dto.CreatedDevice create(Dto.NewDeviceRequest req) {
        return devices.create(req);
    }

    @POST
    @Path("/devices/{id}/pairing-code")
    public Dto.PairingCodeView pairingCode(@PathParam("id") String id) {
        return devices.newCode(uuid(id));
    }

    @DELETE
    @Path("/installations/{id}")
    public void revoke(@PathParam("id") String id) {
        devices.revoke(uuid(id));
    }

    @GET
    @Path("/pairing-codes/state")
    public Dto.PairingStateView state() {
        return devices.pairingState();
    }

    static UUID uuid(String id) {
        try {
            return UUID.fromString(id);
        } catch (IllegalArgumentException e) {
            throw ApiException.notFound("Item");
        }
    }
}
