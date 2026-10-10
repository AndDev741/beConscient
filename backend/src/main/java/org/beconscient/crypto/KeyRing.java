package org.beconscient.crypto;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.runtime.configuration.ConfigUtils;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The server keys this profile accepts, by kid.
 *
 * Keys come from {@code beconscient.hpke.keys} (env BECONSCIENT_HPKE_KEYS, a JSON
 * array of {"kid","private_key"} objects, optionally with "test_only" and
 * "retire_at") and from {@code beconscient.hpke.key-files} (files in the format
 * tools/cmd/keygen writes; "classpath:" reads from the classpath).
 * {@code beconscient.hpke.accepted-kids} narrows the ring further.
 *
 * Outside dev and test the ring never holds a test key, whatever the
 * configuration says; ProdSecretsGuard also refuses to start in that case.
 */
@ApplicationScoped
public class KeyRing {

    private static final Logger LOG = Logger.getLogger(KeyRing.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    @ConfigProperty(name = "beconscient.hpke.keys")
    Optional<String> keysJson;

    @ConfigProperty(name = "beconscient.hpke.key-files")
    Optional<List<String>> keyFiles;

    @ConfigProperty(name = "beconscient.hpke.accepted-kids")
    Optional<List<String>> acceptedKids;

    private Map<String, HpkeKey> keys = Map.of();

    KeyRing() {
    }

    /** A ring outside CDI, for tests of a production-like profile. */
    public static KeyRing of(List<HpkeKey> keys, boolean allowTestKeys) {
        KeyRing ring = new KeyRing();
        ring.keys = index(keys, allowTestKeys, Optional.empty());
        return ring;
    }

    @PostConstruct
    void init() {
        boolean allowTest = allowsTestKeys(ConfigUtils.getProfiles());
        List<HpkeKey> loaded = new ArrayList<>();
        keysJson.filter(s -> !s.isBlank()).ifPresent(json -> loaded.addAll(parse(json)));
        keyFiles.ifPresent(files -> files.forEach(f -> loaded.addAll(parse(read(f)))));
        keys = index(loaded, allowTest, acceptedKids);
        LOG.infof("HPKE key ring: %s", keys.keySet());
    }

    /** Test keys are only ever accepted in the dev and test profiles. */
    public static boolean allowsTestKeys(List<String> profiles) {
        return profiles.contains("dev") || profiles.contains("test");
    }

    private static Map<String, HpkeKey> index(List<HpkeKey> loaded, boolean allowTest, Optional<List<String>> accepted) {
        Map<String, HpkeKey> out = new LinkedHashMap<>();
        for (HpkeKey k : loaded) {
            if (k.isTestKey() && !allowTest) {
                LOG.errorf("Refusing test key %s outside dev and test", k.kid());
                continue;
            }
            if (accepted.isPresent() && !accepted.get().contains(k.kid())) {
                continue;
            }
            out.put(k.kid(), k);
        }
        return Map.copyOf(out);
    }

    /** The key for an envelope's kid, if this profile accepts it right now. */
    public Optional<HpkeKey> find(String kid, Instant now) {
        HpkeKey key = kid == null ? null : keys.get(kid);
        return key != null && key.acceptsAt(now) ? Optional.of(key) : Optional.empty();
    }

    public List<HpkeKey> all() {
        return List.copyOf(keys.values());
    }

    /**
     * Parses one key object or an array of them. Each needs "kid" and
     * "private_key" (base64url, 32 bytes).
     */
    public static List<HpkeKey> parse(String json) {
        try {
            JsonNode root = JSON.readTree(json);
            List<HpkeKey> out = new ArrayList<>();
            if (root.isArray()) {
                for (JsonNode n : root) {
                    out.add(one(n));
                }
            } else if (root.isObject()) {
                out.add(one(root));
            } else {
                throw new IllegalArgumentException("HPKE keys must be a JSON object or array");
            }
            return out;
        } catch (IOException e) {
            throw new IllegalArgumentException("HPKE keys are not valid JSON", e);
        }
    }

    private static HpkeKey one(JsonNode n) {
        String kid = n.path("kid").asText("");
        String priv = n.path("private_key").asText("");
        if (kid.isBlank() || priv.isBlank()) {
            throw new IllegalArgumentException("an HPKE key needs kid and private_key");
        }
        byte[] sk = Bytes.fromB64url(priv);
        if (sk.length != 32) {
            throw new IllegalArgumentException("HPKE key " + kid + " is not a 32-byte X25519 key");
        }
        Instant retire = n.hasNonNull("retire_at") ? Instant.parse(n.get("retire_at").asText()) : null;
        return HpkeKey.of(kid, sk, n.path("test_only").asBoolean(false), retire);
    }

    private static String read(String location) {
        try {
            if (location.startsWith("classpath:")) {
                String name = location.substring("classpath:".length());
                try (InputStream in = Thread.currentThread().getContextClassLoader().getResourceAsStream(name)) {
                    if (in == null) {
                        throw new IllegalArgumentException("no classpath resource " + name);
                    }
                    return new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                }
            }
            return Files.readString(Path.of(location));
        } catch (IOException e) {
            throw new IllegalArgumentException("cannot read HPKE key file " + location, e);
        }
    }
}
