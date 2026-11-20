package com.keplura.server.auth;

import com.keplura.server.identity.PasswordHasher;
import com.keplura.server.identity.User;
import com.keplura.server.identity.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.UUID;

/**
 * Credential check for the M2b login endpoint (docs/adr/0002, docs/adr/0003).
 * Returns an {@link AuthPrincipal} on success and empty on any failure; the
 * caller never learns whether the email is unknown or the password was wrong.
 *
 * <p>Anti user-enumeration: when no user matches, the same Argon2 verification
 * work is performed against a per-instance dummy hash, so the unknown-email
 * path has the same cost profile (dominated by hashing) and — together with
 * the single {@code invalid_credentials} response the controller returns —
 * cannot be distinguished from a wrong password. Residual timing differences
 * (one extra DB lookup) are milliseconds against ~100ms+ Argon2 and accepted
 * for the MVP; logging is intentionally identical for both failure branches
 * so it cannot expose the difference to clients either.
 */
@Component
public class AuthService {

    /** HttpSession attribute name holding the {@link AuthPrincipal} of a logged-in user. */
    public static final String SESSION_PRINCIPAL_ATTR = "keplura.auth.principal";

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    private final UserRepository userRepository;
    private final PasswordHasher passwordHasher;

    /**
     * Random one-time password hashed at startup; never stored anywhere, never
     * matches a real credential. Keeps the unknown-email branch doing the same
     * Argon2 work as the known-email branch.
     */
    private final String dummyHash;

    public AuthService(UserRepository userRepository, PasswordHasher passwordHasher) {
        this.userRepository = userRepository;
        this.passwordHasher = passwordHasher;
        this.dummyHash = passwordHasher.hash("keplura-m2b-dummy-" + UUID.randomUUID());
    }

    /**
     * Verifies email/password. On failure both branches log the exact same
     * line (no PII per the logging convention in docs/development.md) and
     * return empty; the controller turns empty into a single indistinguishable 401.
     */
    /** PILOT 4e: stores a new Argon2 hash and clears the start-password flag. */
    @org.springframework.transaction.annotation.Transactional
    public void changePassword(User user, String newPassword) {
        user.changePassword(passwordHasher.hash(newPassword));
        userRepository.saveAndFlush(user);
        log.info("password changed (user id {})", user.getId());
    }

    /** PILOT 4e: session flag — set at login while a start password is active. */
    public static final String SESSION_MUST_CHANGE_PASSWORD_ATTR = "keplura.mustChangePassword";

    public Optional<AuthPrincipal> authenticate(String email, String password) {
        String normalized = User.normalizeEmail(email);
        Optional<User> found = userRepository.findByEmail(normalized);

        if (found.isEmpty()) {
            passwordHasher.verify(password, dummyHash);
            log.info("authentication rejected (invalid credentials)");
            return Optional.empty();
        }

        User user = found.get();
        if (!passwordHasher.verify(password, user.getPasswordHash())) {
            log.info("authentication rejected (invalid credentials)");
            return Optional.empty();
        }

        return Optional.of(new AuthPrincipal(user.getId(), user.getDisplayName()));
    }
}
