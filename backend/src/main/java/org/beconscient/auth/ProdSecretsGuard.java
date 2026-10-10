package org.beconscient.auth;

import io.quarkus.runtime.StartupEvent;
import io.quarkus.runtime.configuration.ConfigUtils;
import jakarta.annotation.Priority;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Singleton;
import org.beconscient.crypto.HpkeKey;
import org.beconscient.crypto.KeyRing;
import org.eclipse.microprofile.config.Config;
import org.eclipse.microprofile.config.ConfigProvider;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Fails closed: outside the dev and test profiles the server refuses to start
 * when the session key, the owner password or the HPKE keys are missing, or when
 * any of them is a known development value (including the public test key pair).
 * Messages name the setting, never its value.
 */
@Singleton
public class ProdSecretsGuard {

    private static final Logger LOG = Logger.getLogger(ProdSecretsGuard.class);

    /** Development and well-known values; never acceptable in production. */
    static final Set<String> KNOWN_SESSION_KEYS = Set.of(
            "dev-only-session-key-0123456789abcdef",
            "a-development-only-key-at-least-sixteen-chars");
    static final Set<String> KNOWN_PASSWORDS = Set.of(
            "beconscient-dev", "owner", "password", "changeme", "admin");
    static final int MIN_SESSION_KEY_LENGTH = 32;

    void onStart(@Observes @Priority(1) StartupEvent event) {
        check(ConfigUtils.getProfiles(), ConfigProvider.getConfig());
    }

    /** Every profile except dev and test must carry real secrets; anything else stops the start. */
    public static void check(List<String> profiles, Config config) {
        if (KeyRing.allowsTestKeys(profiles)) {
            return;
        }
        enforce(problems(
                config.getOptionalValue("quarkus.http.auth.session.encryption-key", String.class),
                config.getOptionalValue("beconscient.owner-password", String.class),
                config.getOptionalValue("beconscient.hpke.keys", String.class),
                config.getOptionalValues("beconscient.hpke.key-files", String.class)));
    }

    /** Throws when there is any problem; the message lists every one of them. */
    static void enforce(List<String> problems) {
        if (!problems.isEmpty()) {
            problems.forEach(p -> LOG.error("Refusing to start: " + p));
            throw new IllegalStateException("Refusing to start without production secrets: " + String.join("; ", problems));
        }
    }

    /** Pure check of the production secrets, so it can be tested without starting a server. */
    public static List<String> problems(Optional<String> sessionKey, Optional<String> ownerPassword,
                                        Optional<String> hpkeKeys, Optional<List<String>> hpkeKeyFiles) {
        List<String> out = new ArrayList<>();

        String key = sessionKey.map(String::trim).orElse("");
        if (key.isEmpty()) {
            out.add("BECONSCIENT_SESSION_KEY is not set");
        } else if (KNOWN_SESSION_KEYS.contains(key)) {
            out.add("BECONSCIENT_SESSION_KEY is a known development value");
        } else if (key.length() < MIN_SESSION_KEY_LENGTH) {
            out.add("BECONSCIENT_SESSION_KEY must have at least " + MIN_SESSION_KEY_LENGTH + " characters");
        }

        String password = ownerPassword.map(String::trim).orElse("");
        if (password.isEmpty()) {
            out.add("BECONSCIENT_OWNER_PASSWORD is not set");
        } else if (KNOWN_PASSWORDS.contains(password.toLowerCase())) {
            out.add("BECONSCIENT_OWNER_PASSWORD is a known development value");
        }

        List<HpkeKey> keys = new ArrayList<>();
        boolean unreadable = false;
        try {
            hpkeKeys.filter(s -> !s.isBlank()).ifPresent(json -> keys.addAll(KeyRing.parse(json)));
        } catch (RuntimeException e) {
            unreadable = true;
            out.add("BECONSCIENT_HPKE_KEYS is not a valid key list");
        }
        if (hpkeKeyFiles.isPresent() && !hpkeKeyFiles.get().isEmpty()) {
            for (String file : hpkeKeyFiles.get()) {
                try {
                    keys.addAll(KeyRing.parse(java.nio.file.Files.readString(java.nio.file.Path.of(file))));
                } catch (Exception e) {
                    unreadable = true;
                    out.add("HPKE key file " + file + " cannot be read");
                }
            }
        }
        if (keys.isEmpty() && !unreadable) {
            out.add("BECONSCIENT_HPKE_KEYS is not set");
        }
        for (HpkeKey k : keys) {
            if (k.isTestKey()) {
                out.add("BECONSCIENT_HPKE_KEYS contains the test key (" + k.kid() + ")");
            }
        }
        return out;
    }
}
