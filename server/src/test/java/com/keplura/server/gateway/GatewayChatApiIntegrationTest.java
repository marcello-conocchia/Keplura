package com.keplura.server.gateway;

import com.keplura.server.identity.PasswordHasher;
import com.keplura.server.identity.User;
import com.keplura.server.identity.UserRepository;
import com.keplura.server.org.OrgService;
import com.sun.net.httpserver.HttpServer;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.RequestEntity;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.TestPropertySource;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * M4a acceptance suite (AC-1 + AC-2): the session-gated chat proxy over REAL
 * HTTP against a scripted mock upstream on a fixed loopback port (JDK
 * HttpServer — the established no-new-dependency stub of this repo).
 *
 * <p>Covered here: happy JSON relay incl. proof the server-side key reaches
 * ONLY the upstream; SSE frame-for-frame relay; upstream 429/500/auth-failure/
 * abort mapping; 401 without session; 400 without org header; uniform 404 for
 * foreign vs unknown org; model allow-list rejection before any upstream hit;
 * and the sentinel battery: every error body from the mock deliberately
 * carries the key value and the upstream host:port, and BOTH the wire
 * responses and ALL {@code com.keplura} log lines are asserted free of
 * those sentinels after every scenario (AC-2).
 *
 * <p>"sekret-upstream-key" is a test fixture string (repo convention since
 * GatewayEmbeddingServiceTest), not a credential.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
// This class adds a THIRD live RANDOM_PORT Tomcat (with ApiIntegrationTest and
// DesktopCorsIntegrationTest) on top of the baseline's two. The dev container's
// cgroup pids limit (256) cannot hold all of them plus their pools at once, so
// the context MUST be closed (threads released) when this class finishes.
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:gateway-chat-test;DB_CLOSE_DELAY=-1",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "keplura.gateway.base-url=http://127.0.0.1:19911/v1",
        "keplura.gateway.api-key=sekret-upstr" + "eam-key",
        "keplura.gateway.allowed-models=ok-model",
        "keplura.gateway.timeout-seconds=5",
        "keplura.gateway.stream-idle-seconds=2",
        "keplura.gateway.stream-deadline-seconds=8",
        "keplura.gateway.max-request-bytes=4096",
        "keplura.gateway.max-response-bytes=16384"
})
class GatewayChatApiIntegrationTest {

    /** Must equal the api-key property above; used as leak sentinel. */
    private static final String KEY = "sekret-upstr" + "eam-key";
    private static final String UPSTREAM_HOST_PORT = "127.0.0.1:19911";

    private static final PasswordHasher HASHER = new PasswordHasher();
    private static final AtomicInteger NAME_SEQ = new AtomicInteger();

    private static HttpServer upstream;

    // per-test upstream script state
    private static volatile int stubStatus = 200;
    private static volatile String stubBody = "{}";
    private static volatile String stubContentType = "application/json";
    private static volatile boolean stubStream = false;
    private static volatile boolean stubStreamAbortIdle = false;
    private static volatile boolean stubAbortBeforeHeaders = false;
    private static volatile String stubRetryAfter = null;
    private static final AtomicInteger upstreamHits = new AtomicInteger();
    private static final AtomicReference<String> lastAuth = new AtomicReference<>();
    private static final AtomicReference<String> lastBody = new AtomicReference<>();
    private static final AtomicReference<String> lastAccept = new AtomicReference<>();

    private static ListAppender<ILoggingEvent> logAppender;

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private OrgService orgService;

