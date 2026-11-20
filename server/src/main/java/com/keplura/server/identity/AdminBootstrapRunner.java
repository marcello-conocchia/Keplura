package com.keplura.server.identity;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * ADMIN-KI bootstrap admin (docs/adr/0008 §1): the PO's decision is that the
 * first admin account stays a .env concern (no registration surface exists,
 * and someone must be able to open the admin panel on a fresh install).
 *
 * <p>Rules, mirroring the DevAccountSeeder pattern with one deliberate
 * difference:
 * <ul>
 *   <li>Active in EVERY profile (a prod install needs it — unlike the dev
 *       seeder's {@code @Profile("dev")}) but only when BOTH
 *       {@code keplura.admin.bootstrap.email} and
 *       {@code keplura.admin.bootstrap.password} are set. Out of the box
 *       (no properties) this does nothing at all: no known credential ever
 *       exists by default.</li>
 *   <li>Idempotent and non-destructive: an existing account with that email
 *       gets its ROLE raised to ADMIN and NOTHING else — the password is
 *       never reset (no restart backdoor), display name untouched.</li>
 *   <li>A NEW account is created with the given credential (hashed, never
 *       logged) and ADMIN role.</li>
 *   <li>Logs "bootstrap admin applied" only — never the address (logging
 *       convention, docs/development.md).</li>
 * </ul>
 *
 * <p>Ops note (documented in deploy/README.md with this feature): the
 * variables are meant for the FIRST boot of a fresh installation; keeping
 * them set forever is harmless (idempotent) but the password in .env stays
 * a live credential for any hand-built account of that email — the PO's
 * chosen trade-off for a single-machine product.
 */
@Component
public class AdminBootstrapRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(AdminBootstrapRunner.class);

    private final UserRepository userRepository;
    private final PasswordHasher passwordHasher;
    private final String email;
    private final String password;

    public AdminBootstrapRunner(UserRepository userRepository,
                                PasswordHasher passwordHasher,
                                @Value("${keplura.admin.bootstrap.email:}") String email,
                                @Value("${keplura.admin.bootstrap.password:}") String password) {
        this.userRepository = userRepository;
        this.passwordHasher = passwordHasher;
        this.email = email;
        this.password = password;
    }

    @Override
    @Transactional
    public void run(org.springframework.boot.ApplicationArguments args) {
        if (email == null || email.isBlank() || password == null || password.isEmpty()) {
            return; // no bootstrap configured — zero action, zero log noise
        }
        String normalized = User.normalizeEmail(email);
        var existing = userRepository.findByEmail(normalized);
        if (existing.isPresent()) {
            User user = existing.get();
            if (user.isAdmin()) {
                log.info("bootstrap admin already present (role unchanged)");
                return;
            }
            user.promoteToAdmin();
            userRepository.saveAndFlush(user);
            log.info("bootstrap admin role applied to an existing account");
            return;
        }
        // New account: full form via the factory, then the one deliberate
        // promotion verb (User has no general role setter).
        User created = User.create(normalized, passwordHasher.hash(password), "Administrator");
        created.promoteToAdmin();
        userRepository.saveAndFlush(created);
        log.info("bootstrap admin account created");
    }
}
