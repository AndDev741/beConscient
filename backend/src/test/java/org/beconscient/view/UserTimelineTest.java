package org.beconscient.view;

import org.beconscient.support.Ev;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class UserTimelineTest {

    static final ZoneId LISBON = ZoneId.of("Europe/Lisbon");
    static final ZoneId SAO_PAULO = ZoneId.of("America/Sao_Paulo");

    @Test
    void withoutPhoneDataTheDayIsLisbons() {
        UserTimeline tl = UserTimeline.of(List.of(), LISBON);
        assertEquals(Ev.lisbon("2026-10-06T00:00"), tl.startOf(LocalDate.of(2026, 10, 6)));
        assertEquals(LocalDate.of(2026, 10, 6), tl.dateOf(Ev.utc("2026-10-06T22:30:00Z")));
        assertEquals(LocalDate.of(2026, 10, 7), tl.dateOf(Ev.utc("2026-10-06T23:30:00Z")), "Lisbon is UTC+1 in October");
    }

    @Test
    void anInstantTakesThePhonesZoneAtThatTime() {
        long arrival = Ev.utc("2026-10-05T12:00:00Z");
        UserTimeline tl = UserTimeline.of(List.of(new UserTimeline.Point(Ev.utc("2026-10-01T00:00:00Z"), LISBON),
                new UserTimeline.Point(arrival, SAO_PAULO)), LISBON);
        // 02:00 UTC on the 7th is still the 6th in São Paulo
        assertEquals(LocalDate.of(2026, 10, 6), tl.dateOf(Ev.utc("2026-10-07T02:00:00Z")));
        assertEquals(Ev.local("2026-10-06T00:00", "America/Sao_Paulo"), tl.startOf(LocalDate.of(2026, 10, 6)));
        assertEquals(Ev.lisbon("2026-10-03T00:00"), tl.startOf(LocalDate.of(2026, 10, 3)));
        // Before the first known point, the first known zone applies
        assertEquals(LISBON, tl.zoneAt(Ev.utc("2026-09-01T00:00:00Z")));
    }

    @Test
    void aLateMarkerMovesTheLocalDayOnTheNextRead() {
        long t = Ev.utc("2026-10-07T02:00:00Z");
        UserTimeline before = UserTimeline.of(List.of(new UserTimeline.Point(0, LISBON)), LISBON);
        assertEquals(LocalDate.of(2026, 10, 7), before.dateOf(t));
        UserTimeline after = UserTimeline.of(List.of(new UserTimeline.Point(0, LISBON),
                new UserTimeline.Point(Ev.utc("2026-10-06T12:00:00Z"), SAO_PAULO)), LISBON);
        assertEquals(LocalDate.of(2026, 10, 6), after.dateOf(t));
    }

    @Test
    void daylightSavingDaysHave23Or25Hours() {
        UserTimeline tl = UserTimeline.of(List.of(), LISBON);
        LocalDate fallBack = LocalDate.of(2026, 10, 25);
        assertEquals(25 * Ev.HOUR, tl.endOf(fallBack) - tl.startOf(fallBack));
        LocalDate springForward = LocalDate.of(2026, 3, 29);
        assertEquals(23 * Ev.HOUR, tl.endOf(springForward) - tl.startOf(springForward));
        // The repeated hour has the same label twice
        long s = tl.startOf(fallBack);
        assertEquals("01:00", tl.label(s + Ev.HOUR));
        assertEquals("01:00", tl.label(s + 2 * Ev.HOUR));
        assertEquals(Ev.lisbon("2026-10-25T09:00"), tl.instantOf(fallBack, LocalTime.of(9, 0)));
    }
}
