package com.keplura.server.oidc;

import com.keplura.server.admin.SecretCipher;
import com.keplura.server.identity.User;
import com.keplura.server.identity.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.PublicKey;
import java.time.Instant;
import java.util.Map;

/**
 * OIDC login orchestration (docs/adr/0009 §1/§4/§5/§6): the callback side —
 * binding consumption, code exchange, ID-token validation, account linking,
 * handoff issue. The browser-facing pieces live in
 * {@link OidcLoginController}; the session minting in
 * {@code OidcLoginController.exchange} (same class, §6 handoff path).
 *
 * <p>Validation order is the ADR order and every branch refuses with the
 * same generic {@link OidcException#refused()} — the browser only ever sees
 * {@code #oidc-error}, the LOG sees the reason code + provider id only
 * (never subjects, emails, URLs or upstream text; logging convention from
 * docs/development.md).
 */
@Service
public class OidcLoginService {

    private static final Logger log = LoggerFactory.getLogger(OidcLoginService.class);

    /** Result of a fully-validated callback: ready to hand off. */
    public record AuthenticatedUser(long userId, String displayName) {
    }

    private final OidcProviderRepository providerRepository;
    private final OidcAccountLinkRepository linkRepository;
    private final UserRepository userRepository;
    private final OidcDiscoveryClient discovery;
    private final OidcTokenClient tokenClient;
    private final OidcBindingStore bindings;
    private final OidcHandoffStore handoffs;
    private final SecretCipher secretCipher;
    private final OidcProperties props;

    private com.keplura.server.access.EntraPrincipalService entraPrincipals;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setEntraPrincipals(com.keplura.server.access.EntraPrincipalService entraPrincipals) {
        this.entraPrincipals = entraPrincipals;
    }

    public OidcLoginService(OidcProviderRepository providerRepository,
                            OidcAccountLinkRepository linkRepository,
                            UserRepository userRepository,
                            OidcDiscoveryClient discovery,
                            OidcTokenClient tokenClient,
                            OidcBindingStore bindings,
                            OidcHandoffStore handoffs,
                            SecretCipher secretCipher,
                            OidcProperties props) {
        this.providerRepository = providerRepository;
        this.linkRepository = linkRepository;
        this.userRepository = userRepository;
        this.discovery = discovery;
        this.tokenClient = tokenClient;
        this.bindings = bindings;
        this.handoffs = handoffs;
        this.secretCipher = secretCipher;
        this.props = props;
    }

    /**
     * Active provider by id, or NOT_CONFIGURED (unknown id AND deactivated
     * id answer identically — anti-enumeration posture of ADR-0003 login).
     * The id must ALSO equal the one active provider row: the DB guarantees
     * at most one active, so a different id can only point at a retired or
     * never-existing row.
     */
    @Transactional(readOnly = true)
    public OidcProvider activeProvider(long providerId) {
        return providerRepository.findById(providerId)
                .filter(OidcProvider::isActive)
                .orElseThrow(OidcException::notConfigured);
    }

    /** The single active provider for capabilities/begin (empty = off). */
    @Transactional(readOnly = true)
    public java.util.Optional<OidcProvider> theActiveProvider() {
        return providerRepository.findFirstByActiveTrue();
    }

    /**
     * Begin leg: discovery is re-validated NOW (endpoint freshness + the
     * dial-side SSRF check run on every login, ADR-0009 §8) and the authorize
     * URL is assembled with state/nonce/PKCE from a fresh session binding.
     *
     * @param redirectUri the exact value also sent at the token exchange
     *                    (RFC 6749 §4.1.3 equality rule)
     */
    @Transactional(readOnly = true)
    public String begin(OidcProvider provider, jakarta.servlet.http.HttpSession session,
                        String redirectUri) {
        OidcDiscoveryClient.Discovery doc = discovery.discover(provider);
        OidcBindingStore.Binding binding = bindings.create(session, provider.getId());
        StringBuilder url = new StringBuilder(doc.authorizationEndpoint());
        url.append(doc.authorizationEndpoint().contains("?") ? '&' : '?');
        url.append("response_type=code");
        url.append("&client_id=").append(enc(provider.getClientId()));
        url.append("&redirect_uri=").append(enc(redirectUri));
        url.append("&scope=").append(enc("openid email"));
        url.append("&state=").append(enc(binding.state()));
        url.append("&nonce=").append(enc(binding.nonce()));
        url.append("&code_challenge=").append(enc(OidcCodec.pkceChallenge(binding.pkceVerifier())));
        url.append("&code_challenge_method=S256");
        return url.toString();
    }

