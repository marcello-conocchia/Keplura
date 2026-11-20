package com.keplura.server.oidc;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/** Spring Data repository for {@link OidcAccountLink} (ADR-0009 §5). */
public interface OidcAccountLinkRepository extends JpaRepository<OidcAccountLink, Long> {

    Optional<OidcAccountLink> findByProviderIdAndSubject(Long providerId, String subject);

    List<OidcAccountLink> findByProviderId(Long providerId);

    Optional<OidcAccountLink> findByProviderIdAndUserId(Long providerId, Long userId);
}
