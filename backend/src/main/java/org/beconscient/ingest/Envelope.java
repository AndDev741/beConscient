package org.beconscient.ingest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.beconscient.crypto.Bytes;
import org.beconscient.crypto.Hpke;

/** The JSON wrapping of envelope.md: {v, kid, enc, ct} in, {v, nonce, ct} out. */
public final class Envelope {

    private static final ObjectMapper JSON = new ObjectMapper();

    public record Request(String kid, byte[] enc, byte[] ct) {
    }

    /** Anything that is not a well-formed v1 envelope. */
    public static final class Malformed extends Exception {
        public Malformed() {
            super("bad envelope");
        }
    }

    private Envelope() {
    }

    public static Request parse(byte[] body) throws Malformed {
        try {
            JsonNode root = JSON.readTree(body);
            if (root == null || !root.isObject() || root.path("v").asInt(-1) != 1 || !root.path("v").isIntegralNumber()) {
                throw new Malformed();
            }
            String kid = root.path("kid").isTextual() ? root.get("kid").asText() : null;
            if (kid == null || kid.isEmpty() || kid.length() > 64) {
                throw new Malformed();
            }
            if (!root.path("enc").isTextual() || !root.path("ct").isTextual()) {
                throw new Malformed();
            }
            return new Request(kid, Bytes.fromB64url(root.get("enc").asText()), Bytes.fromB64url(root.get("ct").asText()));
        } catch (Malformed e) {
            throw e;
        } catch (Exception e) {
            throw new Malformed();
        }
    }

    /** Encrypts a response plaintext under the exported key. */
    public static String seal(byte[] responseKey, byte[] plaintext) {
        Hpke.Box box = Hpke.sealResponse(responseKey, plaintext);
        ObjectNode out = JsonNodeFactory.instance.objectNode();
        out.put("v", 1);
        out.put("nonce", Bytes.b64url(box.nonce()));
        out.put("ct", Bytes.b64url(box.ct()));
        return out.toString();
    }

    public static String error(String code) {
        return "{\"error\":\"" + code + "\"}";
    }
}
