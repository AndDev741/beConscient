package org.beconscient.auth;

import io.smallrye.config.PropertiesConfigSource;
import io.smallrye.config.SmallRyeConfig;
import io.smallrye.config.SmallRyeConfigBuilder;
import org.beconscient.crypto.Bytes;
import org.beconscient.crypto.Hpke;
import org.beconscient.crypto.HpkeKey;
import org.beconscient.crypto.KeyRing;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Production fails closed: no secrets, or development ones, and it does not start. */
class ProdSecretsGuardTest {

    static final String GOOD_SESSION = "f3Kq9wZt7Lr2Vb8Nx4Hc6Jd1Pm5Gs0Ye";
    static final String TEST_KEYS = "[{\"kid\":\"test-0001\",\"private_key\":\"nYNDYllPBHkfmKXb0PQuChcVm_u_GLtBh8iES4Y23Ls\"}]";

    static String prodKey(String kid) {
        byte[] sk = new byte[32];
        new SecureRandom().nextBytes(sk);
        return "[{\"kid\":\"" + kid + "\",\"private_key\":\"" + Bytes.b64url(sk) + "\"}]";
    }

    static SmallRyeConfig config(Map<String, String> values) {
        return new SmallRyeConfigBuilder().withSources(new PropertiesConfigSource(values, "test", 500)).build();
    }

    static Map<String, String> good() {
        Map<String, String> m = new HashMap<>();
        m.put("quarkus.http.auth.session.encryption-key", GOOD_SESSION);
        m.put("beconscient.owner-password", "a long and private passphrase");
        m.put("beconscient.hpke.keys", prodKey("prod-2026-10"));
        return m;
    }

