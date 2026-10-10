package org.beconscient.derive;

import com.fasterxml.jackson.databind.JsonNode;

/** A stored raw event as the derivation reads it. {@code end} and {@code rev} are null for point events. */
public record EventRow(long id, String type, long ts, Long end, Integer rev, JsonNode payload) {

    public String str(String field) {
        JsonNode v = payload == null ? null : payload.get(field);
        return v == null || v.isNull() ? null : v.asText();
    }

    public boolean flag(String field) {
        return payload != null && payload.path(field).asBoolean(false);
    }
}
