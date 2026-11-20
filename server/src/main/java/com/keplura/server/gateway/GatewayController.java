package com.keplura.server.gateway;

import com.keplura.server.auth.AuthPrincipal;
import com.keplura.server.auth.AuthService;
import com.keplura.server.org.OrgService;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * The M4a model gateway: an OpenAI-compatible chat-completions proxy that keeps
 * every upstream credential SERVER-SIDE — the desktop must never hold an API
 * key. Two routes, both session-gated ({@code SessionAuthConfig}) AND
 * org-scoped: the caller names its organization through the
 * {@code X-Keplura-Org} header and must be a member of it; a foreign or
 * unknown org id answers the uniform 404 (ADR-0004), so this surface cannot
 * probe tenant structure either.
 *
 * <p>Request handling is bytes-in-bytes-out: the raw body is capped,
 * whitelisted and canonicalised by {@link ChatRequestValidator} (nothing
 * unseen reaches the upstream), and the reply is relayed as-is.
 *
 * <p>The chat route writes the response DIRECTLY instead of returning a body
 * type on purpose: a successful relay must stream raw SSE frames (Spring's
 * converter layer would re-frame or re-buffer them, and a wildcard
 * {@code ResponseEntity<?>} is not matched by the streaming return-value
 * handler at all). Every error decision — 400/404/413/502/503 — is reached
 * BEFORE the first byte is written, so the {@link GatewayErrorAdvice} still
 * owns the error shape; once frames flow, the only failure mode left is
 * truncation (an absent {@code data: [DONE]}), which IS the honest signal.
 *
 * <p>Deliberate MVP limits: a stream occupies one servlet thread for its
 * lifetime (no async dispatch, no backpressure games — the bounds are
 * timeouts and byte caps), no chat persistence, no token accounting.
 */
@RestController
@RequestMapping("/api/v1/gateway")
public class GatewayController {

    /** The org the caller wants to spend the shared model key for. */
    public static final String ORG_HEADER = "X-Keplura-Org";

    /** Status projection: the ONLY outward-facing fact about configuration. */
    public record GatewayStatusResponse(boolean configured) {
    }

    private static final Logger log = LoggerFactory.getLogger(GatewayController.class);

    private final GatewayRuntime runtime;
    private final GatewayProperties props;
    private final GatewayClient client;
    private final OrgService orgService;
    private final ChatRequestValidator validator;

    public GatewayController(GatewayRuntime runtime, GatewayClient client, OrgService orgService) {
        this.runtime = runtime;
        // Boot/ENV defaults — request handlers re-resolve per call (ADMIN-KI);
        // the caps (bytes/messages) never come from the DB, so validator and
        // body-cap reads legitimately stay on the ENV instance.
        this.props = runtime.envProperties();
        this.client = client;
        this.orgService = orgService;
        this.validator = new ChatRequestValidator(props);
    }

    @GetMapping("/status")
    public ResponseEntity<GatewayStatusResponse> status(HttpServletRequest httpRequest) {
        requireOrgMembership(httpRequest);
        // ADMIN-KI: report the EFFECTIVE state (DB rows win over ENV).
        return ResponseEntity.ok(new GatewayStatusResponse(runtime.effective().configured()));
    }

    @PostMapping("/chat/completions")
    public void chat(HttpServletRequest httpRequest, HttpServletResponse httpResponse)
            throws IOException {
        requireOrgMembership(httpRequest);
        GatewayProperties effective = runtime.effective();
        if (!effective.configured()) {
            throw new GatewayException(HttpStatus.SERVICE_UNAVAILABLE, "model gateway not configured");
        }
        // ADMIN-KI: the DB-enabled model rows replace the ENV allow-list gate
        // when DB config drives the request (effective() already carries them);
        // the ENV pin stands otherwise.
        ChatRequestValidator activeValidator = runtime.dbChatActive()
                ? new ChatRequestValidator(effective)
                : validator;
        byte[] raw = readBodyCapped(httpRequest);
        ChatRequestValidator.CanonicalRequest request = activeValidator.validate(raw);

        if (request.stream()) {
            streamRelay(request, httpResponse, effective);
        } else {
            GatewayClient.BufferedUpstreamResponse upstream =
                    client.completeJson(request.body(), request.model(), effective);
            httpResponse.setStatus(HttpServletResponse.SC_OK);
            httpResponse.setContentType(MediaType.APPLICATION_JSON_VALUE);
            httpResponse.setCharacterEncoding("UTF-8");
            httpResponse.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
            httpResponse.setContentLength(upstream.body().length);
            httpResponse.getOutputStream().write(upstream.body());
            httpResponse.getOutputStream().flush();
        }
    }

