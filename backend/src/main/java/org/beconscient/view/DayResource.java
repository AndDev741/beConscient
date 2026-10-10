package org.beconscient.view;

import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import org.beconscient.api.Dto;
import org.beconscient.common.ApiException;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;

@Path("/api")
@Produces(MediaType.APPLICATION_JSON)
public class DayResource {

    @Inject
    DayService days;

    @Inject
    TrendsService trends;

    @GET
    @Path("/day")
    public Dto.DayView day(@QueryParam("date") String date) {
        LocalDate d = date == null || date.isBlank() ? days.context().today : parse(date);
        return days.day(d);
    }

    @GET
    @Path("/trends")
    public Dto.Trends trends(@QueryParam("period") String period, @QueryParam("anchor") String anchor) {
        return trends.trends(period == null || period.isBlank() ? "week" : period,
                anchor == null || anchor.isBlank() ? null : parse(anchor));
    }

    static LocalDate parse(String s) {
        try {
            return LocalDate.parse(s);
        } catch (DateTimeParseException e) {
            throw ApiException.badRequest("Dates are YYYY-MM-DD.");
        }
    }
}
