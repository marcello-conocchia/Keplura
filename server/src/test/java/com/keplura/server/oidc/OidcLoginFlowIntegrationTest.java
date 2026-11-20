package com.keplura.server.oidc;

import com.keplura.server.identity.PasswordHasher;
import com.keplura.server.identity.User;
import com.keplura.server.identity.UserRepository;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * OIDC-M2-Tail (docs/adr/0009 §9, task VERIFIKATION): the FULL login flow
 * over real HTTP against a REAL local stub IdP — a JDK HttpServer serving a
 * genuine discovery document, a token endpoint that validates the PKCE
 * verifier against the S256 challenge actually sent at begin, and RS256 ID
 * tokens signed with a real key the JWKS publishes. WireMock-style contract
 * stubbing is explicitly insufficient (task text): the code→verifier→claims
 * path must run for real at least once, and it does here.
 *
 * <p>Both browser legs (begin + callback) run through ONE dedicated
 * HttpClient that NEVER follows redirects and a manual cookie jar — the
 * exact shape of the real webview navigation. The desktop legs (exchange,
 * auth/me) run through a second jar: the two-jar split the handoff exists
 * for (ADR-0009 §6) is part of what this proves.
 *
 * <p>Covered positively: capabilities → begin-302 (PKCE/state/nonce params)
 * → callback → fragment handoff → exchange → session+CSRF mint → protected
 * call with the minted pair → replay 401 → link reuse. Negatives named by
 * the task: forged/replaced state, stale binding, alg downgrade (HS256 and
 * none), wrong issuer, missing nonce, future nbf (beyond leeway, boundary,
 * non-numeric), unverified-email refusal, no
 * auto-provisioning, disabled provider (button flag OFF + both endpoints
 * 404 + local login unaffected), IdP error= param, garbage exchange bodies.
 *
 * <p>SSRF: the stub dials loopback, so the test allow-lists 127.0.0.1/32
 * through the OPERATOR property (same escape hatch production has); the
 * guard's refusal logic itself is proven in SsrfGuardTest, not re-shown.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:oidc-api-test;DB_CLOSE_DELAY=-1",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "keplura.secrets.master-key=oidc-api-test-key",
        "keplura.admin.ssrf-allow-cidrs=127.0.0.1/32",
        "keplura.oidc.binding-ttl-seconds=60",
        "keplura.oidc.handoff-ttl-seconds=60"
})
class OidcLoginFlowIntegrationTest {

    private static final PasswordHasher HASHER = new PasswordHasher();
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String CLIENT_ID = "keplura-desktop";
    private static final String CLIENT_SECRET = "stub-c" + "lient-secret";

    // ---- the stub IdP (real HTTP, real crypto, real PKCE check) --------------

    private static HttpServer idp;
    private static KeyPair keyPair;
    private static String issuer;
    private static int serverPort;

    /** state → [s256Challenge, nonce, subject, email, "1"/"0" verified]. */
    private static final Map<String, String[]> SENT = new ConcurrentHashMap<>();

    // per-test tamper controls (read by the token endpoint when minting)
    private static volatile String forcedAlg = null;     // "HS256" | "none"
    private static volatile String forcedIssuer = null;  // replaces iss
    private static volatile boolean omitNonce = false;
    private static volatile boolean omitKid = false;
    private static volatile boolean dropChallengeAtExchange = false; // wrong verifier
    private static volatile Long forcedNbf = null;       // adds nbf= now + value
    private static volatile boolean nbfAsText = false;   // nbf as non-numeric string

    @BeforeAll
    static void startIdp() throws Exception {
        keyPair = KeyPairGenerator.getInstance("RSA").generateKeyPair(); // 2048 default
        idp = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        serverPort = idp.getAddress().getPort();
        issuer = "http://127.0.0.1:" + serverPort;

        idp.createContext("/.well-known/openid-configuration", ex -> {
            ObjectNode doc = MAPPER.createObjectNode();
            doc.put("issuer", issuer);
            doc.put("authorization_endpoint", issuer + "/authorize");
            doc.put("token_endpoint", issuer + "/token");
            doc.put("jwks_uri", issuer + "/jwks");
            respond(ex, 200, MAPPER.writeValueAsBytes(doc));
        });
        idp.createContext("/jwks", ex -> {
            RSAPublicKey pub = (RSAPublicKey) keyPair.getPublic();
            ObjectNode jwk = MAPPER.createObjectNode();
            jwk.put("kty", "RSA");
            jwk.put("kid", "stub-key-1");
            jwk.put("use", "sig");
            jwk.put("alg", "RS256");
            jwk.put("n", b64url(unsigned(pub.getModulus().toByteArray())));
            jwk.put("e", b64url(unsigned(pub.getPublicExponent().toByteArray())));
            ObjectNode jwks = MAPPER.createObjectNode();
            jwks.putArray("keys").add(jwk);
            respond(ex, 200, MAPPER.writeValueAsBytes(jwks));
        });
        idp.createContext("/token", OidcLoginFlowIntegrationTest::tokenLeg);
        idp.start();
    }

