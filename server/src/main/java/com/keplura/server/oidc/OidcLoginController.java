package com.keplura.server.oidc;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.ExceptionHandler;

import java.net.URI;

/**
 * OIDC login wire surface (docs/adr/0009 §3). Four endpoints:
 *
 * <ul>
 *   <li>{@code GET /api/v1/auth/oidc/providers} — the capabilities flag:
 *       unauthenticated 200 {@code {oidcEnabled, providers:[{id,name}]}};
 *       NEVER urls, client ids or anything richer (login-screen fact).</li>
 *   <li>{@code GET /api/v1/auth/oidc/{id}/begin} — 302 to the IdP. Unknown
 *       or inactive id → 404 (uniform); dial failures → 503 generic body;
 *       rate limit → 429 + Retry-After. Creates the session binding.</li>
 *   <li>{@code GET /api/v1/auth/oidc/{id}/callback} — the ONLY place a
 *       browser (IdP) lands. Success: 302 to the fixed desktop redirect
 *       with {@code #oidc=<handoff>}. ANY failure (error= param included):
 *       302 to the same target with {@code #oidc-error} — never a reason,
 *       never a status page (ADR-0009 §3).</li>
 *   <li>{@code POST /api/v1/auth/oidc/exchange} — consumes the handoff
 *       (§6) and mints the session through the SAME path as local login:
 *       invalidate → fresh session → principal → CSRF bind+cookie. The
 *       body shape is identical to LoginResponse (the desktop treats both
 *       logins as one flow).</li>
 * </ul>
 *
 * <p>The redirect_uri is DERIVED server-side from the fixed public origin
 * (ENV or the request's own scheme/host when unset) — the IdP spec demands
 * the exact match at token exchange, and never reflecting a caller param
 * keeps the open-redirect wall absolute.
 */
@RestController
@RequestMapping("/api/v1/auth/oidc")
public class OidcLoginController {

    private static final Logger log = LoggerFactory.getLogger(OidcLoginController.class);

    /** Capabilities projection: only id + operator label (ADR-0009 §3). */
    public record ProviderOption(long id, String name) {
    }

    public record Capabilities(boolean oidcEnabled, java.util.List<ProviderOption> providers) {
    }

    /** Exchange request: the fragment-delivered one-time code. */
    public record ExchangeRequest(String handoff, Boolean rememberMe) {
    }

    /** Exchange response: deliberately the SAME shape as POST /auth/login. */
    public record ExchangeResponse(Long userId, String displayName, String csrfToken, String role, boolean mustChangePassword) {
    }

    /** Generic error body — carries no reason detail (ADR-0003 style). */
    public record ErrorResponse(String error) {
    }

    private final com.keplura.server.auth.AuthenticatedSessions sessions;
    private final OidcLoginService loginService;
    private final OidcHandoffStore handoffs;
    private final OidcRateLimiter rateLimiter;
    private final OidcProperties props;
    private final com.keplura.server.identity.UserRepository userRepository;
    private final com.keplura.server.auth.CsrfService csrfService;

    public OidcLoginController(OidcLoginService loginService,
                               OidcHandoffStore handoffs,
                               OidcRateLimiter rateLimiter,
                               OidcProperties props,
                               com.keplura.server.identity.UserRepository userRepository,
                               com.keplura.server.auth.CsrfService csrfService, com.keplura.server.auth.AuthenticatedSessions sessions) {
        this.sessions = sessions;
        this.loginService = loginService;
        this.handoffs = handoffs;
        this.rateLimiter = rateLimiter;
        this.props = props;
        this.userRepository = userRepository;
        this.csrfService = csrfService;
    }

    @GetMapping("/providers")
    public Capabilities providers() {
        return loginService.theActiveProvider()
                .map(p -> new Capabilities(true, java.util.List.of(new ProviderOption(p.getId(), p.getName()))))
                .orElseGet(() -> new Capabilities(false, java.util.List.of()));
    }

