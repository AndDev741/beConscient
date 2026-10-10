package org.beconscient.crypto;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.beconscient.ingest.Envelope;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every implementation's envelope vectors (contract/vectors/*.json) open here:
 * request with the test private key, the exported response key, and the response.
 */
class VectorsTest {

    static final ObjectMapper JSON = new ObjectMapper();

    static Path contract() {
        return Path.of(System.getProperty("beconscient.contract.dir", "../contract"));
    }

    static HpkeKey testKey() throws IOException {
        return KeyRing.parse(Files.readString(contract().resolve("keys/test-keypair.json"))).get(0);
    }

    static List<Path> vectorFiles() throws IOException {
        List<Path> out = new ArrayList<>();
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(contract().resolve("vectors"), "*.json")) {
            ds.forEach(out::add);
        }
        out.sort(null);
        return out;
    }

    @Test
    void theTestKeyPairIsConsistent() throws IOException {
        HpkeKey key = testKey();
        JsonNode pub = JSON.readTree(Files.readString(contract().resolve("keys/test-public.json")));
        assertEquals(pub.get("public_key").asText(), Bytes.b64url(key.publicKey()));
        assertTrue(key.isTestKey());
    }

    @Test
    void everyVectorFileOpens() throws Exception {
        HpkeKey key = testKey();
        List<Path> files = vectorFiles();
        assertTrue(files.stream().anyMatch(p -> p.getFileName().toString().equals("go-reference.json")),
                "the Go reference vectors must be present");
        int checked = 0;
        for (Path file : files) {
            for (JsonNode v : JSON.readTree(Files.readString(file))) {
                String what = file.getFileName() + " " + v.get("producer").asText() + "/" + v.get("purpose").asText();
                Hpke.Purpose purpose = Hpke.Purpose.valueOf(v.get("purpose").asText().toUpperCase());
                assertEquals(purpose.info, v.get("info").asText(), what);
                JsonNode req = v.get("request");
                assertEquals(key.kid(), req.get("kid").asText(), what);

                Envelope.Request parsed = Envelope.parse(JSON.writeValueAsBytes(req));
                Hpke.Opened opened = Hpke.open(key.privateKey(), parsed.kid(), purpose, parsed.enc(), parsed.ct());
                assertEquals(v.get("plaintext").asText(), new String(opened.plaintext(), StandardCharsets.UTF_8), what);
                assertEquals(v.get("response_key").asText(), Bytes.b64url(opened.responseKey()), what);

                JsonNode resp = v.get("response");
                byte[] pt = Hpke.openResponse(opened.responseKey(), Bytes.fromB64url(resp.get("nonce").asText()),
                        Bytes.fromB64url(resp.get("ct").asText()));
                assertEquals(v.get("response_plaintext").asText(), new String(pt, StandardCharsets.UTF_8), what);
                checked++;
            }
        }
        assertTrue(checked >= 2);
    }

    @Test
    void anEnvelopeForOnePurposeNeverOpensForTheOther() throws Exception {
        HpkeKey key = testKey();
        for (JsonNode v : JSON.readTree(Files.readString(contract().resolve("vectors/go-reference.json")))) {
            Hpke.Purpose purpose = Hpke.Purpose.valueOf(v.get("purpose").asText().toUpperCase());
            Hpke.Purpose other = purpose == Hpke.Purpose.PAIR ? Hpke.Purpose.BATCH : Hpke.Purpose.PAIR;
            Envelope.Request parsed = Envelope.parse(JSON.writeValueAsBytes(v.get("request")));
            assertThrows(Hpke.OpenFailed.class, () -> Hpke.open(key.privateKey(), parsed.kid(), other, parsed.enc(), parsed.ct()));
            // The kid is the aad: a relabelled envelope does not open either
            assertThrows(Hpke.OpenFailed.class, () -> Hpke.open(key.privateKey(), "prod-2026-10", purpose, parsed.enc(), parsed.ct()));
        }
    }

    @Test
    void sealAndOpenRoundTrip() throws Exception {
        HpkeKey key = testKey();
        byte[] msg = "{\"hello\":1}".getBytes(StandardCharsets.UTF_8);
        Hpke.Sealed sealed = Hpke.seal(key.publicKey(), key.kid(), Hpke.Purpose.BATCH, msg);
        Hpke.Opened opened = Hpke.open(key.privateKey(), key.kid(), Hpke.Purpose.BATCH, sealed.enc(), sealed.ct());
        assertArrayEquals(msg, opened.plaintext());
        assertArrayEquals(sealed.responseKey(), opened.responseKey());

        Hpke.Box box = Hpke.sealResponse(opened.responseKey(), msg);
        assertEquals(12, box.nonce().length);
        assertArrayEquals(msg, Hpke.openResponse(sealed.responseKey(), box.nonce(), box.ct()));
        byte[] tampered = box.ct().clone();
        tampered[0] ^= 1;
        assertThrows(Hpke.OpenFailed.class, () -> Hpke.openResponse(sealed.responseKey(), box.nonce(), tampered));
        // Two responses never share a nonce
        assertFalse(java.util.Arrays.equals(box.nonce(), Hpke.sealResponse(opened.responseKey(), msg).nonce()));
    }
}
