package org.beconscient.view;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.beconscient.common.Jdbc;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.time.ZoneId;
import java.util.List;

/** Builds the user timeline from the phone's zone points. */
@ApplicationScoped
public class Timelines {

    @Inject
    Jdbc jdbc;

    @ConfigProperty(name = "beconscient.fallback-zone", defaultValue = "Europe/Lisbon")
    String fallbackZone;

    public UserTimeline load() {
        List<UserTimeline.Point> points = jdbc.list("""
                        select z.at, z.zone from zone_points z join devices d on d.id = z.device_id
                        where d.kind = 'android'
                          and not exists (select 1 from exclusions x where x.device_id = z.device_id
                                          and (x.type is null or x.type = 'android.tz_change') and z.source = 'marker'
                                          and z.at between x.from_ts and x.to_ts)
                        order by z.at, z.id""",
                rs -> new UserTimeline.Point(rs.getLong("at"), ZoneId.of(rs.getString("zone"))));
        return UserTimeline.of(points, fallback());
    }

    public ZoneId fallback() {
        return ZoneId.of(fallbackZone);
    }
}
