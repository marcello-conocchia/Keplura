package com.keplura.server;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * CORS policy for the desktop login flow (M2d). The Tauri webview runs on its
 * own origin — {@code http://localhost:1420} in dev (Vite), {@code
 * tauri://localhost} in the packaged app — and calls the session API with
 * {@code credentials: "include"}. {@code @CrossOrigin(origins = "*")} cannot
 * serve this: wildcard and credentialed requests are mutually exclusive by
 * spec (CORS §Credentials), so the auth/orgs endpoints need an explicit
 * allow-list with credentials enabled.
 *
 * <p>Scope is deliberately narrow: only the paths the desktop actually calls
 * ({@code /api/v1/auth/login|logout|csrf}, {@code /api/v1/orgs},
 * {@code /api/v1/gateway}, {@code /api/v1/admin} — ADMIN-KI) are mapped.
 * {@code /api/v1/system/info} keeps its own permissive {@code *} (public,
 * credential-less), {@code /api/v1/health} stays CORS-free (operations-only).
 * No {@code *} origin is ever combined with allowCredentials — an attacker
 * page therefore learns nothing about which origins are allowed; requests
 * from anywhere else simply fail preflight.
 *
 * <p>Origins come from {@code keplura.desktop.allowed-origins} (comma
 * separated, this default). A deployment with a different webview origin
 * overrides it via environment, same as any operational value.
 */
@Configuration
public class DesktopCorsConfig implements WebMvcConfigurer {

    // Security finding F2 (M2d gate): the packaged origin on macOS/Linux is
    // tauri://localhost, on Windows it is http://tauri.localhost (Tauri 2 uses
    // a custom-protocol localhost origin there) — both must be allowed or the
    // browser/dev-server path fails preflight before the app can even log in.
    private static final String DEFAULT_ORIGINS =
            "http://localhost:1420,tauri://localhost,http://tauri.localhost";

    private final String[] allowedOrigins;

    public DesktopCorsConfig(
            @Value("${keplura.desktop.allowed-origins:" + DEFAULT_ORIGINS + "}") String allowedOrigins) {
        this.allowedOrigins = java.util.Arrays.stream(allowedOrigins.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toArray(String[]::new);
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        // QA finding (M2d gate): Retry-After must be listed as an exposed
        // header or the webview JS cannot read it on credentialed cross-origin
        // 429 responses (fetch only exposes the CORS-safelisted set by default).
        // SEC-2: X-CSRF-Token is listed even though login itself is CSRF-
        // EXEMPT and the desktop transport deliberately omits the header there
        // — an explicit allowedHeaders list REJECTS anything not in it at
        // preflight, so a client that sends the header on every call (a sane
        // future default) must never be locked out of the one endpoint that
        // boots the whole flow.
        registry.addMapping("/api/v1/auth/login")
                .allowedOrigins(allowedOrigins)
                .allowedMethods("POST", "OPTIONS")
                .allowedHeaders("Content-Type", "X-CSRF-Token")
                .exposedHeaders("Retry-After")
                .allowCredentials(true)
                .maxAge(3600);
        // SEC-2: logout became CSRF-gated — with the X-CSRF-Token header it is
        // no longer a CORS-simple request, so the preflight needs the header
        // explicitly allowed (an unset list rejects every custom header).
        registry.addMapping("/api/v1/auth/logout")
                .allowedOrigins(allowedOrigins)
                .allowedMethods("POST", "OPTIONS")
                .allowedHeaders("X-CSRF-Token")
                .allowCredentials(true)
                .maxAge(3600);
        // SEC-2: session-restore token read (GET /auth/csrf). A simple GET
        // needs no preflight, but the RESPONSE still needs CORS headers for
        // the webview origin to read it at all — credentials included.
        registry.addMapping("/api/v1/auth/csrf")
                .allowedOrigins(allowedOrigins)
                .allowedMethods("GET", "OPTIONS")
                .allowCredentials(true)
                .maxAge(3600);
        registry.addMapping("/api/v1/auth/me")
                .allowedOrigins(allowedOrigins)
                .allowedMethods("GET", "OPTIONS")
                .allowCredentials(true)
                .maxAge(3600);
        registry.addMapping("/api/v1/auth/password")
                .allowedOrigins(allowedOrigins)
                .allowedMethods("POST", "OPTIONS")
                .allowedHeaders("Content-Type", "X-CSRF-Token")
                .exposedHeaders("Retry-After")
                .allowCredentials(true)
                .maxAge(3600);
        // OIDC-M2-Tail (ADR-0009 §3): the capabilities poll (GET, simple) and
        // the handoff exchange (POST + Content-Type) are the only FETCHED
        // surfaces — begin/callback are top-level navigations the CORS layer
        // never sees. The exchange response needs credentials readable, so
        // the pattern mirrors /auth/login exactly.
        registry.addMapping("/api/v1/auth/oidc/**")
                .allowedOrigins(allowedOrigins)
                .allowedMethods("GET", "POST", "OPTIONS")
                .allowedHeaders("Content-Type", "X-CSRF-Token")
                .exposedHeaders("Retry-After")
                .allowCredentials(true)
                .maxAge(3600);
        // SEC-2: invite redemption lives on its own prefix; the POST carries
        // Content-Type AND the double-submit header, so BOTH must survive
        // preflight or the accept flow dies before the session gate is ever
        // reached.
        registry.addMapping("/api/v1/invites/**")
                .allowedOrigins(allowedOrigins)
                .allowedMethods("POST", "OPTIONS")
                .allowedHeaders("Content-Type", "X-CSRF-Token")
                .exposedHeaders("Retry-After")
                .allowCredentials(true)
                .maxAge(3600);
        // M2e: the desktop now CREATES orgs (POST with a JSON body), so this
        // mapping gains POST + Content-Type. Retry-After is exposed here too:
        // the QA gate found that any future 429 on a session path would be
        // unreadable by webview JS otherwise (fetch only exposes the
        // CORS-safelisted set by default) and listing it costs nothing.
        registry.addMapping("/api/v1/orgs")
                .allowedOrigins(allowedOrigins)
                .allowedMethods("GET", "POST", "OPTIONS")
                .allowedHeaders("Content-Type", "X-CSRF-Token")
                .exposedHeaders("Retry-After")
                .allowCredentials(true)
                .maxAge(3600);
        // M3b/M3c: every nested route under the org prefix (spaces, knowledge
        // bases, documents) is called by the desktop too, and pattern matching
        // is EXACT — "/api/v1/orgs" does not cover "/api/v1/orgs/1/spaces", so
        // the nested prefix gets its own mapping. Content-Disposition is
        // exposed because the document download endpoint sets it (attachment +
        // filename): fetch only exposes CORS-safelisted headers by default, so
        // without this the webview could not name the file it saves. The
        // pattern covers POST (create/upload via multipart — a cross-origin
        // multipart form submit is a CORS-simple request, but fetch() with
        // FormData + explicit headers is not, and the preflight must pass)
        // and GET (list + streaming download). M3d: X-Index-Status is exposed
        // so the desktop can show honest searchability per document.
        // M3e: the knowledge UI is the first DESKTOP consumer of every nested
        // route (kb create/list, document upload/list, POST .../search JSON) —
        // no new verbs or headers were needed, the existing mapping already
        // covers GET/POST + Content-Type + the exposed headers; the search
        // preflight is pinned by a regression test so this stays true.
        // M4b: chat rides the same nested prefix (conversations CRUD + the
        // SSE stream route). DELETE is added to the method list for the
        // conversation-delete route ONLY — other org paths keep answering
        // 405 server-side, CORS merely lets the webview ask.
        registry.addMapping("/api/v1/orgs/**")
                .allowedOrigins(allowedOrigins)
                .allowedMethods("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS")
                .allowedHeaders("Content-Type", "X-CSRF-Token")
                .exposedHeaders("Retry-After", "Content-Disposition", "X-Index-Status")
                .allowCredentials(true)
                .maxAge(3600);
        // M4a: the model gateway. Same credentialed webview origins; the POST
        // carries Content-Type plus the tenant header X-Keplura-Org, which
        // must be listed in allowedHeaders or the browser preflight rejects it
        // (custom headers are never CORS-simple). Retry-After stays exposed so
        // a mapped upstream 429 is readable by the desktop (same QA finding
        // as M2d).
        registry.addMapping("/api/v1/gateway/**")
                .allowedOrigins(allowedOrigins)
                .allowedMethods("GET", "POST", "OPTIONS")
                .allowedHeaders("Content-Type", "X-Keplura-Org", "X-CSRF-Token")
                .exposedHeaders("Retry-After")
                .allowCredentials(true)
                .maxAge(3600);
        // Every real admin verb still passes the session, CSRF and fresh-role gates.
        registry.addMapping("/api/v1/admin/**")
                .allowedOrigins(allowedOrigins)
                .allowedMethods("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS")
                .allowedHeaders("Content-Type", "X-CSRF-Token")
                .exposedHeaders("Retry-After")
                .allowCredentials(true)
                .maxAge(3600);
    }
}