    @Test
    void theProdProfileRefusesToStartWithoutSecrets() {
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> ProdSecretsGuard.check(List.of("prod"), config(Map.of())));
        assertTrue(e.getMessage().contains("BECONSCIENT_SESSION_KEY is not set"));
        assertTrue(e.getMessage().contains("BECONSCIENT_OWNER_PASSWORD is not set"));
        assertTrue(e.getMessage().contains("BECONSCIENT_HPKE_KEYS is not set"));
        // Any profile other than dev and test is held to the same rule
        assertThrows(IllegalStateException.class, () -> ProdSecretsGuard.check(List.of("staging"), config(Map.of())));
    }

    @Test
    void theProdProfileStartsWithRealSecrets() {
        assertDoesNotThrow(() -> ProdSecretsGuard.check(List.of("prod"), config(good())));
        assertDoesNotThrow(() -> ProdSecretsGuard.check(List.of("test"), config(Map.of())), "test has its own defaults");
    }

    @Test
    void knownDevelopmentValuesAreRefused() {
        Map<String, String> m = good();
        m.put("quarkus.http.auth.session.encryption-key", "dev-only-session-key-0123456789abcdef");
        m.put("beconscient.owner-password", "beconscient-dev");
        m.put("beconscient.hpke.keys", TEST_KEYS);
        String msg = assertThrows(IllegalStateException.class, () -> ProdSecretsGuard.check(List.of("prod"), config(m))).getMessage();
        assertTrue(msg.contains("BECONSCIENT_SESSION_KEY is a known development value"), msg);
        assertTrue(msg.contains("BECONSCIENT_OWNER_PASSWORD is a known development value"), msg);
        assertTrue(msg.contains("contains the test key"), msg);
        // The message names settings, never their values
        assertFalse(msg.contains("nYNDYll"));
        assertFalse(msg.contains("0123456789abcdef"));
    }

    @Test
    void theTestKeyIsRefusedUnderAnyKidOrFlag() {
        String renamed = "[{\"kid\":\"prod-2026-10\",\"private_key\":\"nYNDYllPBHkfmKXb0PQuChcVm_u_GLtBh8iES4Y23Ls\"}]";
        assertEquals(1, ProdSecretsGuard.problems(Optional.of(GOOD_SESSION), Optional.of("pw-123456"), Optional.of(renamed),
                Optional.empty()).size());
        String flagged = prodKey("prod-x").replace("}]", ",\"test_only\":true}]");
        assertEquals(1, ProdSecretsGuard.problems(Optional.of(GOOD_SESSION), Optional.of("pw-123456"), Optional.of(flagged),
                Optional.empty()).size());
        String kid = prodKey("test-0001");
        assertEquals(1, ProdSecretsGuard.problems(Optional.of(GOOD_SESSION), Optional.of("pw-123456"), Optional.of(kid),
                Optional.empty()).size());
    }

    @Test
    void aShortSessionKeyOrGarbageKeysAreRefused() {
        List<String> p = ProdSecretsGuard.problems(Optional.of("short"), Optional.of("pw-123456"), Optional.of("{not json"),
                Optional.empty());
        assertEquals(2, p.size(), p.toString());
    }

    @Test
    void keyFilesCountAsKeys(@org.junit.jupiter.api.io.TempDir Path dir) throws Exception {
        Path f = dir.resolve("prod.private.json");
        Files.writeString(f, prodKey("prod-2026-10").replace("[", "").replace("]", ""));
        assertTrue(ProdSecretsGuard.problems(Optional.of(GOOD_SESSION), Optional.of("pw-123456"), Optional.empty(),
                Optional.of(List.of(f.toString()))).isEmpty());
    }

    @Test
    void aProdLikeKeyRingRefusesTheTestKidEnvelope() throws Exception {
        HpkeKey test = KeyRing.parse(Files.readString(Path.of("../contract/keys/test-keypair.json"))).get(0);
        HpkeKey prod = KeyRing.parse(prodKey("prod-2026-10")).get(0);
        KeyRing prodRing = KeyRing.of(List.of(test, prod), false);
        assertTrue(prodRing.find("test-0001", Instant.now()).isEmpty());
        assertTrue(prodRing.find("prod-2026-10", Instant.now()).isPresent());
        KeyRing devRing = KeyRing.of(List.of(test), true);
        assertTrue(devRing.find("test-0001", Instant.now()).isPresent());
        assertFalse(KeyRing.allowsTestKeys(List.of("prod")));
    }

    @Test
    void twoLoadedKeysOpenTheirEnvelopesAndARetiredKeyIsRefusedAfterTheCut() throws Exception {
        Instant cut = Instant.parse("2026-11-01T00:00:00Z");
        HpkeKey old = KeyRing.parse(prodKey("prod-2026-10").replace("}]", ",\"retire_at\":\"" + cut + "\"}]")).get(0);
        HpkeKey fresh = KeyRing.parse(prodKey("prod-2026-11")).get(0);
        KeyRing ring = KeyRing.of(List.of(old, fresh), false);
        byte[] msg = "{}".getBytes(StandardCharsets.UTF_8);
        for (HpkeKey k : List.of(old, fresh)) {
            Hpke.Sealed s = Hpke.seal(k.publicKey(), k.kid(), Hpke.Purpose.BATCH, msg);
            HpkeKey found = ring.find(k.kid(), cut.minusSeconds(1)).orElseThrow();
            assertEquals("{}", new String(Hpke.open(found.privateKey(), k.kid(), Hpke.Purpose.BATCH, s.enc(), s.ct()).plaintext(),
                    StandardCharsets.UTF_8));
        }
        assertTrue(ring.find("prod-2026-10", cut).isEmpty(), "retired");
        assertTrue(ring.find("prod-2026-11", cut).isPresent());
        // An envelope sealed to one key never opens with the other
        Hpke.Sealed s = Hpke.seal(old.publicKey(), "prod-2026-11", Hpke.Purpose.BATCH, msg);
        assertThrows(Hpke.OpenFailed.class, () -> Hpke.open(fresh.privateKey(), "prod-2026-11", Hpke.Purpose.BATCH, s.enc(), s.ct()));
    }
}
