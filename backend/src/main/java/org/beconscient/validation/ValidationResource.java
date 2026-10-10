package org.beconscient.validation;

import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import org.beconscient.api.Dto;
import org.beconscient.common.ApiException;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;

@Path("/api/validation")
@Produces(MediaType.APPLICATION_JSON)
public class ValidationResource {

    @Inject
    ValidationService validation;

    @GET
    public List<Dto.ValidationRow> list(@QueryParam("from") String from, @QueryParam("to") String to) {
        return validation.rows(date(from), date(to));
    }

    @Consumes(MediaType.APPLICATION_JSON)
    @PUT
    @Path("/{date}")
    public Dto.ValidationRow set(@PathParam("date") String date, Dto.ValidationRequest req) {
        LocalDate d = date(date);
        if (d == null) {
            throw ApiException.badRequest("Dates are YYYY-MM-DD.");
        }
        return validation.set(d, req == null ? null : req.minutes());
    }

    private static LocalDate date(String s) {
        if (s == null || s.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(s);
        } catch (DateTimeParseException e) {
            throw ApiException.badRequest("Dates are YYYY-MM-DD.");
        }
    }
}
