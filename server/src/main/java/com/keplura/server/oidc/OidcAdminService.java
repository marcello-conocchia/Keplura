package com.keplura.server.oidc;

import com.keplura.server.admin.AdminException;
import com.keplura.server.admin.SecretCipher;
import com.keplura.server.admin.SsrfGuard;
import com.keplura.server.identity.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * ADMIN-KI-shaped CRUD for the OIDC provider row (docs/adr/0009 §2): the
 * secret discipline is the AI-provider pattern verbatim — write-only
 * plaintext (encrypted via the shared {@link SecretCipher}), reads show only
 * {@code secretSet} + the constant mask, an empty patch field KEEPS the
 * stored secret, and {@code active=false} is the retirement verb (no delete:
 * links anchor to the row id).
 *
 * <p>Every create/patch/activate DIALS the discovery URL once (SSRF-guarded,
 * ADR-0009 §8) and refuses a row that could never serve a login — issuer
 * mismatch, unroutable target, malformed doc. Saving a working config is
 * the only way to get an active provider.
 *
 * <p>Activation is the two-phase active_slot dance (clear the old holder
 * and flush BEFORE writing the pending sentinel) so the UNIQUE guard never
 * sees two slots mid-flush; a concurrent activation loses on the constraint
 * and the transaction rolls back — at most one active provider, DB-proven.
 */
@Service
public class OidcAdminService {

    private static final Logger log = LoggerFactory.getLogger(OidcAdminService.class);

    /** DTO: never urls of IdP internals, never the cipher. URLs ARE shown
     *  to admins (they edit them) — same boundary as ai_provider.baseUrl. */
    public record ProviderDto(Long id, String name, String issuer, String discoveryUrl,
                              String clientId, boolean secretSet, String secretMasked,
                              int timeoutSeconds, boolean active,
                              String createdAt, String updatedAt) {
    }

    public record ProviderCreateRequest(String name, String issuer, String discoveryUrl,
                                        String clientId, String clientSecret,
                                        Integer timeoutSeconds, Boolean active) {
    }

    /** Patch: null field = unchanged; secret null/blank KEEPS the stored one. */
    public record ProviderPatchRequest(String name, String issuer, String discoveryUrl,
                                       String clientId, String clientSecret,
                                       Integer timeoutSeconds, Boolean active) {
    }

    /** Link view for the admin table (user label + subject, no PII beyond
     *  what the admin already assigned). */
    public record LinkDto(Long id, String subject, Long userId, String userEmail, String createdAt) {
    }

    private final OidcProviderRepository providers;
    private final OidcAccountLinkRepository links;
    private final UserRepository userRepository;
    private final SecretCipher secretCipher;
    private final SsrfGuard ssrfGuard;
    private final OidcDiscoveryClient discovery;

    public OidcAdminService(OidcProviderRepository providers,
                            OidcAccountLinkRepository links,
                            UserRepository userRepository,
                            SecretCipher secretCipher,
                            SsrfGuard ssrfGuard,
                            OidcDiscoveryClient discovery) {
        this.providers = providers;
        this.links = links;
        this.userRepository = userRepository;
        this.secretCipher = secretCipher;
        this.ssrfGuard = ssrfGuard;
        this.discovery = discovery;
    }

    @Transactional(readOnly = true)
    public List<ProviderDto> list() {
        return providers.findAll().stream().map(OidcAdminService::toDto).toList();
    }

    @Transactional
    public ProviderDto create(ProviderCreateRequest req) {
        String name = require(req.name(), "name is required", OidcProvider.MAX_NAME_LENGTH);
        String issuer = require(req.issuer(), "issuer is required", OidcProvider.MAX_URL_LENGTH);
        String discoveryUrl = validateUrl(req.discoveryUrl(), "discoveryUrl");
        String clientId = require(req.clientId(), "clientId is required", OidcProvider.MAX_CLIENT_ID_LENGTH);
        int timeout = clampTimeout(req.timeoutSeconds());
        if (providers.existsByNameIgnoreCase(name)) {
            throw AdminException.invalid("a provider with that name already exists");
        }
        boolean active = req.active() == null || req.active();
        String cipher = encryptIfPresent(req.clientSecret());
        validateDiscovery(issuer, discoveryUrl, timeout); // dials SSRF-guarded
        OidcProvider saved = providers.saveAndFlush(
                OidcProvider.create(name, normalizeIssuer(issuer), discoveryUrl, clientId, cipher, timeout));
        if (active) {
            activateLocked(saved);
        }
        log.info("admin created oidc provider id={} active={}", saved.getId(), active);
        return toDto(saved);
    }