    /**
     * Callback leg (everything in one governed call): binding consume +
     * state check + provider/TTL check + code exchange + full ID-token
     * validation + link resolution. Returns the handoff code the desktop
     * will exchange for a session (§6).
     */
    public String complete(jakarta.servlet.http.HttpSession session, long providerId,
                           String code, String state, String redirectUri) {
        OidcProvider provider = activeProvider(providerId);
        // Binding FIRST: the state/nonce/verifier live here; absent or
        // expired = refusal (this is also the replay wall — consumed
        // attribute cannot revalidate).
        OidcBindingStore.Binding binding = bindings.consume(session);
        if (binding == null || !bindings.fresh(binding, props.bindingTtlSeconds())
                || binding.providerId() != providerId) {
            log.warn("oidc callback refused: binding (provider={})", providerId);
            throw OidcException.refused();
        }
        if (!OidcCodec.equalsConstantTime(binding.state(), state)) {
            log.warn("oidc callback refused: state (provider={})", providerId);
            throw OidcException.refused();
        }
        if (code == null || code.isBlank() || code.length() > 4096) {
            throw OidcException.refused();
        }
        OidcDiscoveryClient.Discovery doc = discovery.discover(provider);
        String secret = (provider.hasSecret() && secretCipher.available())
                ? secretCipher.decrypt(provider.getClientSecretCipher()) : null;
        OidcTokenClient.TokenResponse tokens = tokenClient.exchange(
                doc.tokenEndpoint(), provider.getClientId(), secret,
                code, redirectUri, binding.pkceVerifier(), provider.getTimeoutSeconds());

        // ---- ID token validation (ADR-0009 §4, in this exact order) ----
        Map<String, PublicKey> keys = discovery.jwks(provider.getId(), doc.jwksUri(),
                provider.getTimeoutSeconds());
        JwsVerifier.Verified verified = verifyWithKidRefresh(provider, doc, keys, tokens.idToken());
        validateClaims(verified, provider, binding.nonce(), tokens.accessToken());

        AuthenticatedUser user = resolveUser(provider, verified.payload());
        if (entraPrincipals != null) {
            try {
                // DS-4: Entra user id + groups for SharePoint rights (only for Entra tokens)
                entraPrincipals.applyFromIdToken(user.userId(), verified.payload());
            } catch (RuntimeException e) {
                log.warn("entra principals not applied ({})", e.getClass().getSimpleName());
            }
        }

        handoffs.sweep();
        String handoff = OidcCodec.randomHex(32);
        handoffs.issue(handoff, user.userId(), props.handoffTtlSeconds());
        log.info("oidc login succeeded: provider={} user={}", provider.getId(), user.userId());
        return handoff;
    }

    private JwsVerifier.Verified verifyWithKidRefresh(OidcProvider provider,
                                                      OidcDiscoveryClient.Discovery doc,
                                                      Map<String, PublicKey> keys, String idToken) {
        try {
            return verifyToken(keys, idToken);
        } catch (OidcException e) {
            // one keyed refresh attempt — but only when the kid was simply
            // missing (a rotation); every other refusal rethrows unchanged
            String kid = peekKid(idToken);
            if (kid == null || keys.containsKey(kid) || !e.code().equals(OidcException.REFUSED)) {
                throw e;
            }
            Map<String, PublicKey> refreshed =
                    discovery.refreshJwksOnce(provider.getId(), doc.jwksUri(), provider.getTimeoutSeconds());
            if (refreshed == null) {
                throw e; // cache was hot: unknown kid is unknown — refuse
            }
            return verifyToken(refreshed, idToken);
        }
    }

    private static JwsVerifier.Verified verifyToken(Map<String, PublicKey> keys, String idToken) {
        return JwsVerifier.verify(idToken, keys, null);
    }

    /** Header kid only (NOT trusted for anything else — pre-signature peek). */
    private static String peekKid(String idToken) {
        try {
            String[] parts = idToken.split("\\.", -1);
            if (parts.length != 3) {
                return null;
            }
            var node = new tools.jackson.databind.ObjectMapper()
                    .readTree(OidcCodec.base64UrlDecode(parts[0]));
            String kid = node.path("kid").asString(null);
            return (kid == null || kid.isBlank() || kid.length() > 200) ? null : kid;
        } catch (Exception e) {
            return null;
        }
    }