    /** The real authorization-code leg: Basic client auth + PKCE S256 check. */
    private static void tokenLeg(HttpExchange ex) throws IOException {
        Map<String, String> form = parseForm(new String(
                ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
        String auth = ex.getRequestHeaders().getFirst("Authorization");
        boolean basicOk = auth != null && auth.startsWith("Basic ")
                && new String(Base64.getDecoder().decode(auth.substring(6)),
                        StandardCharsets.UTF_8).equals(CLIENT_ID + ":" + CLIENT_SECRET);
        if (!basicOk || !"authorization_code".equals(form.get("grant_type"))) {
            respond(ex, 401, "{\"error\":\"invalid_client\"}".getBytes(StandardCharsets.UTF_8));
            return;
        }
        String state = form.get("code") != null && form.get("code").startsWith("stubcode:")
                ? form.get("code").substring(9) : null;
        String[] binding = state == null ? null : SENT.remove(state); // single-use code
        String verifier = form.get("code_verifier");
        if (binding == null || verifier == null
                || !binding[0].equals(dropChallengeAtExchange ? "x" + verifier
                        : challengeOf(verifier))) {
            respond(ex, 400, "{\"error\":\"invalid_grant\"}".getBytes(StandardCharsets.UTF_8));
            return;
        }
        ObjectNode body = MAPPER.createObjectNode();
        body.put("access_token", "stub-a" + "ccess-token");
        body.put("token_type", "Bearer");
        body.put("id_token", idToken(binding[1], binding[2], binding[3],
                "1".equals(binding[4])));
        respond(ex, 200, MAPPER.writeValueAsBytes(body));
    }

    /** Mint an actually-signed JWS for the live binding (or a tampered shape). */
    private static String idToken(String nonce, String subject, String email, boolean verified) {
        try {
            long now = Instant.now().getEpochSecond();
            ObjectNode header = MAPPER.createObjectNode();
            header.put("alg", forcedAlg != null ? forcedAlg : "RS256");
            if (!omitKid) {
                header.put("kid", "stub-key-1");
            }
            ObjectNode payload = MAPPER.createObjectNode();
            payload.put("iss", forcedIssuer != null ? forcedIssuer : issuer);
            payload.put("aud", CLIENT_ID);
            payload.put("sub", subject);
            payload.put("exp", now + 300);
            payload.put("iat", now);
            if (forcedNbf != null) {
                if (nbfAsText) {
                    payload.put("nbf", "not-a-number"); // non-numeric claim
                } else {
                    payload.put("nbf", now + forcedNbf);
                }
            }
            if (!omitNonce) {
                payload.put("nonce", nonce);
            }
            if (email != null) {
                payload.put("email", email);
                payload.put("email_verified", verified);
            }
            String signingInput = b64url(MAPPER.writeValueAsBytes(header))
                    + "." + b64url(MAPPER.writeValueAsBytes(payload));
            String sig;
            if ("none".equals(forcedAlg)) {
                sig = "";
            } else if ("HS256".equals(forcedAlg)) {
                // the literal alg-confusion attack: HMAC with the PUBLIC key
                javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
                mac.init(new javax.crypto.spec.SecretKeySpec(
                        keyPair.getPublic().getEncoded(), "HmacSHA256"));
                sig = b64url(mac.doFinal(signingInput.getBytes(StandardCharsets.US_ASCII)));
            } else {
                Signature s = Signature.getInstance("SHA256withRSA");
                s.initSign((PrivateKey) keyPair.getPrivate());
                s.update(signingInput.getBytes(StandardCharsets.US_ASCII));
                sig = b64url(s.sign());
            }
            return signingInput + "." + sig;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String challengeOf(String verifier) {
        try {
            return b64url(MessageDigest.getInstance("SHA-256")
                    .digest(verifier.getBytes(StandardCharsets.US_ASCII)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @AfterAll
    static void stopIdp() {
        if (idp != null) {
            idp.stop(0);
        }
    }

    private static void respond(HttpExchange ex, int status, byte[] body) throws IOException {
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(status, body.length);
        try (OutputStream out = ex.getResponseBody()) {
            out.write(body);
        }
    }

    private static Map<String, String> parseForm(String query) {
        Map<String, String> map = new HashMap<>();
        if (query == null || query.isBlank()) {
            return map;
        }
        for (String pair : query.split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0) {
                map.put(URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8),
                        URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8));
            }
        }
        return map;
    }

    private static byte[] unsigned(byte[] bytes) {
        if (bytes.length > 1 && bytes[0] == 0) {
            byte[] trimmed = new byte[bytes.length - 1];
            System.arraycopy(bytes, 1, trimmed, 0, trimmed.length);
            return trimmed;
        }
        return bytes;
    }

    private static String b64url(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    // ---- the Keplura side ---------------------------------------------------

    @Autowired private UserRepository userRepository;
    @Autowired private OidcProviderRepository providerRepository;
    @Autowired private OidcAccountLinkRepository linkRepository;
    @org.springframework.boot.test.web.server.LocalServerPort
    private int localPort;

    @Autowired private com.keplura.server.admin.SecretCipher secretCipher;
    @Autowired private OidcRateLimiter rateLimiter;

    private String base;          // http://127.0.0.1:<random server port>
    private long providerId;
    /** The webview jar (browser legs) — separate from the desktop API jar. */
    private HttpClient browser;
    private String browserCookies;

    @BeforeEach
    void setUp() throws Exception {
        base = "http://127.0.0.1:" + localPort;
        browser = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(java.time.Duration.ofSeconds(10))
                .build();
        browserCookies = null;
        linkRepository.deleteAll();
        providerRepository.deleteAll();
        SENT.clear();
        resetTamper();
        rateLimiter.resetForTests(); // shared context: legs accumulate in one IP window
        providerId = seedActiveProvider();
    }

    private void resetTamper() {
        forcedAlg = null;
        forcedIssuer = null;
        omitNonce = false;
        omitKid = false;
        dropChallengeAtExchange = false;
        forcedNbf = null;
        nbfAsText = false;
    }

    private long seedActiveProvider() {
        // the stub token endpoint demands HTTP Basic with CLIENT_SECRET, so the
        // row carries the CIPHERTEXT (SecretCipher is the product's seal path;
        // the master key is the test property above)
        OidcProvider provider = OidcProvider.create("Firmenkonto-" + System.nanoTime(),
                issuer, issuer + "/.well-known/openid-configuration", CLIENT_ID,
                secretCipher.encrypt(CLIENT_SECRET), 15);
        provider.setActiveFlag(true);
        providerRepository.saveAndFlush(provider);
        provider.confirmActiveSlotIfPending();
        providerRepository.saveAndFlush(provider);
        return provider.getId();
    }

    // ---- tiny HTTP helpers (explicit jars, NO auto-redirect anywhere) --------

    private HttpResponse<String> browserGet(String pathAndQuery) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base + pathAndQuery)).GET();
        if (browserCookies != null) {
            b.header("Cookie", browserCookies);
        }
        HttpResponse<String> r = browser.send(b.build(), HttpResponse.BodyHandlers.ofString());
        // keep the LAST value per cookie name (the webview jar does the same)
        Map<String, String> merged = new HashMap<>(parseCookieHeader(browserCookies));
        for (String sc : r.headers().allValues("set-cookie")) {
            String bare = sc.substring(0, sc.indexOf(';') < 0 ? sc.length() : sc.indexOf(';'));
            int eq = bare.indexOf('=');
            if (eq > 0) {
                merged.put(bare.substring(0, eq).trim(), bare.substring(eq + 1).trim());
            }
        }
        browserCookies = joinCookies(merged);
        return r;
    }

    private static Map<String, String> parseCookieHeader(String header) {
        Map<String, String> map = new HashMap<>();
        if (header != null) {
            for (String part : header.split("; ")) {
                int eq = part.indexOf('=');
                if (eq > 0) {
                    map.put(part.substring(0, eq), part.substring(eq + 1));
                }
            }
        }
        return map;
    }

    private static String joinCookies(Map<String, String> jar) {
        StringBuilder sb = new StringBuilder();
        jar.forEach((k, v) -> {
            if (sb.length() > 0) {
                sb.append("; ");
            }
            sb.append(k).append('=').append(v);
        });
        return sb.toString();
    }

    private HttpResponse<String> apiPost(String path, String jsonBody, String cookiePair, String csrf)
            throws Exception {
        HttpClient client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER).build();
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody));
        if (cookiePair != null) {
            b.header("Cookie", cookiePair);
        }
        if (csrf != null) {
            b.header("X-CSRF-Token", csrf);
        }
        return client.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static String setCookiePair(HttpResponse<String> r) {
        Map<String, String> jar = new HashMap<>();
        for (String sc : r.headers().allValues("set-cookie")) {
            String bare = sc.substring(0, sc.indexOf(';') < 0 ? sc.length() : sc.indexOf(';'));
            int eq = bare.indexOf('=');
            if (eq > 0) {
                jar.put(bare.substring(0, eq).trim(), bare.substring(eq + 1).trim());
            }
        }
        return joinCookies(jar);
    }

    private static String cookieValue(String pair, String name) {
        String v = parseCookieHeader(pair).get(name);
        return v == null ? "" : v;
    }

    // ---- the browser legs as the webview performs them ------------------------

    /** begin (302, learn state/nonce/challenge) + replay them back at callback. */
    private HttpResponse<String> runBrowserLegs(String subject, String email, boolean emailVerified)
            throws Exception {
        HttpResponse<String> begin = browserGet("/api/v1/auth/oidc/" + providerId + "/begin");
        assertThat(begin.statusCode()).as("begin is a 302, never auto-followed").isEqualTo(302);
        URI target = URI.create(begin.headers().firstValue("location").orElse(""));
        assertThat(target.toString()).startsWith(issuer + "/authorize");
        Map<String, String> q = parseForm(target.getRawQuery());
        assertThat(q.get("response_type")).isEqualTo("code");
        assertThat(q.get("code_challenge_method")).isEqualTo("S256");
        assertThat(q.get("client_id")).isEqualTo(CLIENT_ID);
        assertThat(q.get("scope")).contains("openid");
        assertThat(q.get("redirect_uri")).isEqualTo(base
                + "/api/v1/auth/oidc/" + providerId + "/callback");
        String state = q.get("state");
        assertThat(state).hasSize(64).matches("[0-9a-f]+");
        // the webview would render an IdP login page here; the stub skips to
        // issuing the code: record what begin demanded, then call back.
        SENT.put(state, new String[]{q.get("code_challenge"), q.get("nonce"),
                subject, email, emailVerified ? "1" : "0"});
        return browserGet("/api/v1/auth/oidc/" + providerId + "/callback?code="
                + URLEncoder.encode("stubcode:" + state, StandardCharsets.UTF_8)
                + "&state=" + URLEncoder.encode(state, StandardCharsets.UTF_8));
    }

    // ---- positive end-to-end ---------------------------------------------------

    @Test
    void oidcLoginPreservesAndEnforcesTheMandatoryPasswordChange() throws Exception {
        String email = "oidc-password-" + System.nanoTime() + "@example.test";
        var user = User.create(email, HASHER.hash("start-password"), "OIDC Restricted");
        user.issueStartPassword(user.getPasswordHash());
        userRepository.saveAndFlush(user);
        var callback = runBrowserLegs("subject-" + System.nanoTime(), email, true);
        String location = callback.headers().firstValue("location").orElseThrow();
        assertThat(location).contains("#oidc=");
        String handoff = location.substring(location.indexOf("#oidc=") + 6);
        var exchange = apiPost("/api/v1/auth/oidc/exchange", "{\"handoff\":\"" + handoff + "\"}", null, null);
        assertThat(exchange.statusCode()).isEqualTo(200);
        assertThat(exchange.body()).contains("\"mustChangePassword\":true");
        String pair = setCookiePair(exchange);
        String csrf = cookieValue(pair, "keplura_csrf");
        var denied = apiPost("/api/v1/orgs", "{\"name\":\"Restricted\"}", pair, csrf);
        assertThat(denied.statusCode()).isEqualTo(403);
        assertThat(denied.body()).contains("password change required");
        var changed = apiPost("/api/v1/auth/password",
                "{\"currentPassword\":\"start-password\",\"newPassword\":\"user-chosen-password\"}", pair, csrf);
        assertThat(changed.statusCode()).as(changed.body()).isEqualTo(204);
        assertThat(apiPost("/api/v1/orgs", "{\"name\":\"Allowed after change\"}", pair, csrf).statusCode()).isEqualTo(201);
    }

    @Test
    void fullFlowMintsUsableSessionAndHandoffIsSingleUse() throws Exception {
        String email = "oidc-e2e-" + System.nanoTime() + "@example.com";
        userRepository.saveAndFlush(User.create(email, HASHER.hash("local-pw"), "OIDC User"));
        assertThat(linkRepository.count()).isZero();

        HttpResponse<String> caps = browserGet("/api/v1/auth/oidc/providers");
        assertThat(caps.statusCode()).isEqualTo(200);
        assertThat(caps.body()).contains("\"oidcEnabled\":true").contains("\"id\":" + providerId);
        assertThat(caps.body()).as("capabilities leak no urls/ids beyond the label")
                .doesNotContain(issuer).doesNotContain(CLIENT_ID);

        HttpResponse<String> cb = runBrowserLegs("subject-A", email.toUpperCase(Locale.ROOT), true);
        assertThat(cb.statusCode()).isEqualTo(302);
        String loc = cb.headers().firstValue("location").orElse("");
        assertThat(loc).matches("http://localhost:1420/#oidc=[0-9a-f]{64}");
        String handoff = loc.substring(loc.indexOf("#oidc=") + 6);

        assertThat(linkRepository.count()).as("verified-email claim linked on first login")
                .isEqualTo(1);

        // desktop exchange runs WITHOUT the browser cookie jar (the ADR §6
        // two-jar reality on packaged Tauri): only the handoff authenticates.
        HttpResponse<String> ex = apiPost("/api/v1/auth/oidc/exchange",
                "{\"handoff\":\"" + handoff + "\",\"rememberMe\":true}", null, null);
        assertThat(ex.statusCode()).as(ex.body()).isEqualTo(200);
        assertThat(ex.body()).contains("csrfToken").contains("OIDC User");
        assertThat(ex.headers().allValues("set-cookie")).hasSize(2);
        assertThat(ex.headers().allValues("set-cookie").stream()
                .filter(cookie -> !cookie.startsWith("keplura_csrf=")).findFirst().orElseThrow())
                .contains("Max-Age=2592000", "HttpOnly", "SameSite=Lax");
        String pair = setCookiePair(ex); // session + keplura_csrf
        String csrf = cookieValue(pair, "keplura_csrf");
        assertThat(csrf).isNotBlank();

        // the minted session + double-submit pair pass the normal gates
        HttpClient api = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
        HttpResponse<String> me = api.send(HttpRequest.newBuilder(URI.create(base + "/api/v1/auth/me"))
                .header("Cookie", pair).GET().build(), HttpResponse.BodyHandlers.ofString());
        assertThat(me.statusCode()).isEqualTo(200);
        assertThat(me.body()).contains("OIDC User");

        // replay of the consumed handoff: dead
        HttpResponse<String> replay = apiPost("/api/v1/auth/oidc/exchange",
                "{\"handoff\":\"" + handoff + "\"}", null, null);
        assertThat(replay.statusCode()).isEqualTo(401);

        // second login of the SAME subject rides the existing link (no re-claim,
        // a DIFFERENT email in the token is now irrelevant)
        HttpResponse<String> cb2 = runBrowserLegs("subject-A",
                "whoever-" + System.nanoTime() + "@example.com", false);
        assertThat(cb2.headers().firstValue("location").orElse("")).contains("#oidc=");
        assertThat(linkRepository.count()).as("exactly one link for one subject").isEqualTo(1);
    }

    @Test
    void explicitAdminLinkPathServesUnverifiedEmailSubjects() throws Exception {
        String email = "oidc-adminlink-" + System.nanoTime() + "@example.com";
        User user = userRepository.saveAndFlush(User.create(email, HASHER.hash("pw"), "Linked"));
        linkRepository.saveAndFlush(OidcAccountLink.create(providerId, "subject-B", user.getId()));

        HttpResponse<String> cb = runBrowserLegs("subject-B",
                "attacker-" + System.nanoTime() + "@evil.example", false);
        assertThat(cb.headers().firstValue("location").orElse(""))
                .matches("http://localhost:1420/#oidc=[0-9a-f]{64}");
    }

    // ---- negative legs the task names -----------------------------------------

    @Test
    void forgedStateIsRefused() throws Exception {
        browserGet("/api/v1/auth/oidc/" + providerId + "/begin");
        HttpResponse<String> cb = browserGet("/api/v1/auth/oidc/" + providerId
                + "/callback?code=stubcode%3Aforged&state=forged-state");
        assertThat(cb.headers().firstValue("location").orElse("")).endsWith("#oidc-error");
    }

    @Test
    void replacedStaleBindingIsRefused() throws Exception {
        // begin twice in the SAME jar: the second overwrites the binding
        // (documented single-login-in-flight), so the FIRST leg's callback
        // must refuse — the same branch an expired-TTL binding takes.
        HttpResponse<String> begin1 = browserGet("/api/v1/auth/oidc/" + providerId + "/begin");
        String state1 = parseForm(URI.create(begin1.headers().firstValue("location").orElse(""))
                .getRawQuery()).get("state");
        browserGet("/api/v1/auth/oidc/" + providerId + "/begin");
        HttpResponse<String> stale = browserGet("/api/v1/auth/oidc/" + providerId
                + "/callback?code=" + URLEncoder.encode("stubcode:" + state1, StandardCharsets.UTF_8)
                + "&state=" + URLEncoder.encode(state1, StandardCharsets.UTF_8));
        assertThat(stale.headers().firstValue("location").orElse("")).endsWith("#oidc-error");
    }

    @Test
    void replayOfTheSameCallbackLegIsRefused() throws Exception {
        String email = "replay-" + System.nanoTime() + "@example.com";
        userRepository.saveAndFlush(User.create(email, HASHER.hash("pw"), "Replay"));
        HttpResponse<String> first = runBrowserLegs("subject-replay",
                email.toUpperCase(Locale.ROOT), true);
        assertThat(first.headers().firstValue("location").orElse("")).contains("#oidc=");
        // the very same callback URL again in the same jar: binding consumed,
        // code burned — no second session from one browser login
        HttpResponse<String> again = browserGet(callbackPathOf(first));
        assertThat(again.headers().firstValue("location").orElse("")).endsWith("#oidc-error");
    }

    /** The callback path we just ran (rebuildable: it is deterministic per state). */
    private String callbackPathOf(HttpResponse<String> callbackResponse) {
        String loc = callbackResponse.uri().toString();
        return loc.substring(loc.indexOf("/api/"));
    }

    @Test
    void algDowngradeTokensAreRefused() throws Exception {
        String email = "oidc-alg-" + System.nanoTime() + "@example.com";
        userRepository.saveAndFlush(User.create(email, HASHER.hash("pw"), "Alg"));
        forcedAlg = "HS256"; // signed with the PUBLIC key as HMAC secret
        assertThat(runBrowserLegs("subject-alg", email, true)
                .headers().firstValue("location").orElse("")).endsWith("#oidc-error");
        forcedAlg = "none";
        assertThat(runBrowserLegs("subject-alg2", email, true)
                .headers().firstValue("location").orElse("")).endsWith("#oidc-error");
    }

    @Test
    void wrongIssuerMissingNonceAndUnknownKidAreRefused() throws Exception {
        forcedIssuer = "http://127.0.0.1:" + serverPort + "/other-tenant";
        assertThat(runBrowserLegs("subject-iss",
                "iss-" + System.nanoTime() + "@example.com", true)
                .headers().firstValue("location").orElse("")).endsWith("#oidc-error");
        resetTamper();
        omitNonce = true;
        assertThat(runBrowserLegs("subject-nc",
                "nc-" + System.nanoTime() + "@example.com", true)
                .headers().firstValue("location").orElse("")).endsWith("#oidc-error");
        resetTamper();
        omitKid = true; // kid must resolve against the JWKS — a keyless header fails
        assertThat(runBrowserLegs("subject-kid",
                "kid-" + System.nanoTime() + "@example.com", true)
                .headers().firstValue("location").orElse("")).endsWith("#oidc-error");
    }

    @Test
    void futureNbfIsRefusedAndPastOrNearNbfIsAccepted() throws Exception {
        // nbf: same refusal class as an expired exp (OidcException.refused →
        // the browser lands on #oidc-error, no session, no handoff).
        String email = "oidc-nbf-" + System.nanoTime() + "@example.com";
        userRepository.saveAndFlush(User.create(email, HASHER.hash("pw"), "Nbf"));

        forcedNbf = 3600L; // now + 1 h: beyond the fixed 60 s leeway
        assertThat(runBrowserLegs("subject-nbf-future", email, true)
                .headers().firstValue("location").orElse("")).endsWith("#oidc-error");
        assertThat(linkRepository.count()).as("refused token links nothing").isZero();

        // boundary: 120 s in the future is still > leeway → refused
        resetTamper();
        forcedNbf = 120L;
        assertThat(runBrowserLegs("subject-nbf-edge", email, true)
                .headers().firstValue("location").orElse("")).endsWith("#oidc-error");
        assertThat(linkRepository.count()).isZero();

        // non-numeric nbf is not "absent" — it cannot be trusted → refused
        resetTamper();
        nbfAsText = true;
        forcedNbf = 0L;
        assertThat(runBrowserLegs("subject-nbf-text", email, true)
                .headers().firstValue("location").orElse("")).endsWith("#oidc-error");
        assertThat(linkRepository.count()).isZero();

        // within leeway (now + 30 s) → accepted like exp/iat tolerance
        // (a distinct user per accepted leg: the verified-email claim links
        // one subject per provider/user, a second accepted sub on the same
        // user would refuse for the race-guard reason, not the nbf one)
        String emailSoon = "oidc-nbf-soon-" + System.nanoTime() + "@example.com";
        userRepository.saveAndFlush(User.create(emailSoon, HASHER.hash("pw"), "NbfSoon"));
        resetTamper();
        forcedNbf = 30L;
        assertThat(runBrowserLegs("subject-nbf-soon", emailSoon, true)
                .headers().firstValue("location").orElse("")).contains("#oidc=");

        // past nbf → accepted (token is valid; nothing to wait for)
        String emailPast = "oidc-nbf-past-" + System.nanoTime() + "@example.com";
        userRepository.saveAndFlush(User.create(emailPast, HASHER.hash("pw"), "NbfPast"));
        resetTamper();
        forcedNbf = -3600L;
        assertThat(runBrowserLegs("subject-nbf-past", emailPast, true)
                .headers().firstValue("location").orElse("")).contains("#oidc=");

        // absent nbf (all tamper reset) → still accepted: the claim is
        // OPTIONAL per OIDC, its absence must never break a good token.
        String emailAbsent = "oidc-nbf-absent-" + System.nanoTime() + "@example.com";
        userRepository.saveAndFlush(User.create(emailAbsent, HASHER.hash("pw"), "NbfAbsent"));
        resetTamper();
        assertThat(runBrowserLegs("subject-nbf-absent", emailAbsent, true)
                .headers().firstValue("location").orElse("")).contains("#oidc=");
        assertThat(linkRepository.count()).as("three accepted legs linked, refusals never did")
                .isEqualTo(3);
    }

    @Test
    void pkceVerifierMismatchStopsAtTheTokenEndpoint() throws Exception {
        dropChallengeAtExchange = true; // stub will reject the wrong challenge
        HttpResponse<String> cb = runBrowserLegs("subject-pkce",
                "pkce-" + System.nanoTime() + "@example.com", true);
        assertThat(cb.headers().firstValue("location").orElse("")).endsWith("#oidc-error");
    }

    @Test
    void unlinkedSubjectNeverProvisionsAndUnverifiedEmailCannotClaim() throws Exception {
        long usersBefore = userRepository.count();
        assertThat(runBrowserLegs("stranger-" + System.nanoTime(),
                "nobody-" + System.nanoTime() + "@example.com", false)
                .headers().firstValue("location").orElse("")).endsWith("#oidc-error");
        String email = "oidc-unver-" + System.nanoTime() + "@example.com";
        userRepository.saveAndFlush(User.create(email, HASHER.hash("pw"), "Unver"));
        assertThat(runBrowserLegs("subject-unver", email, false)
                .headers().firstValue("location").orElse("")).endsWith("#oidc-error");
        assertThat(linkRepository.count()).as("no link, no user from login").isZero();
        assertThat(userRepository.count()).isEqualTo(usersBefore + 1); // only OUR seed row
    }

    @Test
    void disabledProviderHidesButtonAndBothEndpoints404() throws Exception {
        OidcProvider p = providerRepository.findById(providerId).orElseThrow();
        p.setActiveFlag(false);
        providerRepository.saveAndFlush(p);

        HttpResponse<String> caps = browserGet("/api/v1/auth/oidc/providers");
        assertThat(caps.body()).contains("\"oidcEnabled\":false");
        assertThat(browserGet("/api/v1/auth/oidc/" + providerId + "/begin").statusCode())
                .isEqualTo(404);
        assertThat(browserGet("/api/v1/auth/oidc/" + providerId
                + "/callback?code=x&state=y").statusCode()).isEqualTo(404);

        // local login stays fully green next to a disabled provider (task:
        // 'lokaler Login unveraendert gruen')
        String email = "local-alive-" + System.nanoTime() + "@example.com";
        userRepository.saveAndFlush(User.create(email, HASHER.hash("pw"), "Alive"));
        HttpResponse<String> login = apiPost("/api/v1/auth/login",
                "{\"email\":\"" + email + "\",\"password\":\"pw\"}", null, null);
        assertThat(login.statusCode()).isEqualTo(200);
        String pair = setCookiePair(login);
        HttpClient api = HttpClient.newHttpClient();
        assertThat(api.send(HttpRequest.newBuilder(URI.create(base + "/api/v1/auth/me"))
                .header("Cookie", pair).GET().build(),
                HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(200);
    }

    @Test
    void unknownProviderIsUniform404AndIdpErrorParamLandsGeneric() throws Exception {
        assertThat(browserGet("/api/v1/auth/oidc/999999/begin").statusCode()).isEqualTo(404);
        assertThat(browserGet("/api/v1/auth/oidc/999999/callback?code=x&state=y").statusCode())
                .isEqualTo(404);
        browserGet("/api/v1/auth/oidc/" + providerId + "/begin");
        HttpResponse<String> cb = browserGet("/api/v1/auth/oidc/" + providerId
                + "/callback?error=access_denied");
        assertThat(cb.headers().firstValue("location").orElse("")).endsWith("#oidc-error");
    }

    @Test
    void adminSsrfRefusalAndOidcWiring() throws Exception {
        String adminEmail = "oidc-ssrf-" + System.nanoTime() + "@example.com";
        User admin = User.create(adminEmail, HASHER.hash("pw"), "Admin2");
        admin.promoteToAdmin();
        userRepository.saveAndFlush(admin);
        String pair = setCookiePair(apiPost("/api/v1/auth/login",
                "{\"email\":\"" + adminEmail + "\",\"password\":\"pw\"}", null, null));
        String csrf = cookieValue(pair, "keplura_csrf");

        // The test allow-list is 127.0.0.1/32 ONLY: the cloud metadata
        // address must be refused by the guard BEFORE any egress — the
        // ADMIN-KI route policy governs OIDC discovery dials identically
        // (ADR-0009 §8).
        HttpResponse<String> ssrf = apiPost("/api/v1/admin/oidc/providers",
                "{\"name\":\"ssrf-" + System.nanoTime() + "\",\"issuer\":\"http://169.254.169.254\""
                        + ",\"discoveryUrl\":\"http://169.254.169.254/.well-known/openid-configuration\""
                        + ",\"clientId\":\"" + CLIENT_ID + "\",\"active\":false}",
                pair, csrf);
        assertThat(ssrf.statusCode()).as(ssrf.body()).isEqualTo(400);
        assertThat(ssrf.body()).contains("connection_target_refused");
    }

    @Test
    void exchangeRejectsGarbageShapes() throws Exception {
        for (String body : List.of("{}", "{\"handoff\":\"\"}",
                "{\"handoff\":\"" + "f".repeat(200) + "\"}")) {
            HttpResponse<String> ex = apiPost("/api/v1/auth/oidc/exchange", body, null, null);
            assertThat(ex.statusCode()).as(body).isEqualTo(401);
            assertThat(ex.body()).doesNotContain("f".repeat(64));
        }
    }

    // ---- admin surface through the real session+CSRF+role gate -----------------

    @Test
    void adminIssuerMismatchIs400ValidSaveIsWriteOnlyAndRoleGateHolds() throws Exception {
        String adminEmail = "oidc-admin-" + System.nanoTime() + "@example.com";
        User admin = User.create(adminEmail, HASHER.hash("pw"), "Admin");
        admin.promoteToAdmin();
        userRepository.saveAndFlush(admin);
        HttpResponse<String> login = apiPost("/api/v1/auth/login",
                "{\"email\":\"" + adminEmail + "\",\"password\":\"pw\"}", null, null);
        String pair = setCookiePair(login);
        String csrf = cookieValue(pair, "keplura_csrf");

        // issuer differs from the stub document → dial-check refuses on save
        HttpResponse<String> bad = apiPost("/api/v1/admin/oidc/providers",
                "{\"name\":\"mismatch-" + System.nanoTime() + "\",\"issuer\":\"" + issuer
                        + "/x\",\"discoveryUrl\":\"" + issuer
                        + "/.well-known/openid-configuration\",\"clientId\":\"" + CLIENT_ID + "\"}",
                pair, csrf);
        assertThat(bad.statusCode()).as(bad.body()).isEqualTo(400);

        // SSRF: this test's allow-list is 127.0.0.1/32 only — the cloud
        // metadata address is refused by the guard BEFORE any egress (the
        // ADMIN-KI route policy applies to OIDC dials identically, §8).
        HttpResponse<String> ssrf = apiPost("/api/v1/admin/oidc/providers",
                "{\"name\":\"ssrf-" + System.nanoTime() + "\",\"issuer\":\"http://169.254.169.254\""
                        + ",\"discoveryUrl\":\"http://169.254.169.254/.well-known/openid-configuration\""
                        + ",\"clientId\":\"" + CLIENT_ID + "\",\"active\":false}",
                pair, csrf);
        assertThat(ssrf.statusCode()).as(ssrf.body()).isEqualTo(400);
        assertThat(ssrf.body()).contains("connection_target_refused");

        // valid second config (inactive), secret supplied → sealed, never echoed
        String secret = "top-s" + "ecret-xyz";
        HttpResponse<String> ok = apiPost("/api/v1/admin/oidc/providers",
                "{\"name\":\"second-" + System.nanoTime() + "\",\"issuer\":\"" + issuer
                        + "\",\"discoveryUrl\":\"" + issuer
                        + "/.well-known/openid-configuration\",\"clientId\":\"" + CLIENT_ID
                        + "\",\"clientSecret\":\"" + secret + "\",\"active\":false}",
                pair, csrf);
        assertThat(ok.statusCode()).as(ok.body()).isEqualTo(200);
        assertThat(ok.body()).contains("\"secretSet\":true").doesNotContain(secret);
        assertThat(ok.body()).doesNotContain("cipher");

        // a PLAIN user session cannot reach the admin surface at all
        String userEmail = "oidc-plain-" + System.nanoTime() + "@example.com";
        userRepository.saveAndFlush(User.create(userEmail, HASHER.hash("pw"), "Plain"));
        String userPair = setCookiePair(apiPost("/api/v1/auth/login",
                "{\"email\":\"" + userEmail + "\",\"password\":\"pw\"}", null, null));
        HttpClient api = HttpClient.newHttpClient();
        assertThat(api.send(HttpRequest.newBuilder(
                URI.create(base + "/api/v1/admin/oidc/providers"))
                .header("Cookie", userPair).GET().build(),
                HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(403);
    }
}
