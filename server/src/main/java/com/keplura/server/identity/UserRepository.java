package com.keplura.server.identity;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * Spring Data repository for the local {@link User} entity (M2a).
 *
 * <p>Email lookups expect the stored form: callers MUST pass
 * {@link User#normalizeEmail} (as the M2b login service does), because the
 * column stores only that canonical form.
 */
public interface UserRepository extends JpaRepository<User, Long> {

    /** @param email already normalised via {@link User#normalizeEmail} */
    Optional<User> findByEmail(String email);

    /** Admin-Suche für den Rechte-Nachweis. */
    List<User> findTop20ByEmailContainingIgnoreCaseOrDisplayNameContainingIgnoreCaseOrderByDisplayNameAsc(
            String email, String displayName);

    boolean existsByEmail(String email);
}