    @BeforeAll
    static void startMockUpstream() throws IOException {
        upstream = HttpServer.create(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 19911), 0);
        upstream.createContext("/v1/chat/completions", exchange -> {
            upstreamHits.incrementAndGet();
            lastAuth.set(exchange.getRequestHeaders().getFirst("Authorization"));
            lastAccept.set(exchange.getRequestHeaders().getFirst("Accept"));
            lastBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            if (stubAbortBeforeHeaders) {
                // simulate an upstream that dies before answering: JDK client
                // surfaces this as IOException -> our 502 path
                exchange.close();
                return;
            }
            if (stubStream) {
                exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
                exchange.sendResponseHeaders(stubStatus, 0);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write("data: {\"id\":\"c1\",\"choices\":[{\"delta\":{\"content\":\"hallo\"}}]}\n\n"
                            .getBytes(StandardCharsets.UTF_8));
                    os.flush();
                    if (stubStreamAbortIdle) {
                        try {
                            Thread.sleep(4000); // > idle cap, < deadline: watchdog must kill
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                        os.write("data: [DONE]\n\n".getBytes(StandardCharsets.UTF_8));
                        os.flush();
                        return;
                    }
                    os.write("data: {\"id\":\"c2\",\"choices\":[{\"delta\":{\"content\":\" welt\"}}]}\n\n"
                            .getBytes(StandardCharsets.UTF_8));
                    os.flush();
                    os.write("data: [DONE]\n\n".getBytes(StandardCharsets.UTF_8));
                    os.flush();
                }
                return;
            }
            // ERROR bodies deliberately carry the sentinels: ANY verbatim relay of
            // an upstream error body would light up the leak assertions. Success
            // bodies are served clean (they ARE relayed — that is the product).
            String sentinel = (stubStatus < 200 || stubStatus >= 300)
                    ? " LEAKSENTINEL_KEY=" + KEY + " LEAKSENTINEL_HOST="
                            + exchange.getLocalAddress().getHostString() + ":" + exchange.getLocalAddress().getPort()
                    : "";
            byte[] out = (stubBody + sentinel).getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", stubContentType);
            if (stubRetryAfter != null) {
                exchange.getResponseHeaders().add("Retry-After", stubRetryAfter);
            }
            exchange.sendResponseHeaders(stubStatus, out.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(out);
            }
        });
        upstream.start();
        logAppender = new ListAppender<>();
        logAppender.start();
        ((Logger) LoggerFactory.getLogger("com.keplura")).addAppender(logAppender);
    }

    @AfterAll
    static void stopMockUpstream() {
        ((Logger) LoggerFactory.getLogger("com.keplura")).detachAppender(logAppender);
        upstream.stop(0);
    }

    private void resetUpstream() {
        stubStatus = 200;
        stubBody = "{}";
        stubContentType = "application/json";
        stubStream = false;
        stubStreamAbortIdle = false;
        stubAbortBeforeHeaders = false;
        stubRetryAfter = null;
        upstreamHits.set(0);
        lastAuth.set(null);
        lastBody.set(null);
        lastAccept.set(null);
    }

    private record Session(String cookie, String org) {
    }

    private Session loginWithOrg(String label) {
        String email = label + NAME_SEQ.incrementAndGet() + "@example.com";
        User user = userRepository.saveAndFlush(User.create(email, HASHER.hash("pw-" + label), label));
        ResponseEntity<String> login = rest.exchange(RequestEntity
                .post(URI.create("/api/v1/auth/login"))
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("email", email, "password", "pw-" + label)), String.class);
        assertThat(login.getStatusCode().value()).isEqualTo(200);
        // SEC-2: the full cookie pair (session + csrf) — the gateway POST needs both.
        String cookie = com.keplura.server.auth.CsrfTestSupport.cookiePair(login);
        long orgId = orgService.createOrg(user.getId(), "Org " + label).getId();
        return new Session(cookie, String.valueOf(orgId));
    }

    private ResponseEntity<byte[]> chat(Session session, String jsonBody) {
        return rest.exchange(RequestEntity
                .post(URI.create("/api/v1/gateway/chat/completions"))
                .header(HttpHeaders.COOKIE, session.cookie())
                .header(com.keplura.server.auth.CsrfService.HEADER_NAME,
                        com.keplura.server.auth.CsrfTestSupport.csrfValue(session.cookie()))
                .header(GatewayController.ORG_HEADER, session.org())
                .contentType(MediaType.APPLICATION_JSON)
                .body(jsonBody.getBytes(StandardCharsets.UTF_8)), byte[].class);
    }

    private static String bodyOf(ResponseEntity<byte[]> response) {
        byte[] body = response.getBody();
        return new String(body == null ? new byte[0] : body, StandardCharsets.UTF_8);
    }

    private static String minimalChat(String model) {
        return "{\"model\":\"" + model + "\",\"messages\":[{\"role\":\"user\",\"content\":\"sag hallo\"}]}";
    }

    // ---------- happy path ----------

    @Test
    void relaysJsonAndAttachesServerSideBearerKey() {
        resetUpstream();
        stubBody = "{\"id\":\"chatcmpl-1\",\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"hallo\"}}]}";
        Session session = loginWithOrg("relay");

        ResponseEntity<byte[]> response = chat(session, minimalChat("ok-model"));

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(bodyOf(response)).contains("chatcmpl-1").contains("hallo");
        assertThat(response.getHeaders().getContentType()).isNotNull();
        assertThat(response.getHeaders().getContentType().includes(MediaType.APPLICATION_JSON)).isTrue();
        // the key reaches the upstream — that is its ONLY appearance — and the
        // forwarded body is the canonicalised JSON, with the prompt inside it
        assertThat(lastAuth.get()).isEqualTo("Bearer " + KEY);
        assertThat(lastAccept.get()).isEqualTo("application/json");
        assertThat(lastBody.get()).contains("\"model\":\"ok-model\"").contains("sag hallo");
        assertNoSentinelsAnywhere(response);
    }

    @Test
    void statusReportsOnlyTheConfiguredFlag() {
        resetUpstream();
        Session session = loginWithOrg("stat");

        ResponseEntity<byte[]> response = rest.exchange(RequestEntity
                .get(URI.create("/api/v1/gateway/status"))
                .header(HttpHeaders.COOKIE, session.cookie())
                .header(GatewayController.ORG_HEADER, session.org())
                .build(), byte[].class);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(bodyOf(response)).isEqualTo("{\"configured\":true}");
        assertNoSentinelsAnywhere(response);
    }

    @Test
    void disallowedModelIsRejectedBeforeAnyUpstreamCall() {
        resetUpstream();
        Session session = loginWithOrg("modelgate");

        ResponseEntity<byte[]> response = chat(session, minimalChat("other-model"));

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(bodyOf(response)).contains("model is not allowed");
        assertThat(upstreamHits.get()).isZero();
    }

    @Test
    void oversizedAndMalformedRequestsFailLocallyWithZeroUpstreamHits() {
        resetUpstream();
        Session session = loginWithOrg("caps");

        ResponseEntity<byte[]> tooBig = chat(session,
                "{\"model\":\"ok-model\",\"messages\":[{\"role\":\"user\",\"content\":\"" + "x".repeat(5000) + "\"}]}");
        assertThat(tooBig.getStatusCode().value()).isEqualTo(413);

        ResponseEntity<byte[]> badJson = chat(session, "{not json");
        assertThat(badJson.getStatusCode().value()).isEqualTo(400);

        ResponseEntity<byte[]> noModel = chat(session,
                "{\"messages\":[{\"role\":\"user\",\"content\":\"x\"}]}");
        assertThat(noModel.getStatusCode().value()).isEqualTo(400);

        ResponseEntity<byte[]> badRole = chat(session,
                "{\"model\":\"ok-model\",\"messages\":[{\"role\":\"wizard\",\"content\":\"x\"}]}");
        assertThat(badRole.getStatusCode().value()).isEqualTo(400);

        ResponseEntity<byte[]> tools = chat(session,
                "{\"model\":\"ok-model\",\"tools\":[],\"messages\":[{\"role\":\"user\",\"content\":\"x\"}]}");
        assertThat(tools.getStatusCode().value()).isEqualTo(400);

        assertThat(upstreamHits.get()).isZero();
        assertNoSentinelsAnywhere(badJson);
    }

    // ---------- SSE relay ----------

    @Test
    void relaysSseStreamFrameForFrame() {
        resetUpstream();
        stubStream = true;
        Session session = loginWithOrg("streamer");

        ResponseEntity<byte[]> response = chat(session,
                "{\"model\":\"ok-model\",\"stream\":true,\"messages\":[{\"role\":\"user\",\"content\":\"sag hallo\"}]}");

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getHeaders().getContentType().toString()).startsWith("text/event-stream");
        String body = bodyOf(response);
        assertThat(body).contains("data: {\"id\":\"c1\"").contains("data: {\"id\":\"c2\"")
                .contains("data: [DONE]");
        assertThat(lastAccept.get()).isEqualTo("text/event-stream");
        assertNoSentinelsAnywhere(response);
    }

    @Test
    void silentUpstreamStreamIsKilledByIdleWatchdogAfterRelayingWhatArrived() {
        resetUpstream();
        stubStream = true;
        stubStreamAbortIdle = true;
        Session session = loginWithOrg("silen");

        ResponseEntity<byte[]> response = chat(session,
                "{\"model\":\"ok-model\",\"stream\":true,\"messages\":[{\"role\":\"user\",\"content\":\"sag hallo\"}]}");

        // first frame made it through, the silence killed the transfer, and the
        // missing [DONE] is the honest "incomplete" signal (AC: no hang forever)
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        String body = bodyOf(response);
        assertThat(body).contains("data: {\"id\":\"c1\"").doesNotContain("[DONE]");
        assertNoSentinelsAnywhere(response);
    }

    // ---------- error mapping ----------

    @Test
    void upstream429MapsTo429WithSanitizedBodyAndRetryAfter() {
        resetUpstream();
        stubStatus = 429;
        stubBody = "{\"error\":\"slow down\"}";
        stubRetryAfter = "7";
        Session session = loginWithOrg("rl");

        ResponseEntity<byte[]> response = chat(session, minimalChat("ok-model"));

        assertThat(response.getStatusCode().value()).isEqualTo(429);
        assertThat(bodyOf(response)).doesNotContain("slow down").doesNotContain("LEAKSENTINEL")
                .contains("model gateway rate limited");
        assertThat(response.getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isEqualTo("7");
        assertNoSentinelsAnywhere(response);
    }

    @Test
    void upstream500MapsTo502WithoutRelayingBodyOrHeaders() {
        resetUpstream();
        stubStatus = 500;
        stubBody = "{\"error\":\"internal upstream detail\"}";
        Session session = loginWithOrg("boom");

        ResponseEntity<byte[]> response = chat(session, minimalChat("ok-model"));

        assertThat(response.getStatusCode().value()).isEqualTo(502);
        assertThat(bodyOf(response)).doesNotContain("internal upstream detail").doesNotContain("LEAKSENTINEL")
                .contains("model gateway upstream failure");
        assertNoSentinelsAnywhere(response);
    }

    @Test
    void upstreamAuthFailureIsAlsoBadGatewayNotEchoedAsUnauthorized() {
        resetUpstream();
        stubStatus = 401;
        stubBody = "{\"error\":\"invalid api key\"}";
        Session session = loginWithOrg("upauth");

        ResponseEntity<byte[]> response = chat(session, minimalChat("ok-model"));

        // a 401 would falsely tell the SESSION holder to log in again
        assertThat(response.getStatusCode().value()).isEqualTo(502);
        assertThat(bodyOf(response)).doesNotContain("invalid api key");
        assertNoSentinelsAnywhere(response);
    }

    @Test
    void upstreamDyingBeforeHeadersMapsTo502() {
        resetUpstream();
        stubAbortBeforeHeaders = true;
        Session session = loginWithOrg("dying");

        ResponseEntity<byte[]> response = chat(session, minimalChat("ok-model"));

        assertThat(response.getStatusCode().value()).isEqualTo(502);
        assertThat(bodyOf(response)).contains("model gateway upstream unreachable");
        assertNoSentinelsAnywhere(response);
    }

    @Test
    void wrongSuccessContentTypeIsTreatedAsUpstreamFailure() {
        resetUpstream();
        stubContentType = "text/plain";
        stubBody = "hello plain";
        Session session = loginWithOrg("ctype");

        ResponseEntity<byte[]> response = chat(session, minimalChat("ok-model"));

        assertThat(response.getStatusCode().value()).isEqualTo(502);
        assertThat(bodyOf(response)).doesNotContain("hello plain");
    }

    @Test
    void upstream429DateRetryAfterIsDroppedNotRelayed() {
        resetUpstream();
        stubStatus = 429;
        stubBody = "{\"error\":\"slow down\"}";
        stubRetryAfter = "Wed, 21 Oct 2015 07:28:00 GMT"; // non-numeric: must be DROPPED
        Session session = loginWithOrg("rldate");

        ResponseEntity<byte[]> response = chat(session, minimalChat("ok-model"));

        assertThat(response.getStatusCode().value()).isEqualTo(429);
        assertThat(response.getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isNull();
        assertNoSentinelsAnywhere(response);
    }

    // ---------- auth & tenancy ----------

    @Test
    void gatewayRoutesRequireSession() {
        resetUpstream();
        ResponseEntity<byte[]> anon = rest.exchange(RequestEntity
                .post(URI.create("/api/v1/gateway/chat/completions"))
                .contentType(MediaType.APPLICATION_JSON)
                .body(minimalChat("ok-model").getBytes(StandardCharsets.UTF_8)), byte[].class);
        assertThat(anon.getStatusCode().value()).isEqualTo(401);

        ResponseEntity<byte[]> anonStatus = rest.exchange(
                RequestEntity.get(URI.create("/api/v1/gateway/status")).build(), byte[].class);
        assertThat(anonStatus.getStatusCode().value()).isEqualTo(401);
        // the 401 comes before any upstream touch
        assertThat(upstreamHits.get()).isZero();
    }

    @Test
    void missingOrGarbageOrgHeaderIs400() {
        resetUpstream();
        Session session = loginWithOrg("nohdr");
        ResponseEntity<byte[]> noHeader = rest.exchange(RequestEntity
                .post(URI.create("/api/v1/gateway/chat/completions"))
                .header(HttpHeaders.COOKIE, session.cookie())
                .header(com.keplura.server.auth.CsrfService.HEADER_NAME,
                        com.keplura.server.auth.CsrfTestSupport.csrfValue(session.cookie()))
                .contentType(MediaType.APPLICATION_JSON)
                .body(minimalChat("ok-model").getBytes(StandardCharsets.UTF_8)), byte[].class);
        assertThat(noHeader.getStatusCode().value()).isEqualTo(400);
        assertThat(bodyOf(noHeader)).contains("header is required");

        ResponseEntity<byte[]> garbage = rest.exchange(RequestEntity
                .post(URI.create("/api/v1/gateway/chat/completions"))
                .header(HttpHeaders.COOKIE, session.cookie())
                .header(com.keplura.server.auth.CsrfService.HEADER_NAME,
                        com.keplura.server.auth.CsrfTestSupport.csrfValue(session.cookie()))
                .header(GatewayController.ORG_HEADER, "not-a-number")
                .contentType(MediaType.APPLICATION_JSON)
                .body(minimalChat("ok-model").getBytes(StandardCharsets.UTF_8)), byte[].class);
        assertThat(garbage.getStatusCode().value()).isEqualTo(400);
    }

    @Test
    void foreignOrgIsIndistinguishableFromUnknownOrg() {
        resetUpstream();
        Session victim = loginWithOrg("victim");
        Session attacker = loginWithOrg("mallory");

        ResponseEntity<byte[]> foreign = rest.exchange(RequestEntity
                .post(URI.create("/api/v1/gateway/chat/completions"))
                .header(HttpHeaders.COOKIE, attacker.cookie())
                .header(com.keplura.server.auth.CsrfService.HEADER_NAME,
                        com.keplura.server.auth.CsrfTestSupport.csrfValue(attacker.cookie()))
                .header(GatewayController.ORG_HEADER, victim.org())
                .contentType(MediaType.APPLICATION_JSON)
                .body(minimalChat("ok-model").getBytes(StandardCharsets.UTF_8)), byte[].class);
        assertThat(foreign.getStatusCode().value()).isEqualTo(404);

        ResponseEntity<byte[]> unknown = rest.exchange(RequestEntity
                .post(URI.create("/api/v1/gateway/chat/completions"))
                .header(HttpHeaders.COOKIE, attacker.cookie())
                .header(com.keplura.server.auth.CsrfService.HEADER_NAME,
                        com.keplura.server.auth.CsrfTestSupport.csrfValue(attacker.cookie()))
                .header(GatewayController.ORG_HEADER, "999999999")
                .contentType(MediaType.APPLICATION_JSON)
                .body(minimalChat("ok-model").getBytes(StandardCharsets.UTF_8)), byte[].class);
        assertThat(unknown.getStatusCode().value()).isEqualTo(404);
        // identical body -> the endpoint cannot probe which org ids exist
        assertThat(bodyOf(unknown)).isEqualTo(bodyOf(foreign));
        assertThat(bodyOf(foreign)).contains("organization not found");
        assertThat(upstreamHits.get()).isZero();
    }

    // ---------- AC-2 sentinel battery ----------

    private void assertNoSentinelsAnywhere(ResponseEntity<byte[]> response) {
        assertThat(bodyOf(response))
                .as("response body must never carry the key, upstream host, or upstream content")
                .doesNotContain(KEY).doesNotContain(UPSTREAM_HOST_PORT).doesNotContain("LEAKSENTINEL");
        assertThat(response.getHeaders().toString())
                .as("response headers must never carry the key or upstream host")
                .doesNotContain(KEY).doesNotContain(UPSTREAM_HOST_PORT);
        List<String> lines = logAppender.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
        assertThat(String.join("\n", lines))
                .as("every com.keplura log line stays free of key material and the upstream address")
                .doesNotContain(KEY).doesNotContain(UPSTREAM_HOST_PORT).doesNotContain("LEAKSENTINEL");
    }
}
