package com.keplura.server.chat;

import com.keplura.server.auth.AuthPrincipal;
import com.keplura.server.auth.AuthService;
import com.keplura.server.tool.ToolExecutionService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.DeserializationFeature;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;

/**
 * Kontext einer Unterhaltung: zusätzliche Wissensbasen und angehängte Dateien.
 * Liegt unter {@code /api/v1/orgs/**} (Session + CSRF). Fremde Unterhaltungen → 404.
 */
@RestController
@RequestMapping("/api/v1/orgs/{orgId}/chat/conversations/{conversationId}")
public class ChatContextController {
    // 32 IDs of 512 UTF-16 units, at up to six bytes per JSON escape, plus framing.
    private static final int MAX_SELECTION_BODY_BYTES = 100 * 1024;
    private static final JsonMapper SELECTION_JSON = JsonMapper.builder()
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY)
            .build();

    public record KnowledgeBasesRequest(List<Long> knowledgeBaseIds) {
    }

    private final ChatContextService service;
    private final ToolExecutionService tools;

    public ChatContextController(ChatContextService service, ToolExecutionService tools) {
        this.service = service;
        this.tools = tools;
    }

    @GetMapping("/tool-selection")
    public ToolExecutionService.ChatToolSelection toolSelection(@PathVariable("orgId") Long orgId,
                                                               @PathVariable("conversationId") Long cid,
                                                               HttpServletRequest request) {
        return tools.chatToolSelection(user(request), orgId, cid);
    }

    @PutMapping("/tool-selection")
    public ToolExecutionService.ChatToolSelection setToolSelection(@PathVariable("orgId") Long orgId,
                                                                  @PathVariable("conversationId") Long cid,
                                                                  HttpServletRequest request) throws IOException {
        Long userId = user(request);
        if (request.getContentLengthLong() > MAX_SELECTION_BODY_BYTES) throw selectionBodyTooLarge();
        byte[] bytes = request.getInputStream().readNBytes(MAX_SELECTION_BODY_BYTES + 1);
        if (bytes.length > MAX_SELECTION_BODY_BYTES) throw selectionBodyTooLarge();
        JsonNode body;
        try {
            body = SELECTION_JSON.readTree(bytes);
        } catch (RuntimeException malformed) {
            throw new ChatException(HttpStatus.BAD_REQUEST, "request body is not valid JSON");
        }
        return tools.setChatToolSelection(userId, orgId, cid, body);
    }

    private static ChatException selectionBodyTooLarge() {
        return new ChatException(HttpStatus.PAYLOAD_TOO_LARGE, "request body too large");
    }

    @GetMapping("/context")
    public ChatContextService.ContextView context(@PathVariable("orgId") Long orgId,
                                                  @PathVariable("conversationId") Long cid,
                                                  HttpServletRequest request) {
        return service.context(user(request), orgId, cid);
    }

    @PutMapping("/knowledge-bases")
    public ChatContextService.ContextView setKnowledgeBases(@PathVariable("orgId") Long orgId,
                                                            @PathVariable("conversationId") Long cid,
                                                            @RequestBody(required = false) KnowledgeBasesRequest in,
                                                            HttpServletRequest request) {
        return service.setExtraKnowledgeBases(user(request), orgId, cid, in == null ? null : in.knowledgeBaseIds());
    }

    @PostMapping("/attachments")
    public ResponseEntity<ChatContextService.AttachmentView> upload(@PathVariable("orgId") Long orgId,
                                                                    @PathVariable("conversationId") Long cid,
                                                                    @RequestParam("file") MultipartFile file,
                                                                    HttpServletRequest request) throws IOException {
        if (file.isEmpty()) throw new ChatException(HttpStatus.BAD_REQUEST, "file is empty");
        try (InputStream in = file.getInputStream()) {
            return ResponseEntity.status(HttpStatus.CREATED)
                    .body(service.addAttachment(user(request), orgId, cid, file.getOriginalFilename(), in));
        }
    }

    @DeleteMapping("/attachments/{attachmentId}")
    public ResponseEntity<Void> deleteAttachment(@PathVariable("orgId") Long orgId,
                                                 @PathVariable("conversationId") Long cid,
                                                 @PathVariable("attachmentId") Long attachmentId,
                                                 HttpServletRequest request) {
        service.deleteAttachment(user(request), orgId, cid, attachmentId);
        return ResponseEntity.noContent().build();
    }

    private static Long user(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        Object p = session == null ? null : session.getAttribute(AuthService.SESSION_PRINCIPAL_ATTR);
        if (p instanceof AuthPrincipal ap) return ap.userId();
        throw new IllegalStateException("protected request without principal");
    }
}
