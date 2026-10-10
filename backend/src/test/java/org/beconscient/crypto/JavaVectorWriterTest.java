package org.beconscient.crypto;

import com.fasterxml.jackson.core.util.DefaultIndenter;
import com.fasterxml.jackson.core.util.DefaultPrettyPrinter;
import com.fasterxml.jackson.core.util.Separators;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.beconscient.device.Fingerprint;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

/**
 * Writes contract/vectors/java-backend.json in the format of tools/cmd/vectorgen,
 * so the other implementations can check that they open what Java seals. Run with
 * {@code ./mvnw test -Dtest=JavaVectorWriterTest -Dbeconscient.writeVectors=true};
 * VectorsTest and {@code go run ./cmd/vectorcheck} then verify it.
 *
 * The pairing response carries the real fingerprint of the sample installation,
 * so collectors can check their fingerprint formula against it.
 */
class JavaVectorWriterTest {

    static final ObjectMapper JSON = new ObjectMapper();
    static final String SECRET = "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8";
    static final String INSTALLATION = "0192f3a0-1c2b-7cde-8f00-112233445566";

    @Test
    @EnabledIfSystemProperty(named = "beconscient.writeVectors", matches = "true")
    void writeJavaVectors() throws Exception {
        HpkeKey key = VectorsTest.testKey();
        String batch = "{\"header\":{\"installation_id\":\"" + INSTALLATION + "\",\"credential\":\"" + SECRET
                + "\",\"seq\":1,\"sent_at\":1760090000123,\"tz\":\"Europe/Lisbon\",\"tz_offset_min\":60,"
                + "\"contract_version\":1,\"client_version\":\"0.0.0-test\"},\"events\":[{\"id\":"
                + "\"0192f3a1-0000-7000-8000-000000000001\",\"type\":\"android.usage\",\"at\":1760089000000,"
                + "\"kind\":\"ACTIVITY_RESUMED\",\"package\":\"com.example.app\",\"class\":\"com.example.app.MainActivity\"}]}";
        String receipt = "{\"installation_id\":\"" + INSTALLATION + "\",\"seq\":1,\"results\":[{\"id\":"
                + "\"0192f3a1-0000-7000-8000-000000000001\",\"status\":\"accepted\"}]}";
        String pair = "{\"code\":\"7KQ2-M9XD\",\"installation_id\":\"" + INSTALLATION + "\",\"secret\":\"" + SECRET
                + "\",\"kind\":\"desktop\",\"name\":\"Test desktop\",\"client_version\":\"0.0.0-test\"}";
        String fingerprint = Fingerprint.of(UUID.fromString(INSTALLATION), Bytes.sha256(Bytes.fromB64url(SECRET)));
        String pairResponse = "{\"device_id\":\"0192f3a0-0000-7000-8000-0000000000aa\",\"device_name\":\"Test desktop\","
                + "\"fingerprint\":\"" + fingerprint + "\"}";

        ArrayNode out = JSON.createArrayNode();
        out.add(vector(key, Hpke.Purpose.BATCH, batch, receipt));
        out.add(vector(key, Hpke.Purpose.PAIR, pair, pairResponse));

        DefaultIndenter indenter = new DefaultIndenter("  ", "\n");
        DefaultPrettyPrinter printer = new DefaultPrettyPrinter()
                .withSeparators(Separators.createDefaultInstance().withObjectFieldValueSpacing(Separators.Spacing.AFTER));
        printer.indentArraysWith(indenter);
        printer.indentObjectsWith(indenter);
        String json = JSON.writer(printer).writeValueAsString(out) + "\n";
        Files.writeString(VectorsTest.contract().resolve("vectors/java-backend.json"), json, StandardCharsets.UTF_8);
    }

    static ObjectNode vector(HpkeKey key, Hpke.Purpose purpose, String plaintext, String response) throws Exception {
        Hpke.Sealed sealed = Hpke.seal(key.publicKey(), key.kid(), purpose, plaintext.getBytes(StandardCharsets.UTF_8));
        Hpke.Box box = Hpke.sealResponse(sealed.responseKey(), response.getBytes(StandardCharsets.UTF_8));
        // Self-check before writing
        Hpke.Opened opened = Hpke.open(key.privateKey(), key.kid(), purpose, sealed.enc(), sealed.ct());
        if (!plaintext.equals(new String(opened.plaintext(), StandardCharsets.UTF_8))) {
            throw new IllegalStateException("round trip failed");
        }
        ObjectNode v = JSON.createObjectNode();
        v.put("producer", "java-backend");
        v.put("kid", key.kid());
        v.put("purpose", purpose.name().toLowerCase());
        v.put("info", purpose.info);
        v.put("plaintext", plaintext);
        ObjectNode req = v.putObject("request");
        req.put("v", 1);
        req.put("kid", key.kid());
        req.put("enc", Bytes.b64url(sealed.enc()));
        req.put("ct", Bytes.b64url(sealed.ct()));
        v.put("response_key", Bytes.b64url(sealed.responseKey()));
        v.put("response_plaintext", response);
        ObjectNode resp = v.putObject("response");
        resp.put("v", 1);
        resp.put("ct", Bytes.b64url(box.ct()));
        resp.put("nonce", Bytes.b64url(box.nonce()));
        return v;
    }
}
