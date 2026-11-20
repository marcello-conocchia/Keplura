package com.keplura.server.chat;

import com.keplura.server.auth.AuthPrincipal;
import com.keplura.server.auth.AuthService;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The M4b chat API (desktop-facing, session-gated by living under the
 * protected {@code /api/v1/orgs/**} prefix — same interceptor + CORS mapping
 * the knowledge UI already uses; no new auth surface):
 *
 * <ul>
 *   <li>{@code GET  /api/v1/orgs/{orgId}/spaces/{spaceId}/chat/conversations}
 *       — my threads in one space, newest activity first</li>
 *   <li>{@code POST /api/v1/orgs/{orgId}/spaces/{spaceId}/chat/conversations}
 *       — create {knowledgeBaseId?, title?} (the kb pins the RAG context)</li>
 *   <li>{@code GET  /api/v1/orgs/{orgId}/chat/conversations/{id}} — transcript
 *       with per-answer citations + honest end status</li>
 *   <li>{@code DELETE /api/v1/orgs/{orgId}/chat/conversations/{id}} — hard delete</li>
 *   <li>{@code POST /api/v1/orgs/{orgId}/chat/conversations/{id}/stream}
 *       — the turn: {prompt} in, SSE out (upstream frames verbatim +
 *       chat-only {@code citations}/{@code turn} trailer events)</li>
 * </ul>
 *
 * <p>The stream route writes the response DIRECTLY like the M4a gateway does
 * and for the same reasons: Spring's converter layer would re-frame raw SSE,
 * and every error decision (400/401/404/503/429/502) is reached by
 * {@link ChatService#beginTurn} BEFORE the first byte, so
 * {@link ChatErrorAdvice} still owns the JSON error shape. Once frames flow,
 * truncation IS the failure signal (M4a precedent).
 *
 * <p>Abort handling: {@code ResponseBodyEmitter}'s completion callbacks cannot
 * run here (the body is written directly), so the client-gone signal is the
 * IOException on the next relay write — see {@link ChatService#relayTurn}.
 */
@RestController
@RequestMapping("/api/v1/orgs/{orgId}")
public class ChatController {

    private static final Logger log = LoggerFactory.getLogger(ChatController.class);

    /** Error body — same generic shape as every other endpoint here. */
    public record ErrorResponse(String error) {
    }

    /** Create body: optional kb (RAG context) + optional title. */
    public record CreateConversationRequest(Long knowledgeBaseId, String title) {
    }

    private final ChatService chatService;
    private final ChatProperties chatProps;
    private final ObjectMapper mapper = JsonMapper.builder().build();

    public ChatController(ChatService chatService, ChatProperties chatProps) {
        this.chatService = chatService;
        this.chatProps = chatProps;
    }

    @GetMapping("/spaces/{spaceId}/chat/conversations")
    public ResponseEntity<?> list(@PathVariable("orgId") Long orgId,
                                  @PathVariable("spaceId") Long spaceId,
                                  HttpServletRequest httpRequest) {
        AuthPrincipal principal = currentPrincipal(httpRequest);
        List<ChatService.ConversationSummary> items =
                chatService.listConversations(principal.userId(), orgId, spaceId);
        return ResponseEntity.ok(items);
    }

    @PostMapping("/spaces/{spaceId}/chat/conversations")
    public ResponseEntity<?> create(@PathVariable("orgId") Long orgId,
                                    @PathVariable("spaceId") Long spaceId,
                                    @RequestBody(required = false) CreateConversationRequest request,
                                    HttpServletRequest httpRequest) {
        AuthPrincipal principal = currentPrincipal(httpRequest);
        if (request != null && request.title() != null
                && request.title().strip().length() > Conversation.MAX_TITLE_LENGTH) {
            throw new ChatException(HttpStatus.BAD_REQUEST, "title is invalid");
        }
        ChatService.ConversationSummary created = chatService.createConversation(
                principal.userId(), orgId, spaceId,
                request == null ? null : request.knowledgeBaseId(),
                request == null ? null : request.title());
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @GetMapping("/chat/conversations/{conversationId}")
    public ResponseEntity<ChatService.TranscriptDto> transcript(@PathVariable("orgId") Long orgId,
                                                                @PathVariable("conversationId") Long conversationId,
                                                                HttpServletRequest httpRequest) {
        AuthPrincipal principal = currentPrincipal(httpRequest);
        return ResponseEntity.ok(chatService.transcript(principal.userId(), orgId, conversationId));
    }

    /** PILOT 4e: body {"rating": "up" | "down" | null}. */
    public record FeedbackRequest(String rating) {
    }

    public record FeedbackResponse(Long messageId, String rating) {
    }

    @org.springframework.web.bind.annotation.PutMapping("/chat/conversations/{conversationId}/messages/{messageId}/feedback")
    public ResponseEntity<FeedbackResponse> feedback(@PathVariable("orgId") Long orgId,
                                                     @PathVariable("conversationId") Long conversationId,
                                                     @PathVariable("messageId") Long messageId,
                                                     @org.springframework.web.bind.annotation.RequestBody(required = false) FeedbackRequest request,
                                                     HttpServletRequest httpRequest) {
        AuthPrincipal principal = currentPrincipal(httpRequest);
        String rating = chatService.rateMessage(principal.userId(), orgId, conversationId, messageId,
                request == null ? null : request.rating());
        return ResponseEntity.ok(new FeedbackResponse(messageId, rating));
    }

    /** Antwort als Word (.docx) oder PDF herunterladen. */
    @GetMapping("/chat/conversations/{conversationId}/messages/{messageId}/export")
    public ResponseEntity<byte[]> export(@PathVariable("orgId") Long orgId,
                                         @PathVariable("conversationId") Long conversationId,
                                         @PathVariable("messageId") Long messageId,
                                         @org.springframework.web.bind.annotation.RequestParam(value = "format", defaultValue = "docx") String format,
                                         HttpServletRequest httpRequest) throws java.io.IOException {
        AuthPrincipal principal = currentPrincipal(httpRequest);
        var input = chatService.exportInput(principal.userId(), orgId, conversationId, messageId);
        boolean pdf = "pdf".equalsIgnoreCase(format);
        if (!pdf && !"docx".equalsIgnoreCase(format)) {
            throw new ChatException(HttpStatus.BAD_REQUEST, "format must be docx or pdf");
        }
        byte[] body = pdf ? exportService.pdf(input) : exportService.docx(input);
        String name = "Keplura-Antwort-" + java.time.LocalDate.now() + (pdf ? ".pdf" : ".docx");
        return ResponseEntity.ok()
                .header(org.springframework.http.HttpHeaders.CONTENT_TYPE, pdf ? "application/pdf"
                        : "application/vnd.openxmlformats-officedocument.wordprocessingml.document")
                .header(org.springframework.http.HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + name + "\"")
                .header(org.springframework.http.HttpHeaders.CACHE_CONTROL, "no-store")
                .header("X-Content-Type-Options", "nosniff")
                .body(body);
    }

    private AnswerExportService exportService;

    @org.springframework.beans.factory.annotation.Autowired
    void setExportService(AnswerExportService exportService) {
        this.exportService = exportService;
    }

    @DeleteMapping("/chat/conversations/{conversationId}")
    public ResponseEntity<ErrorResponse> delete(@PathVariable("orgId") Long orgId,
                                                @PathVariable("conversationId") Long conversationId,
                                                HttpServletRequest httpRequest) {
        AuthPrincipal principal = currentPrincipal(httpRequest);
        chatService.deleteConversation(principal.userId(), orgId, conversationId);
        return ResponseEntity.noContent().build();
    }

    /**
     * One streaming chat turn. Body: {@code {"prompt": "..."}} — read with a
     * byte bound derived from the prompt cap (never a model-request passthrough:
     * only the prompt text is taken from this body; the upstream request is
     * built server-side).
     */
    @PostMapping("/chat/conversations/{conversationId}/stream")
    public void stream(@PathVariable("orgId") Long orgId,
                       @PathVariable("conversationId") Long conversationId,
                       HttpServletRequest httpRequest,
                       HttpServletResponse httpResponse) throws IOException {
        AuthPrincipal principal = currentPrincipal(httpRequest);
        JsonNode body = readBody(httpRequest);
        String prompt = promptOf(body);
        boolean webSearch = body.path("webSearch").asBoolean(false);

        // ALL clean-JSON-error decisions happen inside beginTurn; a throw
        // here still owns an uncommitted response → ChatErrorAdvice maps it.
        ChatService.StreamedTurn turn =
                chatService.beginTurn(principal.userId(), orgId, conversationId, prompt, webSearch);

        httpResponse.setStatus(HttpServletResponse.SC_OK);
        httpResponse.setContentType(MediaType.TEXT_EVENT_STREAM_VALUE);
        httpResponse.setCharacterEncoding("UTF-8");
        httpResponse.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        httpResponse.setHeader("X-Accel-Buffering", "no");

        OutputStream out = httpResponse.getOutputStream();
        AtomicBoolean aborted = new AtomicBoolean(false);
        try {
            chatService.relayTurn(turn, out, aborted);
        } finally {
            // relayTurn closes the upstream; this guard covers a throw before
            // it could (e.g. an OutOfMemory during the pump)
            try {
                turn.upstream().close();
            } catch (RuntimeException ignored) {
                // already closed by the relay — nothing actionable
            }
        }
        if (aborted.get()) {
            log.debug("chat stream aborted by client (conversation={})", conversationId);
        }
    }

    // ---- request shaping ----

    private JsonNode readBody(HttpServletRequest request) throws IOException {
        // raw bound: prompt cap worst case is ~4x bytes in UTF-8 JSON escapes,
        // + framing — a body beyond this is framing noise, never a real prompt
        int bound = (int) Math.min(4L * chatProps.maxPromptChars() + 4096, Integer.MAX_VALUE);
        byte[] raw = readBodyCapped(request, bound);
        JsonNode root;
        try {
            root = mapper.readTree(raw);
        } catch (RuntimeException e) {
            // Jackson messages can quote input content — stay generic (M4a rule)
            throw new ChatException(HttpStatus.BAD_REQUEST, "request body is not valid JSON");
        }
        if (root == null || !root.isObject()) {
            throw new ChatException(HttpStatus.BAD_REQUEST, "prompt must be a string");
        }
        return root;
    }

    private static String promptOf(JsonNode root) {
        JsonNode prompt = root.path("prompt");
        if (prompt == null || !prompt.isString()) {
            throw new ChatException(HttpStatus.BAD_REQUEST, "prompt must be a string");
        }
        return prompt.stringValue();
    }

    /**
     * Bounded streaming read — the {@code GatewayController.readBodyCapped}
     * (M4a, security-approved) precedent applied to the chat prompt body: the
     * declared length is checked upfront AND the running total is checked on
     * every chunk, so a {@code Transfer-Encoding: chunked} body (which hides
     * the declared length) can never be buffered whole into the heap. The
     * server stops reading at {@code bound} and answers the clean 400 without
     * consuming the rest of the body.
     */
    private byte[] readBodyCapped(HttpServletRequest request, int bound) throws IOException {
        long declared = request.getContentLengthLong();
        if (declared > bound) {
            throw new ChatException(HttpStatus.BAD_REQUEST, "request body too large");
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream(4096);
        byte[] buffer = new byte[8192];
        long total = 0;
        int n;
        try (InputStream in = request.getInputStream()) {
            while ((n = in.read(buffer)) > 0) {
                total += n;
                if (total > bound) {
                    throw new ChatException(HttpStatus.BAD_REQUEST, "request body too large");
                }
                out.write(buffer, 0, n);
            }
        }
        return out.toByteArray();
    }

    /** Session principal guard — identical pattern as every protected controller. */
    private AuthPrincipal currentPrincipal(HttpServletRequest httpRequest) {
        HttpSession session = httpRequest.getSession(false);
        Object principal = (session == null) ? null
                : session.getAttribute(AuthService.SESSION_PRINCIPAL_ATTR);
        if (principal instanceof AuthPrincipal p) {
            return p;
        }
        throw new IllegalStateException("chat request reached controller without session principal");
    }
}