    @Transactional
    public ProviderDto update(Long id, ProviderPatchRequest req) {
        OidcProvider provider = providers.findById(id).orElseThrow(AdminException::notFound);
        String name = req.name() == null ? provider.getName()
                : require(req.name(), "name must not be blank", OidcProvider.MAX_NAME_LENGTH);
        if (!name.equalsIgnoreCase(provider.getName()) && providers.existsByNameIgnoreCase(name)) {
            throw AdminException.invalid("a provider with that name already exists");
        }
        String issuer = req.issuer() == null ? provider.getIssuer()
                : normalizeIssuer(require(req.issuer(), "issuer must not be blank", OidcProvider.MAX_URL_LENGTH));
        String discoveryUrl = req.discoveryUrl() == null
                ? provider.getDiscoveryUrl() : validateUrl(req.discoveryUrl(), "discoveryUrl");
        String clientId = req.clientId() == null ? provider.getClientId()
                : require(req.clientId(), "clientId must not be blank", OidcProvider.MAX_CLIENT_ID_LENGTH);
        String newCipher = null;
        if (req.clientSecret() != null && !req.clientSecret().isBlank()) {
            newCipher = encryptIfPresent(req.clientSecret());
        }
        int timeout = req.timeoutSeconds() == null ? provider.getTimeoutSeconds()
                : clampTimeout(req.timeoutSeconds());
        provider.applyEdit(name.strip(), issuer, discoveryUrl, clientId, newCipher, timeout);
        boolean wantActive = req.active() == null ? provider.isActive() : req.active();
        if (wantActive || configChangedForDiscovery(provider, req)) {
            // dial-check on activation and whenever the endpoints moved
            validateDiscovery(issuer, discoveryUrl, timeout);
        }
        if (wantActive && !provider.isActive()) {
            providers.saveAndFlush(provider); // persist edits first (id stable)
            activateLocked(provider);
        } else if (!wantActive && provider.isActive()) {
            provider.setActiveFlag(false);
        }
        providers.saveAndFlush(provider);
        discovery.evict(provider.getId()); // edited config: no stale JWKS
        log.info("admin updated oidc provider id={} active={}", provider.getId(), provider.isActive());
        return toDto(provider);
    }

    /**
     * Explicit admin pairing: subject ↔ existing user (ADR-0009 §5 path 0 —
     * before any login). Refuses double-linking a subject or a user at one
     * provider; the unique index backstops races.
     */
    @Transactional
    public LinkDto assignLink(Long providerId, String subject, String email) {
        providers.findById(providerId).orElseThrow(AdminException::notFound);
        String sub = require(subject, "subject is required", 200);
        String normalized = com.keplura.server.identity.User.normalizeEmail(email);
        var user = userRepository.findByEmail(normalized).orElseThrow(() -> AdminException.invalid(
                "no user with that email exists (links attach to existing users only)"));
        links.findByProviderIdAndSubject(providerId, sub).ifPresent(l -> {
            throw AdminException.invalid("that subject is already linked");
        });
        links.findByProviderIdAndUserId(providerId, user.getId()).ifPresent(l -> {
            throw AdminException.invalid("that user already holds a link at this provider");
        });
        OidcAccountLink saved = links.saveAndFlush(OidcAccountLink.create(providerId, sub, user.getId()));
        log.info("admin linked oidc subject at provider={} to user={}", providerId, user.getId());
        return new LinkDto(saved.getId(), saved.getSubject(), user.getId(), user.getEmail(),
                saved.getCreatedAt().toString());
    }

    @Transactional(readOnly = true)
    public List<LinkDto> listLinks(Long providerId) {
        providers.findById(providerId).orElseThrow(AdminException::notFound);
        return links.findByProviderId(providerId).stream()
                .map(l -> new LinkDto(l.getId(), l.getSubject(), l.getUserId(),
                        userRepository.findById(l.getUserId()).map(u -> u.getEmail()).orElse(""),
                        l.getCreatedAt().toString()))
                .toList();
    }

    // ---- internals ----------------------------------------------------------

