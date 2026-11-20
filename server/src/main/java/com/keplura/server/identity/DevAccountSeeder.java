package com.keplura.server.identity;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Optional dev-profile account seed (M2d) so the desktop login flow and the QA
 * gate can exercise a real session end-to-end. Registration is deliberately
 * NOT a product feature (ADR-0002 / M2b directive: invites are a later PO
 * topic), which leaves a manual dev with no way to get a first user — this
 * runner closes exactly that gap and nothing else:
 *
 * <ul>
 *   <li>Active ONLY under the {@code dev} profile AND only when BOTH
 *       {@code keplura.dev.seed.email} and {@code keplura.dev.seed.password}
 *       are set. Out of the box (no properties) it does nothing at all, so no
 *       known credential ever exists by default.</li>
 *   <li>Idempotent: an existing user with that email is left untouched
 *       (password is NOT reset — a seed must not be a backdoor on restart).</li>
 *   <li>The prod profile can never reach this code (both {@code @Profile} and
 *       the property gate), so packaged deployments have no seeding surface.</li>
 * </ul>
 */
@Component
@Profile("dev")
@org.springframework.core.annotation.Order(100) // runs before OidcDevSeeder (200)
@ConditionalOnProperty(prefix = "keplura.dev.seed", name = {"email", "password"})
public class DevAccountSeeder implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(DevAccountSeeder.class);

    private final UserRepository userRepository;
    private final PasswordHasher passwordHasher;
    private final String email;
    private final String password;
    private final String displayName;

    public DevAccountSeeder(UserRepository userRepository,
                            PasswordHasher passwordHasher,
                            @org.springframework.beans.factory.annotation.Value("${keplura.dev.seed.email}") String email,
                            @org.springframework.beans.factory.annotation.Value("${keplura.dev.seed.password}") String password,
                            @org.springframework.beans.factory.annotation.Value("${keplura.dev.seed.display-name:Dev User}") String displayName) {
        this.userRepository = userRepository;
        this.passwordHasher = passwordHasher;
        this.email = email;
        this.password = password;
        this.displayName = displayName;
    }

    @Override
    public void run(String... args) {
        String normalized = User.normalizeEmail(email);
        if (userRepository.existsByEmail(normalized)) {
            // No password overwrite: seeding existing accounts would be a
            // credential-reset backdoor.
            log.info("dev account seed skipped (user already exists)");
            return;
        }
        userRepository.saveAndFlush(User.create(normalized, passwordHasher.hash(password), displayName));
        // Log without echoing the address (docs/development.md logging convention).
        log.info("dev account seed created a login account");
    }
}
