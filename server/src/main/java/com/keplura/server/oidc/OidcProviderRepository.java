package com.keplura.server.oidc;

import org.springframework.data.jpa.repository.JpaRepository;

/** Spring Data repository for {@link OidcProvider} (ADR-0009 §2). */
public interface OidcProviderRepository extends JpaRepository<OidcProvider, Long> {

    boolean existsByNameIgnoreCase(String name);

    /** The ONE active provider (DB-enforced uniqueness of active_slot). */
    java.util.Optional<OidcProvider> findFirstByActiveTrue();

    /** True when the login screen may show the OIDC button at all. */
    boolean existsByActiveTrue();
}