    /**
     * Relays upstream SSE frames verbatim. All mapping decisions happened in
     * {@code openStream} (it throws before returning on any non-2xx), so from
     * here on the response is committed to being a stream: the only failures
     * are IO-level (our watchdog kill, upstream death, client gone) and each
     * ends the transfer early — an incomplete stream without {@code [DONE]}.
     */
    private void streamRelay(ChatRequestValidator.CanonicalRequest request,
                             HttpServletResponse httpResponse,
                             GatewayProperties effective) throws IOException {
        try (GatewayClient.UpstreamStream upstream =
                     client.openStream(request.body(), request.model(), effective)) {
            httpResponse.setStatus(HttpServletResponse.SC_OK);
            httpResponse.setContentType(MediaType.TEXT_EVENT_STREAM_VALUE);
            httpResponse.setCharacterEncoding("UTF-8");
            httpResponse.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
            // proxies (nginx) must NOT buffer an SSE relay into lumps
            httpResponse.setHeader("X-Accel-Buffering", "no");

            OutputStream out = httpResponse.getOutputStream();
            InputStream in = upstream.body();
            byte[] buffer = new byte[8192];
            long total = 0;
            int n;
            while ((n = in.read(buffer)) > 0) {
                total += n;
                if (total > props.maxResponseBytes()) {
                    log.warn("gateway stream hit response cap (model={})", request.model());
                    return; // cap: end without [DONE]; upstream socket closes with the try
                }
                out.write(buffer, 0, n);
                out.flush(); // SSE: every frame should leave immediately
            }
        } catch (IOException e) {
            // post-commit IO failure: client disconnect, watchdog kill or upstream
            // death. Nothing to map (the response is a stream); class name only —
            // JDK IO messages can embed the upstream host.
            log.warn("gateway stream ended early (model={}, cause={})",
                    request.model(), e.getClass().getSimpleName());
        }
    }

    // ---- guards ----

    /**
     * Session principal (the interceptor already 401-gated; this is the
     * wiring-consistency guard like every other controller) PLUS membership
     * in the org named by {@value #ORG_HEADER} — resolved through
     * {@link OrgService#getMyOrg}, the same two-guard lookup the orgs API
     * uses. Foreign org and unknown org are indistinguishable (uniform 404).
     */
    private void requireOrgMembership(HttpServletRequest httpRequest) {
        HttpSession session = httpRequest.getSession(false);
        Object principal = (session == null) ? null : session.getAttribute(AuthService.SESSION_PRINCIPAL_ATTR);
        if (!(principal instanceof AuthPrincipal p)) {
            throw new IllegalStateException("gateway request reached controller without session principal");
        }
        String raw = httpRequest.getHeader(ORG_HEADER);
        if (raw == null || raw.isBlank()) {
            throw new GatewayException(HttpStatus.BAD_REQUEST, ORG_HEADER + " header is required");
        }
        long orgId;
        try {
            orgId = Long.parseLong(raw.trim());
        } catch (NumberFormatException e) {
            throw new GatewayException(HttpStatus.BAD_REQUEST, "org header is invalid");
        }
        if (orgId < 1) {
            throw new GatewayException(HttpStatus.BAD_REQUEST, "org header is invalid");
        }
        try {
            orgService.getMyOrg(p.userId(), orgId);
        } catch (OrgService.OrgNotFoundException e) {
            throw new GatewayException(HttpStatus.NOT_FOUND, "organization not found");
        }
    }

    private byte[] readBodyCapped(HttpServletRequest request) throws IOException {
        long declared = request.getContentLengthLong();
        if (declared > props.maxRequestBytes()) {
            throw new GatewayException(HttpStatus.PAYLOAD_TOO_LARGE, "request body too large");
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream(4096);
        byte[] buffer = new byte[8192];
        long total = 0;
        int n;
        try (InputStream in = request.getInputStream()) {
            while ((n = in.read(buffer)) > 0) {
                total += n;
                if (total > props.maxRequestBytes()) {
                    throw new GatewayException(HttpStatus.PAYLOAD_TOO_LARGE, "request body too large");
                }
                out.write(buffer, 0, n);
            }
        }
        return out.toByteArray();
    }
}
