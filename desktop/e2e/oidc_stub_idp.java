// OIDC-M2-Tail browser-e2e stub IdP (docs/adr/0009 §9): a REAL, minimal
// OpenID Provider for the Chromium flow — pure JDK (com.sun.net.httpserver),
// real RSA-2048 keypair generated at boot, genuinely RS256-signed ID tokens,
// and a token endpoint that REQUIRES the correct PKCE S256 verifier plus
// HTTP Basic client auth. This is NOT a contract mock: the code→verifier→
// claims path runs for real end to end.
//
// Unlike the embedded stub in OidcLoginFlowIntegrationTest (no UI), this one
// renders a REAL login form at /authorize: the browser flow clicks through
// it, so the whole redirect chain (begin -> IdP form -> callback) exercises
// genuine navigation, cookies and history exactly like a corporate IdP.
//
// Login semantics (drives the e2e legs):
//   user "oidc-e2e"            -> subject "e2e-subject-1", linked email,
//                                 email_verified=true  -> SUCCESS
//   user "unverified"          -> email_verified=false, unlinked subject
//                                 -> server refuses -> #oidc-error
//   user "deny"                -> IdP redirects back with error=access_denied
//                                 -> #oidc-error (IdP said no)
//   wrong password             -> 401-ish re-render (the form shows a hint)
//
// Boot:  java oidc_stub_idp.java <port> <clientId> <clientSecret> <email> <idpPass>
// Writes "IDP_READY <port>" to stdout once listening (handshake for the
// harness). Issuer is always http://127.0.0.1:<port> (SSRF allow-list leg).
//
// .java source-file launcher on purpose (JDK 21): zero build integration,
// zero dependencies — same posture as the packaged-app e2e python scripts.
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.Signature;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class oidc_stub_idp {

    static String issuer, clientId, clientSecret, linkedEmail, idpPass;
    static KeyPair keyPair;
    /** state -> [challenge, nonce, user] (single-use). */
    static final Map<String, String[]> SENT = new ConcurrentHashMap<>();

    public static void main(String[] args) throws Exception {
        int port = Integer.parseInt(args[0]);
        clientId = args[1];
        clientSecret = args[2];
        linkedEmail = args[3];
        idpPass = args.length > 4 ? args[4] : "";
        keyPair = KeyPairGenerator.getInstance("RSA").generateKeyPair();
        issuer = "http://127.0.0.1:" + port;

        HttpServer s = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        s.createContext("/.well-known/openid-configuration", oidc_stub_idp::discovery);
        s.createContext("/jwks", oidc_stub_idp::jwks);
        s.createContext("/authorize", oidc_stub_idp::authorize);
        s.createContext("/login", oidc_stub_idp::login);
        s.createContext("/token", oidc_stub_idp::token);
        s.setExecutor(java.util.concurrent.Executors.newFixedThreadPool(4));
        s.start();
        System.out.println("IDP_READY " + port);
        System.out.flush();
    }

    static void discovery(HttpExchange ex) throws java.io.IOException {
        String body = "{\"issuer\":\"" + issuer + "\""
                + ",\"authorization_endpoint\":\"" + issuer + "/authorize\""
                + ",\"token_endpoint\":\"" + issuer + "/token\""
                + ",\"jwks_uri\":\"" + issuer + "/jwks\""
                + ",\"response_types_supported\":[\"code\"]"
                + ",\"code_challenge_methods_supported\":[\"S256\"]"
                + ",\"id_token_signing_alg_values_supported\":[\"RS256\"]}";
        respond(ex, 200, body.getBytes(StandardCharsets.UTF_8), "application/json");
    }

    static void jwks(HttpExchange ex) throws java.io.IOException {
        RSAPublicKey pub = (RSAPublicKey) keyPair.getPublic();
        String body = "{\"keys\":[{\"kty\":\"RSA\",\"use\":\"sig\",\"alg\":\"RS256\""
                + ",\"kid\":\"e2e-key-1\""
                + ",\"n\":\"" + b64url(unsigned(pub.getModulus().toByteArray())) + "\""
                + ",\"e\":\"" + b64url(unsigned(pub.getPublicExponent().toByteArray())) + "\"}]}";
        respond(ex, 200, body.getBytes(StandardCharsets.UTF_8), "application/json");
    }

    /** GET renders the login form; the values ride hidden fields to /login.
     *  (HTML-escaping only: the browser percent-encodes form fields itself on
     *  submit, and parseForm decodes exactly once — double-encoding here
     *  would corrupt the challenge.) */
    static void authorize(HttpExchange ex) throws java.io.IOException {
        Map<String, String> q = parseForm(ex.getRequestURI().getRawQuery());
        String state = q.getOrDefault("state", "");
        String challenge = q.getOrDefault("code_challenge", "");
        String carried = ""
                + "<input type=\"hidden\" name=\"state\" value=\"" + esc(state) + "\"/>"
                + "<input type=\"hidden\" name=\"challenge\" value=\"" + esc(challenge) + "\"/>"
                + "<input type=\"hidden\" name=\"nonce\" value=\"" + esc(q.getOrDefault("nonce", "")) + "\"/>"
                + "<input type=\"hidden\" name=\"redirect_uri\" value=\"" + esc(q.getOrDefault("redirect_uri", "")) + "\"/>";
        String form = "<!doctype html><html><head><title>E2E IdP</title></head><body>"
                + "<h1>Test-IdP Login</h1>"
                + "<form method=\"POST\" action=\"/login\">"
                + carried
                + "<label>Benutzer <input name=\"user\" data-testid=\"idp-user\"/></label>"
                + "<label>Passwort <input name=\"pass\" type=\"password\" data-testid=\"idp-pass\"/></label>"
                + "<button type=\"submit\" data-testid=\"idp-submit\">Anmelden</button>"
                + "</form></body></html>";
        respond(ex, 200, form.getBytes(StandardCharsets.UTF_8), "text/html; charset=utf-8");
    }

    /** The form post: mint the code for the chosen persona, 302 back. */
    static void login(HttpExchange ex) throws java.io.IOException {
        Map<String, String> form = parseForm(new String(
                ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
        String user = form.getOrDefault("user", "");
        String state = form.getOrDefault("state", "");
        String redirect = form.getOrDefault("redirect_uri", "");
        if (!idpPass.equals(form.getOrDefault("pass", "")) || redirect.isEmpty()) {
            // wrong credentials: re-render the same form with a hint (no code
            // minted — the OIDC chain simply never continues)
            String again = "<!doctype html><html><body>"
                    + "<p data-testid=\"idp-error\">bad credentials</p>"
                    + "<form method=\"POST\" action=\"/login\">"
                    + "<input type=\"hidden\" name=\"state\" value=\"" + esc(state) + "\"/>"
                    + "<input type=\"hidden\" name=\"challenge\" value=\"" + esc(form.getOrDefault("challenge", "")) + "\"/>"
                    + "<input type=\"hidden\" name=\"nonce\" value=\"" + esc(form.getOrDefault("nonce", "")) + "\"/>"
                    + "<input type=\"hidden\" name=\"redirect_uri\" value=\"" + esc(redirect) + "\"/>"
                    + "<input name=\"user\" data-testid=\"idp-user\"/><input name=\"pass\" type=\"password\" data-testid=\"idp-pass\"/>"
                    + "<button type=\"submit\" data-testid=\"idp-submit\">Anmelden</button>"
                    + "</form></body></html>";
            respond(ex, 401, again.getBytes(StandardCharsets.UTF_8), "text/html; charset=utf-8");
            return;
        }
        String base = redirect + (redirect.contains("?") ? "&" : "?");
        if ("deny".equals(user)) {
            // the IdP itself says no: error param, no code (negative UI leg)
            ex.getResponseHeaders().add("Location", base + "error=access_denied&state="
                    + URLEncoder.encode(state, StandardCharsets.UTF_8));
            ex.sendResponseHeaders(302, -1);
            ex.close();
            return;
        }
        SENT.put(state, new String[]{form.getOrDefault("challenge", ""),
                form.getOrDefault("nonce", ""), user});
        ex.getResponseHeaders().add("Location", base + "code=" + URLEncoder.encode("e2ecode:" + state,
                StandardCharsets.UTF_8) + "&state=" + URLEncoder.encode(state, StandardCharsets.UTF_8));
        ex.sendResponseHeaders(302, -1);
        ex.close();
    }

    /** Real exchange: Basic client auth + S256 verifier check, then a signed JWT. */
    static void token(HttpExchange ex) throws java.io.IOException {
        Map<String, String> form = parseForm(new String(
                ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
        String auth = ex.getRequestHeaders().getFirst("Authorization");
        boolean basicOk = auth != null && auth.startsWith("Basic ")
                && new String(Base64.getDecoder().decode(auth.substring(6)),
                        StandardCharsets.UTF_8).equals(clientId + ":" + clientSecret);
        if (!basicOk || !"authorization_code".equals(form.get("grant_type"))) {
            respond(ex, 401, "{\"error\":\"invalid_client\"}".getBytes(StandardCharsets.UTF_8),
                    "application/json");
            return;
        }
        String state = form.get("code") != null && form.get("code").startsWith("e2ecode:")
                ? form.get("code").substring(8) : null;
        String[] binding = state == null ? null : SENT.remove(state);
        String verifier = form.get("code_verifier");
        if (binding == null || verifier == null
                || !binding[0].equals(challengeOf(verifier))) {
            respond(ex, 400, "{\"error\":\"invalid_grant\"}".getBytes(StandardCharsets.UTF_8),
                    "application/json");
            return;
        }
        String user = binding[2];
        boolean verified = "oidc-e2e".equals(user);
        String subject = verified ? "e2e-subject-1" : "e2e-stranger-" + user;
        String email = verified ? linkedEmail : "stranger-" + user + "@example.com";
        try {
            respond(ex, 200, ("{\"access_token\":\"e2e-at\",\"token_type\":\"Bearer\""
                    + ",\"id_token\":\"" + idToken(binding[1], subject, email, verified) + "\"}")
                    .getBytes(StandardCharsets.UTF_8), "application/json");
        } catch (Exception e) {
            respond(ex, 500, "{}".getBytes(StandardCharsets.UTF_8), "application/json");
        }
    }

    static String idToken(String nonce, String subject, String email, boolean verified)
            throws Exception {
        long now = Instant.now().getEpochSecond();
        String header = "{\"alg\":\"RS256\",\"typ\":\"JWT\",\"kid\":\"e2e-key-1\"}";
        String payload = "{\"iss\":\"" + issuer + "\",\"aud\":\"" + clientId + "\""
                + ",\"sub\":\"" + subject + "\",\"exp\":" + (now + 300) + ",\"iat\":" + now
                + ",\"nonce\":\"" + nonce + "\""
                + ",\"email\":\"" + email + "\",\"email_verified\":" + verified + "}";
        String signingInput = b64url(header.getBytes(StandardCharsets.UTF_8))
                + "." + b64url(payload.getBytes(StandardCharsets.UTF_8));
        Signature sig = Signature.getInstance("SHA256withRSA");
        sig.initSign(keyPair.getPrivate());
        sig.update(signingInput.getBytes(StandardCharsets.US_ASCII));
        return signingInput + "." + b64url(sig.sign());
    }

    static String challengeOf(String verifier) {
        try {
            return b64url(MessageDigest.getInstance("SHA-256")
                    .digest(verifier.getBytes(StandardCharsets.US_ASCII)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    static byte[] unsigned(byte[] bytes) {
        if (bytes.length > 1 && bytes[0] == 0) {
            byte[] trimmed = new byte[bytes.length - 1];
            System.arraycopy(bytes, 1, trimmed, 0, trimmed.length);
            return trimmed;
        }
        return bytes;
    }

    static String b64url(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    static String esc(String v) {
        return v.replace("&", "&amp;").replace("<", "&lt;").replace("\"", "&quot;");
    }

    static Map<String, String> parseForm(String query) {
        Map<String, String> map = new LinkedHashMap<>();
        if (query == null || query.isBlank()) return map;
        for (String pair : query.split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0) {
                map.put(URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8),
                        URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8));
            }
        }
        return map;
    }

    static void respond(HttpExchange ex, int status, byte[] body, String ctype)
            throws java.io.IOException {
        ex.getResponseHeaders().add("Content-Type", ctype);
        ex.sendResponseHeaders(status, body.length);
        try (OutputStream out = ex.getResponseBody()) {
            out.write(body);
        }
    }
}
