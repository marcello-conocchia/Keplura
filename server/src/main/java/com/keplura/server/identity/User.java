package com.keplura.server.identity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.Locale;

/**
 * Local identity record (M2a, docs/adr/0002). The password is stored only as an
 * Argon2 hash ({@link PasswordHasher}) — never in plaintext, and this entity
 * must never be serialised to an API response with {@code passwordHash} exposed.
 *
 * <p>No setters by design: a user is created fully formed via
 * {@link #create} and updated deliberately (e.g. a future password change will
 * re-hash). {@code createdAt} is set once on first persist.
 */
@Entity
@Table(name = "app_user")
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Login identity; compared/stored lower-cased (unique at DB level). */
    @Column(nullable = false, unique = true, length = 320)
    private String email;

    @Column(name = "password_hash", nullable = false, length = 200)
    private String passwordHash;

    @Column(name = "display_name", nullable = false, length = 200)
    private String displayName;

    /**
     * Global deployment role as the plain stored name (ADMIN-KI SCOPE A,
     * docs/adr/0008 §1; V9 CHECK keeps the domain to USER|ADMIN). Mapped as
     * a String, exposed as {@link UserRole} via {@link #role()} — so
     * ddl-auto=validate never leans on an enum mapping. Every consumer must
     * use {@link #role()}; the raw column is not the API.
     */
    @Column(name = "role", nullable = false, length = 20)
    private String role = UserRole.USER.name();

    /** PILOT 4e (V15): true while the account still carries an admin-issued start password. */
    @Column(name = "must_change_password", nullable = false)
    private boolean mustChangePassword = false;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected User() {
        // JPA proxy constructor
    }

    private User(String email, String passwordHash, String displayName) {
        this.email = email;
        this.passwordHash = passwordHash;
        this.displayName = displayName;
    }

    /**
     * @param passwordHash an Argon2 hash produced by {@link PasswordHasher#hash}
     *                     — raw passwords must never reach this constructor
     */
    public static User create(String email, String passwordHash, String displayName) {
        if (email == null || email.isBlank()) {
            throw new IllegalArgumentException("email must not be blank");
        }
        if (passwordHash == null || passwordHash.isBlank()) {
            throw new IllegalArgumentException("passwordHash must not be blank");
        }
        if (displayName == null || displayName.isBlank()) {
            throw new IllegalArgumentException("displayName must not be blank");
        }
        return new User(normalizeEmail(email), passwordHash, displayName.trim());
    }

    /** Canonical form of a login email; the unique key used by M2b login lookup. */
    public static String normalizeEmail(String email) {
        // Locale.ROOT: the default-locale fold is environment-sensitive (in tr-TR
        // "I" lowercases to "ı"), which would break the unique login key in M2b.
        return email.trim().toLowerCase(Locale.ROOT);
    }

    @PrePersist
    void stampCreatedAt() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }

    public Long getId() {
        return id;
    }

    public String getEmail() {
        return email;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public String getDisplayName() {
        return displayName;
    }

    /** Typed view of the stored global role; unknown stored values fail loudly. */
    public UserRole role() {
        return UserRole.fromStorage(role);
    }

    public boolean isAdmin() {
        return role() == UserRole.ADMIN;
    }

    /**
     * The ONLY way a user becomes ADMIN (bootstrap runner / AccountTool
     * precedent) — there is no general setter: demotion and promotion are
     * deliberate operations, never a side effect of an edit. Idempotent.
     */
    public void promoteToAdmin() {
        this.role = UserRole.ADMIN.name();
    }

    /** Explicit reverse of {@link #promoteToAdmin()} (AccountTool parity). */
    public void demoteToUser() {
        this.role = UserRole.USER.name();
    }

    public boolean isMustChangePassword() {
        return mustChangePassword;
    }

    /** Admin-issued start password (Argon2 hash); the user must replace it at first login. */
    public void issueStartPassword(String passwordHash) {
        this.passwordHash = passwordHash;
        this.mustChangePassword = true;
    }

    /** User-chosen password (Argon2 hash); clears the change requirement. */
    public void changePassword(String passwordHash) {
        this.passwordHash = passwordHash;
        this.mustChangePassword = false;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
