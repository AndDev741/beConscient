package org.beconscient.ingest;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.beconscient.support.Ev;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ContractFilterTest {

    static ContractFilter.Accepted accepted(ObjectNode e) {
        return assertInstanceOf(ContractFilter.Accepted.class, ContractFilter.apply(e));
    }

    static String rejected(ObjectNode e) {
        return assertInstanceOf(ContractFilter.Rejected.class, ContractFilter.apply(e)).reason();
    }

    @Test
    void stringsAreTruncatedToTheirMaximum() {
        ObjectNode site = Ev.site(Ev.id(), 1, 2, 1, "youtube", true);
        site.put("title", "x".repeat(400));
        assertEquals(300, accepted(site).payload().get("title").asText().length());
        ObjectNode noInstance = Ev.resumed(1, "com.whatsapp");
        noInstance.remove("instance");
        noInstance.remove("class");
        assertEquals(0, accepted(noInstance).dropped(), "class and instance are optional");
        ObjectNode usage = Ev.resumed(1, "p".repeat(300));
        assertEquals(255, accepted(usage).payload().get("package").asText().length());
        ObjectNode health = Ev.phoneHealth(1, 1);
        health.with("sensors").with("usage").put("detail", "d".repeat(500));
        assertEquals(120, accepted(health).payload().at("/sensors/usage/detail").asText().length());
    }

    @Test
    void unknownFieldsAndSensorsAreDroppedAndCounted() {
        ObjectNode health = Ev.phoneHealth(1, 1);
        health.with("sensors").putObject("gps").put("state", "ok");
        health.with("sensors").with("usage").put("location", "here");
        health.put("battery_level", 50);
        ContractFilter.Accepted a = accepted(health);
        assertEquals(3, a.dropped());
        assertFalse(a.payload().get("sensors").has("gps"));
        assertFalse(a.payload().at("/sensors/usage").has("location"));
    }

    @Test
    void invalidValuesAreRejectedWithTheirReason() {
        ObjectNode health = Ev.phoneHealth(1, 1);
        health.with("sensors").with("usage").put("state", "broken");
        assertEquals("invalid_enum", rejected(health));
        assertEquals("invalid_enum", rejected(Ev.usage(1, "SCREEN_SIDEWAYS")));
        assertEquals("missing_field", rejected(Ev.usage(1, "ACTIVITY_RESUMED")), "activity kinds need a package");
        ObjectNode noId = Ev.screenOn(1);
        noId.remove("id");
        assertEquals("missing_id", rejected(noId));
        ObjectNode badId = Ev.screenOn(1);
        badId.put("id", "not-a-uuid");
        assertEquals("invalid_id", rejected(badId));
        ObjectNode noTime = Ev.screenOn(1);
        noTime.remove("at");
        assertEquals("missing_time", rejected(noTime));
        ObjectNode noRev = Ev.activity(Ev.id(), 1, 2, 1);
        noRev.remove("rev");
        assertEquals("missing_rev", rejected(noRev));
        ObjectNode noType = Ev.screenOn(1);
        noType.remove("type");
        assertEquals("missing_type", rejected(noType));
    }

    @Test
    void aRejectedEventKeepsOnlyItsValidAllowedFields() {
        ObjectNode e = Ev.site(Ev.id(), 5, 1, 1, "youtube", true);
        e.put("url", "https://youtube.com/watch?v=secret");
        ContractFilter.Rejected r = assertInstanceOf(ContractFilter.Rejected.class, ContractFilter.apply(e));
        assertEquals("end_before_start", r.reason());
        assertFalse(r.stripped().has("url"));
        assertTrue(r.stripped().has("site"));
    }

    @Test
    void pageKindIsLinkedinOnlyAndTitleIsYoutubeOnly() {
        ObjectNode yt = Ev.site(Ev.id(), 1, 2, 1, "youtube", true);
        yt.put("page_kind", "feed");
        ContractFilter.Accepted a = accepted(yt);
        assertFalse(a.payload().has("page_kind"));
        assertEquals(1, a.dropped());
        ObjectNode li = Ev.site(Ev.id(), 1, 2, 1, "linkedin", false);
        li.put("page_kind", "other");
        assertEquals("other", accepted(li).payload().get("page_kind").asText());
        li.putNull("page_kind");
        assertNull(accepted(li).payload().get("page_kind"));
    }

    @Test
    void naturalKeysFollowTheContract() {
        ContractFilter.Accepted usage = accepted(Ev.resumed(42, "com.whatsapp"));
        assertEquals("ACTIVITY_RESUMED\u001f42\u001fcom.whatsapp\u001fcom.whatsapp.Main", usage.naturalKey());
        assertEquals("r1\u001f1", accepted(Ev.posted(1, "p", "r1")).naturalKey());
        assertEquals("r1", accepted(Ev.removed(1, "p", "r1", "click")).naturalKey());
        assertNull(accepted(Ev.activity(Ev.id(), 1, 2, 1)).naturalKey());
        assertNull(accepted(Ev.shorts(1, "enter", "v")).naturalKey());
    }

    @Test
    void contractFieldsAreKnownPerType() {
        assertTrue(ContractFilter.fieldsOf("browser.site").contains("title"));
        assertTrue(ContractFilter.fieldsOf("android.location").isEmpty());
        assertEquals("extension_brave", ContractFilter.sensorOf("browser.site",
                (ObjectNode) Ev.JSON.createObjectNode().put("browser", "brave")));
    }

    @Test
    void pausesKeepTheirChoiceAndNeverTheMessage() {
        ObjectNode pause = Ev.pause(1, "com.google.android.youtube", "alternative");
        pause.put("block", "b".repeat(80));
        pause.put("alternative", "com.amazon.kindle");
        pause.put("message", "Before bed: Kindle");
        ContractFilter.Accepted a = accepted(pause);
        assertEquals(1, a.dropped());
        assertFalse(a.payload().has("message"));
        assertEquals(60, a.payload().get("block").asText().length());
        assertEquals("com.amazon.kindle", a.payload().get("alternative").asText());
        assertNull(a.naturalKey());
        assertEquals("launcher", ContractFilter.sensorOf("android.pause", a.payload()));
        assertEquals("invalid_enum", rejected(Ev.pause(1, "com.whatsapp", "snoozed")));
        ObjectNode noPackage = Ev.pause(1, "com.whatsapp", "opened");
        noPackage.remove("package");
        assertEquals("missing_field", rejected(noPackage));
    }
}