    @GetMapping("/{providerId}/begin")
    public ResponseEntity<Void> begin(@PathVariable long providerId,
                                      HttpServletRequest request, HttpSession session) {
        String clientIp = request.getRemoteAddr(); // XFF not trusted (LoginRateLimiter doctrine)
        if (!rateLimiter.allowBegin(clientIp)) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .header(HttpHeaders.RETRY_AFTER,
                            Long.toString(rateLimiter.retryAfterBegin(clientIp).toSeconds()))
                    .build();
        }
        OidcProvider provider = loginService.activeProvider(providerId);
        String redirectUri = callbackUri(request, providerId);
        String target = loginService.begin(provider, session, redirectUri);
        return ResponseEntity.status(HttpStatus.FOUND).location(URI.create(target)).build();
    }

    @GetMapping("/{providerId}/callback")
    public ResponseEntity<Void> callback(@PathVariable long providerId,
                                         @RequestParam(name = "code", required = false) String code,
                                         @RequestParam(name = "state", required = false) String state,
                                         @RequestParam(name = "error", required = false) String idpError,
                                         HttpServletRequest request,
                                         HttpSession session) {
        String failure = props.desktopRedirectUri() + "#oidc-error";
        try {
            if (idpError != null && !idpError.isBlank()) {
                // The IdP itself said no (access_denied etc.): log the PARAM
                // NAME only (fixed vocabulary, max 40 chars), never any value.
                log.warn("oidc callback: provider={} idp_error={}", providerId,
                        idpError.length() > 40 ? idpError.substring(0, 40) : idpError);
                throw OidcException.upstream();
            }
            String handoff = loginService.complete(session, providerId, code, state,
                    callbackUri(request, providerId));
            return ResponseEntity.status(HttpStatus.FOUND)
                    .location(URI.create(props.desktopRedirectUri() + "#oidc=" + handoff))
                    .build();
        } catch (OidcException e) {
            // A provider that is unknown or retired is NOT an authentication
            // failure — it is a deployment fact begin already answers 404 for
            // (uniform, anti-enumeration posture identical). The task's gate
            // "disabled provider => endpoints 404/503" demands the same verb
            // here; only genuine auth/protocol refusals leave the browser
            // with #oidc-error.
            if (e.code().equals(OidcException.NOT_CONFIGURED)) {
                throw e; // -> @ExceptionHandler -> 404 generic body
            }
            // every other failure shape leaves only #oidc-error for the browser
            return ResponseEntity.status(HttpStatus.FOUND).location(URI.create(failure)).build();
        } catch (RuntimeException e) {
            // Defence in depth: a repository race (unique-index violation on a
            // simultaneous verified-email claim) or any unforeseen failure
            // must NEVER render a server error page in the user's browser.
            log.warn("oidc callback: unexpected failure ({})", e.getClass().getSimpleName());
            return ResponseEntity.status(HttpStatus.FOUND).location(URI.create(failure)).build();
        }
    }

    /**
     * Handoff → session (ADR-0009 §6). CSRF-exempt like /auth/login (no
     * session to protect yet — the single-use 256-bit handoff code is the
     * authenticator). Session fixation defence identical to login: any
     * pre-existing session is discarded first. 401 generic for a dead or
     * wrong code; 429 on the exchange flood guard.
     */
    @PostMapping("/exchange")
    public ResponseEntity<?> exchange(@RequestBody ExchangeRequest body,
                                      HttpServletRequest request,
                                      jakarta.servlet.http.HttpServletResponse response) {
        String clientIp = request.getRemoteAddr();
        if (!rateLimiter.allowExchange(clientIp)) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .header(HttpHeaders.RETRY_AFTER,
                            Long.toString(rateLimiter.retryAfterExchange(clientIp).toSeconds()))
                    .body(new ErrorResponse("too many attempts"));
        }
        Long userId = handoffs.consume(body == null ? null : body.handoff());
        handoffs.sweep();
        if (userId == null) {
            log.info("oidc exchange rejected (unknown/expired/consumed handoff)");
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(new ErrorResponse("invalid handoff"));
        }
        var userOpt = userRepository.findById(userId);
        if (userOpt.isEmpty()) {
            // authenticated handoff whose user vanished: same 401 (ADR-0003 posture)
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(new ErrorResponse("invalid handoff"));
        }
        var user = userOpt.get();
        String csrfToken = sessions.create(user, request, response, Boolean.TRUE.equals(body.rememberMe()));
        log.info("oidc session minted for user={}", user.getId());
        return ResponseEntity.ok(new ExchangeResponse(user.getId(), user.getDisplayName(),
                csrfToken, user.role().name(), user.isMustChangePassword()));
    }

    /**
     * The exact redirect_uri both legs must agree on. Public origin ENV wins;
     * deriving from the request keeps a LAN/dev deployment working without
     * config (the value is still never caller-influenced: only the server's
     * own scheme/host/port participate).
     */
    private String callbackUri(HttpServletRequest request, long providerId) {
        String base = props.publicBaseUrl();
        if (base.isEmpty()) {
            base = request.getScheme() + "://" + request.getServerName();
            int port = request.getServerPort();
            boolean defaultPort = ("http".equals(request.getScheme()) && port == 80)
                    || ("https".equals(request.getScheme()) && port == 443);
            if (!defaultPort) {
                base = base + ":" + port;
            }
        }
        return OidcConfiguration.trimTrailingSlash(base) + "/api/v1/auth/oidc/" + providerId + "/callback";
    }

    @ExceptionHandler(OidcException.class)
    public ResponseEntity<ErrorResponse> oidcFailure(OidcException e) {
        HttpStatus status = switch (e.code()) {
            case OidcException.NOT_CONFIGURED -> HttpStatus.NOT_FOUND;
            case OidcException.UPSTREAM, OidcException.POLICY_REFUSED -> HttpStatus.SERVICE_UNAVAILABLE;
            case OidcException.RATE_LIMITED -> HttpStatus.TOO_MANY_REQUESTS;
            default -> HttpStatus.BAD_REQUEST;
        };
        return ResponseEntity.status(status)
                .contentType(MediaType.APPLICATION_JSON)
                .body(new ErrorResponse(e.getMessage()));
    }
}
