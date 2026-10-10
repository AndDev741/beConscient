package org.beconscient.auth;

import io.quarkus.elytron.security.common.BcryptUtil;
import io.quarkus.runtime.StartupEvent;
import jakarta.annotation.Priority;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Singleton;
import jakarta.transaction.Transactional;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.util.Optional;

/**
 * Creates the owner account on first start from BECONSCIENT_OWNER_PASSWORD, so no
 * password or hash is ever committed. Runs after ProdSecretsGuard.
 */
@Singleton
public class UserSeeder {

    private static final Logger LOG = Logger.getLogger(UserSeeder.class);

    @ConfigProperty(name = "beconscient.owner")
    String owner;

    @ConfigProperty(name = "beconscient.owner-password")
    Optional<String> initialPassword;

    @Transactional
    void onStart(@Observes @Priority(10) StartupEvent event) {
        if (AppUser.count("username", owner) > 0) {
            return;
        }
        String password = initialPassword.filter(p -> !p.isBlank()).orElse(null);
        if (password == null) {
            LOG.warnf("No account for '%s' and BECONSCIENT_OWNER_PASSWORD is not set, so nobody can sign in.", owner);
            return;
        }
        AppUser user = new AppUser();
        user.username = owner;
        user.password = BcryptUtil.bcryptHash(password);
        user.role = "owner";
        user.persist();
        LOG.infof("Created the '%s' account from BECONSCIENT_OWNER_PASSWORD.", owner);
    }
}
