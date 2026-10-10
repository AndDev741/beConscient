package org.beconscient.auth;

import io.quarkus.hibernate.orm.panache.PanacheEntity;
import io.quarkus.security.jpa.Password;
import io.quarkus.security.jpa.Roles;
import io.quarkus.security.jpa.UserDefinition;
import io.quarkus.security.jpa.Username;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

/** Who may sign in: the owner, with a bcrypt hash. */
@Entity
@Table(name = "app_users")
@UserDefinition
public class AppUser extends PanacheEntity {

    @Username
    @Column(nullable = false, unique = true, length = 64)
    public String username;

    /** bcrypt in Modular Crypt Format, written by BcryptUtil. */
    @Password
    @Column(nullable = false)
    public String password;

    @Roles
    @Column(nullable = false, length = 64)
    public String role;
}
