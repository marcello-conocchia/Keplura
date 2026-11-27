package com.keplura.server.tool;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * TU-06d: image and file results of a tool call ({@code tool_artifact}). Only bytes whose type
 * {@link ArtifactInspector} verified are stored; everything over the limits is dropped and noted on the card. The
 * model only ever gets a text placeholder (type, name, size) — never bytes and never an image.
 */
@Component
public class ToolArtifactStore {

    public static final int MAX_PER_CALL = 4;
    public static final long MAX_BYTES = 5L * 1024 * 1024;
    public static final long MAX_PER_QUESTION = 10L * 1024 * 1024;

    /** As a card shows it: no bytes. {@code droppedReason != null} = not stored. */
    public record View(String id, String name, String mediaType, long size, boolean image, String droppedReason) { }

    /** Bytes for the owner-checked download. */
    public record Download(String name, String mediaType, byte[] data) { }

    private final JdbcTemplate jdbc;

    public ToolArtifactStore(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    /** Stores the call's artifacts and returns the placeholder text for the model ("" if there are none). */
    public String store(ToolCallStore.Row card, List<ToolAdapter.Artifact> artifacts, boolean internal) {
        if (artifacts == null || artifacts.isEmpty()) return "";
        StringBuilder notes = new StringBuilder();
        long used = card.promptMessageId() == null ? 0 : usedByQuestion(card.conversationId(), card.promptMessageId());
        int index = 0;
        int extra = Math.max(0, artifacts.size() - MAX_PER_CALL);
        for (ToolAdapter.Artifact a : artifacts.subList(0, Math.min(artifacts.size(), MAX_PER_CALL))) {
            index++;
            byte[] data = a == null ? null : a.data();
            String reason;
            ArtifactInspector.Verdict verdict = null;
            if (a == null || a.invalidReason() != null) reason = a == null ? "Die Datei ist ungültig." : a.invalidReason();
            else if (data == null || data.length == 0) reason = "Die Datei ist leer.";
            else if (data.length > MAX_BYTES) reason = "Die Datei ist größer als 5 MB.";
            else if (used + data.length > MAX_PER_QUESTION) reason = "Die Dateien dieser Frage überschreiten zusammen 10 MB.";
            else {
                verdict = ArtifactInspector.inspect(a.declaredType(), data);
                reason = verdict.reason();
            }
            String type = reason == null ? verdict.type() : null;
            String name = name(a == null ? null : a.name(), type, index);
            if (reason != null) {
                insert(card, name, null, data == null ? 0 : data.length, null, null, reason, internal);
                notes.append("\n[Datei verworfen: ").append(name).append(" – ").append(reason).append(']');
                continue;
            }
            used += data.length;
            insert(card, name, type, data.length, sha256(data), data, null, internal);
            notes.append("\n[Datei-Ergebnis: ").append(name).append(" (").append(type).append(", ").append(human(data.length))
                    .append(") – auf der Aufrufkarte gespeichert; der Inhalt ist nicht im Modellkontext.]");
        }
        if (extra > 0) {
            String reason = "Mehr als vier Dateien je Aufruf: " + extra + " weitere verworfen.";
            insert(card, "weitere-dateien.bin", null, 0, null, null, reason, internal);
            notes.append("\n[").append(reason).append(']');
        }
        return notes.toString();
    }

    private void insert(ToolCallStore.Row card, String name, String type, long size, String sha, byte[] data, String reason, boolean internal) {
        jdbc.update("""
                insert into tool_artifact(public_id, tool_call_id, conversation_id, prompt_message_id, user_id, org_id, space_id, name,
                  media_type, size_bytes, sha256, data, dropped_reason, internal, created_at) values (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)""",
                UUID.randomUUID().toString(), card.id(), card.conversationId(), card.promptMessageId(), card.userId(), card.orgId(),
                card.spaceId(), name, type, size, sha, data, reason, internal, Timestamp.from(Instant.now()));
    }

    long usedByQuestion(long conversationId, long promptMessageId) {
        Long sum = jdbc.queryForObject("select coalesce(sum(size_bytes),0) from tool_artifact where conversation_id=? and prompt_message_id=? and data is not null",
                Long.class, conversationId, promptMessageId);
        return sum == null ? 0 : sum;
    }

    public List<View> forCard(long cardId) {
        return jdbc.query("select public_id, name, media_type, size_bytes, dropped_reason from tool_artifact where tool_call_id=? order by id",
                (rs, n) -> new View(rs.getString(1), rs.getString(2), rs.getString(3), rs.getLong(4),
                        rs.getString(3) != null && ArtifactInspector.IMAGES.contains(rs.getString(3)), rs.getString(5)), cardId);
    }

    /**
     * The owner's artifact only: same person, organization and chat, the chat still belongs to the person in that
     * organization (membership, space of the org) and the artifact is stored with a verified type. Else empty (404).
     */
    public Optional<Download> owned(String publicId, long userId, long orgId, long conversationId) {
        if (publicId == null || publicId.length() != 36) return Optional.empty();
        return jdbc.query("""
                select a.name, a.media_type, a.data from tool_artifact a
                join tool_call t on t.id=a.tool_call_id and t.conversation_id=a.conversation_id and t.user_id=a.user_id
                join chat_conversation c on c.id=a.conversation_id and c.org_id=a.org_id and c.created_by=a.user_id and c.space_id=a.space_id
                join space s on s.id=c.space_id and s.org_id=c.org_id
                where a.public_id=? and a.user_id=? and a.org_id=? and a.conversation_id=? and a.data is not null and a.media_type is not null
                and exists(select 1 from membership m where m.org_id=c.org_id and m.user_id=c.created_by)""",
                (rs, n) -> new Download(rs.getString(1), rs.getString(2), rs.getBytes(3)),
                publicId, userId, orgId, conversationId).stream()
                .filter(d -> ArtifactInspector.ALLOWED.contains(d.mediaType())).findFirst();
    }

    public void deleteConversation(long conversationId) {
        jdbc.update("delete from tool_artifact where conversation_id=?", conversationId);
    }

    /**
     * The name is generated HERE with the extension of the VERIFIED type; a foreign name (resource URI, file name)
     * is never used, since it could echo a token or other data into card metadata, the model placeholder or the
     * download name. {@code raw} is ignored on purpose.
     */
    static String name(String raw, String type, int index) {
        String ext = type == null ? "bin" : ArtifactInspector.EXTENSIONS.getOrDefault(type, "bin");
        return "ergebnis-" + index + "." + ext;
    }

    public static final String CREDENTIAL_REASON = "Die Datei enthielt Zugangsdaten und wurde verworfen.";

    /**
     * Credential check before storage: a file whose bytes contain the bearer token of the call (as sent, UTF-8) is
     * never stored or published; it becomes a dropped entry with a fixed reason. Applied by every adapter that
     * sends a credential (MCP, OpenAPI) before handing artifacts on.
     */
    public static List<ToolAdapter.Artifact> withoutCredential(List<ToolAdapter.Artifact> artifacts, String token) {
        if (artifacts == null) return List.of();
        if (artifacts.isEmpty() || token == null || token.isEmpty()) return artifacts;
        byte[] needle = token.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        List<ToolAdapter.Artifact> out = new java.util.ArrayList<>(artifacts.size());
        for (ToolAdapter.Artifact a : artifacts) {
            if (a != null && a.data() != null && contains(a.data(), needle)) {
                out.add(new ToolAdapter.Artifact(null, null, null, CREDENTIAL_REASON));
            } else {
                out.add(a);
            }
        }
        return java.util.Collections.unmodifiableList(out);
    }

    static boolean contains(byte[] haystack, byte[] needle) {
        outer:
        for (int i = 0; i <= haystack.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) if (haystack[i + j] != needle[j]) continue outer;
            return true;
        }
        return false;
    }

    static String human(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return (bytes / 1024) + " KB";
        return String.format(java.util.Locale.ROOT, "%.1f MB", bytes / 1024.0 / 1024.0);
    }

    private static String sha256(byte[] data) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data)); }
        catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
}