    private void validateClaims(JwsVerifier.Verified verified, OidcProvider provider,
                                String expectedNonce, String accessToken) {
        var payload = verified.payload();
        String iss = payload.path("iss").asString(null);
        if (iss == null || !OidcCodec.equalsConstantTime(iss, provider.getIssuer())) {
            log.warn("oidc token refused: iss");
            throw OidcException.refused();
        }
        // aud: string OR array; must contain client_id; multi-aud pins azp
        var aud = payload.path("aud");
        boolean containsClient;
        int audCount;
        if (aud.isArray()) {
            audCount = aud.size();
            containsClient = false;
            var it = aud.valueStream().iterator();
            while (it.hasNext()) {
                if (OidcCodec.equalsConstantTime(it.next().asString(null), provider.getClientId())) {
                    containsClient = true;
                }
            }
        } else {
            String single = aud.asString(null);
            audCount = (single == null) ? 0 : 1;
            containsClient = OidcCodec.equalsConstantTime(single, provider.getClientId());
        }
        if (!containsClient) {
            log.warn("oidc token refused: aud");
            throw OidcException.refused();
        }
        if (audCount > 1
                && !OidcCodec.equalsConstantTime(payload.path("azp").asString(null), provider.getClientId())) {
            log.warn("oidc token refused: azp");
            throw OidcException.refused();
        }
        long now = Instant.now().getEpochSecond();
        long exp = payload.path("exp").asLong(Long.MIN_VALUE);
        long iat = payload.path("iat").asLong(-1);
        final long leeway = 60; // fixed, documented (ADR-0009 §4) — no config fork
        if (exp == Long.MIN_VALUE || exp + leeway < now) {
            log.warn("oidc token refused: exp");
            throw OidcException.refused();
        }
        if (iat < 0 || iat > now + leeway) {
            log.warn("oidc token refused: iat");
            throw OidcException.refused();
        }
        // nbf is OPTIONAL per OIDC (RFC 9068): absent → no check (absent()
        // sentinel, never the asLong default which a numeric claim could
        // collide with); present non-numeric → refuse; present and more
        // than the SAME fixed 60 s leeway into the future → not yet valid,
        // refused like an expired exp (ADR-0009 §4).
        var nbfNode = payload.path("nbf");
        if (nbfNode.isMissingNode() || nbfNode.isNull()) {
            // optional claim not present — valid without nbf check
        } else {
            long nbf = nbfNode.asLong(Long.MIN_VALUE);
            if (nbf == Long.MIN_VALUE || nbf > now + leeway) {
                log.warn("oidc token refused: nbf");
                throw OidcException.refused();
            }
        }
        if (!OidcCodec.equalsConstantTime(payload.path("nonce").asString(null), expectedNonce)) {
            log.warn("oidc token refused: nonce");
            throw OidcException.refused();
        }
        String sub = payload.path("sub").asString(null);
        if (sub == null || sub.isBlank() || sub.length() > 200) {
            log.warn("oidc token refused: sub");
            throw OidcException.refused();
        }
        JwsVerifier.verifyAtHash(payload, verified.alg(), accessToken);
    }

    /**
     * Account linking (ADR-0009 §5, no auto-provisioning):
     *  1. existing link (provider, sub) → that user;
     *  2. no link: email_verified == true (JSON boolean) AND the normalized
     *     email matches an EXISTING user → first-come claim: link them
     *     (unique (provider_id, subject) + a guard that the user does not
     *     already hold another subject at this provider make races and
     *     takeovers impossible — a losing racer's insert throws and the
     *     callback's catch-all turns it into the same generic failure);
     *  3. everything else → refusal (uniform, no oracle).
     *
     * <p>Runs per repository call WITHOUT a wrapping transaction on purpose:
     * complete() performs network dials (IdP) and must not hold a DB
     * connection across them; the atomicity that matters (one subject →
     * one user) is enforced by the unique index, not by transaction scope.
     */
    public AuthenticatedUser resolveUser(OidcProvider provider, tools.jackson.databind.JsonNode payload) {
        String sub = payload.path("sub").asString(null);
        var existing = linkRepository.findByProviderIdAndSubject(provider.getId(), sub);
        if (existing.isPresent()) {
            long userId = existing.get().getUserId();
            User user = userRepository.findById(userId)
                    .orElseThrow(OidcException::refused); // user deleted after link
            return new AuthenticatedUser(user.getId(), user.getDisplayName());
        }
        boolean emailVerified = payload.path("email_verified").isBoolean()
                && payload.path("email_verified").asBoolean(false);
        // (spec-strict: a string "true" is NOT a boolean — only the JSON
        // boolean true proves the IdP verified the address)
        String email = payload.path("email").asString(null);
        if (!emailVerified || email == null || email.isBlank()) {
            log.warn("oidc login refused: unlinked subject (provider={})", provider.getId());
            throw OidcException.refused();
        }
        User candidate = userRepository.findByEmail(User.normalizeEmail(email))
                .orElseThrow(() -> {
                    log.warn("oidc login refused: no user for verified email (provider={})", provider.getId());
                    return OidcException.refused();
                });
        // Claim race guard: one user may hold ONE subject per provider — an
        // existing link to a DIFFERENT sub at this provider blocks the claim.
        if (linkRepository.findByProviderIdAndUserId(provider.getId(), candidate.getId()).isPresent()) {
            log.warn("oidc login refused: user already linked elsewhere (provider={})", provider.getId());
            throw OidcException.refused();
        }
        linkRepository.saveAndFlush(OidcAccountLink.create(provider.getId(), sub, candidate.getId()));
        log.info("oidc verified-email claim linked user={} at provider={}",
                candidate.getId(), provider.getId());
        return new AuthenticatedUser(candidate.getId(), candidate.getDisplayName());
    }

    private static String enc(String v) {
        return java.net.URLEncoder.encode(v, java.nio.charset.StandardCharsets.UTF_8);
    }
}
