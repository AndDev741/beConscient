package org.beconscient.derive;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * What the phone looked like at an instant: screen, keyguard, the resumed
 * activities (most recent last), the launcher and whether a Shorts marker is
 * open. A block stores the state it ends in; the next block starts from it.
 */
public final class PhoneState {

    public enum Screen { ON, OFF, UNKNOWN }

    /** One resumed activity. {@code cls} and {@code instance} may be null. */
    public record Resumed(String pkg, String cls, Long instance, long since, Long eventId) {

        boolean sameActivity(String p, String c, Long i) {
            if (!pkg.equals(p)) {
                return false;
            }
            if (cls == null || c == null) {
                return true;
            }
            if (!cls.equals(c)) {
                return false;
            }
            return instance == null || i == null || instance.equals(i);
        }
    }

    public Screen screen = Screen.UNKNOWN;
    public boolean locked;
    public final List<Resumed> resumed = new ArrayList<>();
    public String home;
    public boolean shorts;

    public static PhoneState initial() {
        return new PhoneState();
    }

    public PhoneState copy() {
        PhoneState s = new PhoneState();
        s.screen = screen;
        s.locked = locked;
        s.resumed.addAll(resumed);
        s.home = home;
        s.shorts = shorts;
        return s;
    }

    /** Screen on and unlocked: the only state in which phone time counts. */
    public boolean active() {
        return screen == Screen.ON && !locked;
    }

    public Resumed top() {
        return resumed.isEmpty() ? null : resumed.get(resumed.size() - 1);
    }

    public ObjectNode toJson() {
        ObjectNode n = JsonNodeFactory.instance.objectNode();
        n.put("screen", screen.name());
        n.put("locked", locked);
        ArrayNode arr = n.putArray("resumed");
        for (Resumed r : resumed) {
            ObjectNode o = arr.addObject();
            o.put("pkg", r.pkg());
            if (r.cls() != null) {
                o.put("cls", r.cls());
            }
            if (r.instance() != null) {
                o.put("instance", r.instance());
            }
            o.put("since", r.since());
            if (r.eventId() != null) {
                o.put("eventId", r.eventId());
            }
        }
        if (home != null) {
            n.put("home", home);
        }
        n.put("shorts", shorts);
        return n;
    }

    public static PhoneState fromJson(JsonNode n) {
        PhoneState s = new PhoneState();
        if (n == null || !n.isObject()) {
            return s;
        }
        s.screen = Screen.valueOf(n.path("screen").asText("UNKNOWN"));
        s.locked = n.path("locked").asBoolean(false);
        for (JsonNode o : n.path("resumed")) {
            s.resumed.add(new Resumed(o.path("pkg").asText(),
                    o.hasNonNull("cls") ? o.get("cls").asText() : null,
                    o.hasNonNull("instance") ? o.get("instance").asLong() : null,
                    o.path("since").asLong(),
                    o.hasNonNull("eventId") ? o.get("eventId").asLong() : null));
        }
        s.home = n.hasNonNull("home") ? n.get("home").asText() : null;
        s.shorts = n.path("shorts").asBoolean(false);
        return s;
    }

    /** Equality of everything that can change the next block's segments. */
    public boolean sameAs(PhoneState o) {
        if (o == null || screen != o.screen || locked != o.locked || shorts != o.shorts || !Objects.equals(home, o.home)
                || resumed.size() != o.resumed.size()) {
            return false;
        }
        for (int i = 0; i < resumed.size(); i++) {
            Resumed a = resumed.get(i);
            Resumed b = o.resumed.get(i);
            if (!a.pkg().equals(b.pkg()) || !Objects.equals(a.cls(), b.cls()) || !Objects.equals(a.instance(), b.instance())) {
                return false;
            }
        }
        return true;
    }
}