    /** Old holder cleared + flushed BEFORE the pending write (see javadoc). */
    private void activateLocked(OidcProvider target) {
        providers.findFirstByActiveTrue().ifPresent(current -> {
            if (!current.getId().equals(target.getId())) {
                current.setActiveFlag(false);
                providers.saveAndFlush(current);
            }
        });
        target.setActiveFlag(true); // pending sentinel
        providers.saveAndFlush(target);
        target.confirmActiveSlotIfPending();
        providers.saveAndFlush(target);
    }

    private static boolean configChangedForDiscovery(OidcProvider p, ProviderPatchRequest req) {
        return (req.issuer() != null && !normalizeIssuer(req.issuer()).equals(p.getIssuer()))
                || (req.discoveryUrl() != null && !req.discoveryUrl().equals(p.getDiscoveryUrl()));
    }

    /** Trailing-slash fold so "https://idp/" == "https://idp" (config mercy,
     *  exact-match validation still applies afterwards). */
    private static String normalizeIssuer(String issuer) {
        String v = issuer.strip();
        while (v.endsWith("/")) {
            v = v.substring(0, v.length() - 1);
        }
        return v;
    }

    private void validateDiscovery(String issuer, String discoveryUrl, int timeoutSeconds) {
        // A probe provider object (unsaved) — discover() only needs these.
        OidcProvider probe = OidcProvider.create("_probe", normalizeIssuer(issuer), discoveryUrl,
                "_", null, timeoutSeconds);
        try {
            discovery.discover(probe);
        } catch (OidcException e) {
            if (e.code().equals(OidcException.REFUSED)) {
                throw AdminException.invalid(
                        "discovery document issuer does not match the configured issuer");
            }
            // The guard vetoed the target (policy) vs. the target failed to
            // answer (upstream) — ADMIN sees which, in ADMIN-KI's fixed-code
            // vocabulary; the login path keeps them indistinguishable (§8).
            throw e.code().equals(OidcException.POLICY_REFUSED)
                    ? AdminException.targetRefused()
                    : AdminException.invalid("discovery endpoint is not reachable or not routable");
        }
    }

    private String encryptIfPresent(String secret) {
        if (secret == null || secret.isBlank()) {
            return null;
        }
        if (!secretCipher.available()) {
            throw AdminException.masterKeyMissing();
        }
        if (secret.length() > 512) {
            throw AdminException.invalid("secret too long");
        }
        return secretCipher.encrypt(secret.strip());
    }

    /** Shape validation via the ADMIN-KI guard (scheme/userinfo/port). */
    private static String validateUrl(String raw, String field) {
        try {
            SsrfGuard.parseShape(raw);
        } catch (AdminException e) {
            throw AdminException.invalid(field + " " + e.getMessage());
        }
        String v = raw.strip();
        if (v.length() > OidcProvider.MAX_URL_LENGTH) {
            throw AdminException.invalid(field + " too long");
        }
        return v;
    }

    private static int clampTimeout(Integer timeoutSeconds) {
        if (timeoutSeconds == null) {
            return 15;
        }
        if (timeoutSeconds < OidcProvider.MIN_TIMEOUT_SECONDS
                || timeoutSeconds > OidcProvider.MAX_TIMEOUT_SECONDS) {
            throw AdminException.invalid("timeoutSeconds must be between "
                    + OidcProvider.MIN_TIMEOUT_SECONDS + " and " + OidcProvider.MAX_TIMEOUT_SECONDS);
        }
        return timeoutSeconds;
    }

    private static String require(String value, String message, int maxLength) {
        if (value == null || value.isBlank()) {
            throw AdminException.invalid(message);
        }
        if (value.strip().length() > maxLength) {
            throw AdminException.invalid("value exceeds " + maxLength + " characters");
        }
        return value.strip();
    }

    /** DTO mapping — the ONE place deciding what leaves: never the cipher. */
    static ProviderDto toDto(OidcProvider p) {
        return new ProviderDto(p.getId(), p.getName(), p.getIssuer(), p.getDiscoveryUrl(),
                p.getClientId(), p.hasSecret(), p.hasSecret() ? SecretCipher.MASK : "",
                p.getTimeoutSeconds(), p.isActive(),
                p.getCreatedAt().toString(), p.getUpdatedAt().toString());
    }
}
