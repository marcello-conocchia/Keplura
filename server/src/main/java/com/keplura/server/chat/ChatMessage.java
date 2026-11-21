package com.keplura.server.chat;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * One chat message (M4b) — user prompt echo or assistant answer, appended to
 * a {@link Conversation}. Rows are immutable: the assistant answer is
 * persisted ONCE, when its stream ends — whether that end is clean, capped,
 * aborted or failed; the honest end state rides on {@code status}. A server
 * crash mid-turn therefore leaves the user row without an answer row, which
 * the transcript renders truthfully (no half-persisted content exists).
 *
 * <p>No tenant pins are duplicated here: the conversation carries them and
 * every read reaches messages ONLY through a conversation id the service
 * already resolved through {@link ChatRepository#findVisible}.
 */
@Entity
@Table(name = "chat_message",
        indexes = @Index(name = "chat_message_conversation_id_idx",
                columnList = "conversation_id"))
public class ChatMessage {

    /** V6 column bound; the API edge caps prompts well below this. */
    public static final int MAX_CONTENT_LENGTH = 32000;

    /** V6 column bound for the citation JSON array. */
    public static final int MAX_CITATIONS_LENGTH = 16000;

    public static final String ROLE_USER = "user";
    public static final String ROLE_ASSISTANT = "assistant";

    /** Stream finished upstream-clean ([DONE] seen). */
    public static final String STATUS_COMPLETE = "complete";
    /** Client pressed stop mid-stream (kept as a partial, honest answer). */
    public static final String STATUS_ABORTED = "aborted";
    /** Gateway/upstream/IO failure before any usable answer text arrived. */
    public static final String STATUS_FAILED = "failed";
    /** Watchdog (idle/deadline), byte cap, or mid-stream death ended it. */
    public static final String STATUS_INCOMPLETE = "incomplete";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "conversation_id", nullable = false, updatable = false)
    private Long conversationId;

    @Column(name = "prompt_message_id", updatable = false)
    private Long promptMessageId;

    public Long getPromptMessageId() { return promptMessageId; }

    /** user | assistant — set only by the two factories below, never free text. */
    @Column(nullable = false, updatable = false, length = 16)
    private String role;

    /** The prompt echo or streamed answer; "" only for a failed empty turn. */
    @Column(nullable = false, updatable = false, length = MAX_CONTENT_LENGTH)
    private String content;

    /** Gateway model tag of an assistant answer ("" for user rows). */
    @Column(nullable = false, updatable = false, length = 128)
    private String model;

    /** JSON array of citation entries; "[]" when the turn had no kb context. */
    @Column(name = "citations_json", nullable = false, updatable = false,
            length = MAX_CITATIONS_LENGTH)
    private String citationsJson;

    /** One of the STATUS_* constants the stream ended with. */
    @Column(nullable = false, updatable = false, length = 16)
    private String status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /** PILOT 4e: "up" | "down" | null — set only by the conversation owner. */
    @Column(length = 8)
    private String feedback;

    @Column(name = "feedback_at")
    private Instant feedbackAt;

    protected ChatMessage() {
        // JPA proxy constructor
    }

    private ChatMessage(Long conversationId, String role, String content,
                        String model, String citationsJson, String status) {
        this.conversationId = conversationId;
        this.role = role;
        this.content = content;
        this.model = model;
        this.citationsJson = citationsJson;
        this.status = status;
    }

    /** A user prompt, complete at insert (never streams). */
    public static ChatMessage userMessage(Long conversationId, String content) {
        return new ChatMessage(conversationId, ROLE_USER, content, "", "[]", STATUS_COMPLETE);
    }

    /**
     * An assistant answer frozen at stream end. Content and citations are
     * truncated to the DB bounds rather than failing the write — losing the
     * tail of an oversized answer beats losing the whole turn.
     */
    public static ChatMessage assistantAnswer(Long conversationId, String text,
                                              String modelTag, String citationsJson,
                                              String endStatus) {
        String safe = (text == null) ? "" : text;
        if (safe.length() > MAX_CONTENT_LENGTH) {
            safe = safe.substring(0, MAX_CONTENT_LENGTH);
        }
        String cites = (citationsJson == null || citationsJson.isBlank()) ? "[]" : citationsJson;
        if (cites.length() > MAX_CITATIONS_LENGTH) {
            cites = "[]"; // oversized citation blob: honest fallback, never half JSON
        }
        String model = (modelTag == null) ? "" : modelTag.substring(0, Math.min(modelTag.length(), 128));
        return new ChatMessage(conversationId, ROLE_ASSISTANT, safe, model, cites, endStatus);
    }

    public static ChatMessage assistantAnswer(Long conversationId, String text, String modelTag,
            String citationsJson, String endStatus, Long promptId) {
        ChatMessage answer = assistantAnswer(conversationId, text, modelTag, citationsJson, endStatus);
        answer.promptMessageId = promptId;
        return answer;
    }

    @PrePersist
    void stampCreatedAt() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }

    public Long getId() {
        return id;
    }

    public Long getConversationId() {
        return conversationId;
    }

    public String getRole() {
        return role;
    }

    public String getContent() {
        return content;
    }

    public String getModel() {
        return model;
    }

    public String getCitationsJson() {
        return citationsJson;
    }

    public String getStatus() {
        return status;
    }

    public String getFeedback() {
        return feedback;
    }

    /** Sets or clears the owner's rating (null clears). */
    public void rate(String rating) {
        if (rating != null && !"up".equals(rating) && !"down".equals(rating)) {
            throw new IllegalArgumentException("rating must be up, down or null");
        }
        this.feedback = rating;
        this.feedbackAt = rating == null ? null : Instant.now();
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
