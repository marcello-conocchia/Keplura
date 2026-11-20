package com.keplura.server.chat;

import com.keplura.server.knowledge.KnowledgeBaseRepository;
import com.keplura.server.search.TikaDocumentTextExtractor;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Chat-Kontext jenseits der primären Wissensbasis: weitere Wissensbasen (Antworten aus
 * mehreren Quellen gleichzeitig) und angehängte Dateien („Fasse diese Datei zusammen“).
 *
 * <p>Alles hängt an einer EIGENEN Unterhaltung ({@link ChatRepository#findVisible}),
 * jede Wissensbasis wird gegen die Mitgliedschaft im selben Arbeitsbereich geprüft.
 * Anhänge werden nur als extrahierter Text gespeichert (keine Binärdatei), gekappt auf
 * {@link #MAX_ATTACHMENT_CHARS} Zeichen.
 */
@Service
public class ChatContextService {

    static final int MAX_EXTRA_KBS = 5;
    static final int MAX_ATTACHMENTS = 5;
    static final int MAX_ATTACHMENT_CHARS = 30000;
    /** Gesamtanteil der Anhänge am Prompt (Gateway-Grenze je Nachricht: 64k Zeichen). */
    static final int MAX_PROMPT_ATTACHMENT_CHARS = 40000;
    static final long MAX_UPLOAD_BYTES = 10L * 1024 * 1024;
    static final Set<String> TEXT_EXTENSIONS = Set.of("txt", "md", "csv");

    public record AttachmentView(Long id, String filename, int charCount, boolean truncated, Instant createdAt) {
    }

    public record ContextView(Long knowledgeBaseId, List<Long> extraKnowledgeBaseIds, List<AttachmentView> attachments) {
    }

    /** Text eines Anhangs für den Prompt. */
    public record AttachmentText(String filename, String text) {
    }

    private final JdbcTemplate jdbc;
    private final ChatRepository conversations;
    private final KnowledgeBaseRepository kbs;
    private final TikaDocumentTextExtractor extractor;

    public ChatContextService(JdbcTemplate jdbc, ChatRepository conversations, KnowledgeBaseRepository kbs,
                              TikaDocumentTextExtractor extractor) {
        this.jdbc = jdbc;
        this.conversations = conversations;
        this.kbs = kbs;
        this.extractor = extractor;
    }

    @Transactional(readOnly = true)
    public ContextView context(Long userId, Long orgId, Long conversationId) {
        Conversation c = require(userId, orgId, conversationId);
        return new ContextView(c.getKnowledgeBaseId(), extraKbIds(c.getId()), attachments(c.getId()));
    }

    /** Ersetzt die zusätzlichen Wissensbasen (die primäre bleibt unverändert). */
    @Transactional
    public ContextView setExtraKnowledgeBases(Long userId, Long orgId, Long conversationId, List<Long> ids) {
        Conversation c = require(userId, orgId, conversationId);
        Set<Long> clean = new LinkedHashSet<>();
        if (ids != null) {
            for (Long id : ids) {
                if (id != null && !id.equals(c.getKnowledgeBaseId())) clean.add(id);
            }
        }
        if (clean.size() > MAX_EXTRA_KBS) {
            throw new ChatException(HttpStatus.BAD_REQUEST, "at most " + MAX_EXTRA_KBS + " additional knowledge bases");
        }
        for (Long id : clean) {
            kbs.findByIdInSpaceForMember(id, c.getSpaceId(), orgId, userId)
                    .orElseThrow(() -> new ChatException(HttpStatus.NOT_FOUND, "knowledge base not found"));
        }
        jdbc.update("delete from chat_conversation_kb where conversation_id = ?", c.getId());
        for (Long id : clean) {
            jdbc.update("insert into chat_conversation_kb (conversation_id, knowledge_base_id) values (?, ?)", c.getId(), id);
        }
        return new ContextView(c.getKnowledgeBaseId(), extraKbIds(c.getId()), attachments(c.getId()));
    }

    @Transactional
    public AttachmentView addAttachment(Long userId, Long orgId, Long conversationId, String filename, InputStream in) {
        Conversation c = require(userId, orgId, conversationId);
        String name = cleanName(filename);
        // Serialize the count + insert across requests and server instances.
        jdbc.queryForObject("select id from chat_conversation where id = ? for update", Long.class, c.getId());
        Integer count = jdbc.queryForObject("select count(*) from chat_attachment where conversation_id = ?",
                Integer.class, c.getId());
        if (count != null && count >= MAX_ATTACHMENTS) {
            throw new ChatException(HttpStatus.BAD_REQUEST, "at most " + MAX_ATTACHMENTS + " attachments per chat");
        }
        String extracted = extract(name, in);
        boolean truncated = extracted.length() > MAX_ATTACHMENT_CHARS;
        String text = truncated ? extracted.substring(0, MAX_ATTACHMENT_CHARS) : extracted;
        Instant now = Instant.now();
        var keys = new org.springframework.jdbc.support.GeneratedKeyHolder();
        jdbc.update(connection -> {
            var statement = connection.prepareStatement("""
                insert into chat_attachment (conversation_id, filename, text_content, char_count, truncated, created_by, created_at)
                values (?, ?, ?, ?, ?, ?, ?)""", new String[]{"id"});
            statement.setLong(1, c.getId());
            statement.setString(2, name);
            statement.setString(3, text);
            statement.setInt(4, text.length());
            statement.setBoolean(5, truncated);
            statement.setLong(6, userId);
            statement.setTimestamp(7, Timestamp.from(now));
            return statement;
        }, keys);
        Long id = java.util.Objects.requireNonNull(keys.getKey(), "attachment id missing").longValue();
        return new AttachmentView(id, name, text.length(), truncated, now);
    }

    @Transactional
    public void deleteAttachment(Long userId, Long orgId, Long conversationId, Long attachmentId) {
        Conversation c = require(userId, orgId, conversationId);
        int n = jdbc.update("delete from chat_attachment where id = ? and conversation_id = ?", attachmentId, c.getId());
        if (n == 0) throw new ChatException(HttpStatus.NOT_FOUND, "attachment not found");
    }

    // ---- für ChatService (Unterhaltung bereits geprüft) ----

    List<Long> extraKbIds(Long conversationId) {
        return jdbc.queryForList("select knowledge_base_id from chat_conversation_kb where conversation_id = ? order by knowledge_base_id",
                Long.class, conversationId);
    }

    List<AttachmentText> attachmentTexts(Long conversationId) {
        List<AttachmentText> out = new ArrayList<>();
        int budget = MAX_PROMPT_ATTACHMENT_CHARS;
        for (AttachmentText a : jdbc.query("select filename, text_content from chat_attachment where conversation_id = ? order by id",
                (rs, n) -> new AttachmentText(rs.getString(1), rs.getString(2)), conversationId)) {
            if (budget <= 0) break;
            String t = a.text().length() > budget ? a.text().substring(0, budget) : a.text();
            budget -= t.length();
            out.add(new AttachmentText(a.filename(), t));
        }
        return out;
    }

    // ---- intern ----

    private List<AttachmentView> attachments(Long conversationId) {
        return jdbc.query("select id, filename, char_count, truncated, created_at from chat_attachment where conversation_id = ? order by id",
                (rs, n) -> new AttachmentView(rs.getLong(1), rs.getString(2), rs.getInt(3), rs.getBoolean(4),
                        rs.getTimestamp(5).toInstant()), conversationId);
    }

    private Conversation require(Long userId, Long orgId, Long conversationId) {
        return conversations.findVisible(conversationId, orgId, userId)
                .orElseThrow(() -> new ChatException(HttpStatus.NOT_FOUND, "conversation not found"));
    }

    static String cleanName(String filename) {
        String n = filename == null ? "" : filename.replace('\\', '/');
        n = n.substring(n.lastIndexOf('/') + 1).replaceAll("[\\p{Cntrl}]", "").strip();
        if (n.isEmpty()) throw new ChatException(HttpStatus.BAD_REQUEST, "filename is missing");
        return n.length() > 255 ? n.substring(0, 255) : n;
    }

    static String extensionOf(String name) {
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private String extract(String name, InputStream in) {
        String ext = extensionOf(name);
        boolean text = TEXT_EXTENSIONS.contains(ext);
        if (!text && !TikaDocumentTextExtractor.PARSED_EXTENSIONS.contains(ext)) {
            throw new ChatException(HttpStatus.BAD_REQUEST, "unsupported file type (pdf, docx, xlsx, pptx, txt, md, csv)");
        }
        Path tmp = null;
        try {
            tmp = Files.createTempFile("ic-attach-", "." + ext);
            byte[] bytes = in.readNBytes((int) MAX_UPLOAD_BYTES + 1);
            if (bytes.length > MAX_UPLOAD_BYTES) {
                throw new ChatException(HttpStatus.PAYLOAD_TOO_LARGE, "file is too large (max 10 MB)");
            }
            Files.write(tmp, bytes);
            String result;
            if (text) {
                result = StandardCharsets.UTF_8.newDecoder()
                        .onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT)
                        .decode(java.nio.ByteBuffer.wrap(Files.readAllBytes(tmp))).toString();
            } else {
                result = extractor.extract(name, tmp).text();
            }
            result = result.replace("\f", "\n\n").replace("\u0000", "").strip();
            if (result.isEmpty()) throw new ChatException(HttpStatus.BAD_REQUEST, "file contains no readable text");
            return result;
        } catch (CharacterCodingException e) {
            throw new ChatException(HttpStatus.BAD_REQUEST, "text file is not valid UTF-8");
        } catch (TikaDocumentTextExtractor.BlankExtractedTextException e) {
            throw new ChatException(HttpStatus.BAD_REQUEST, "file contains no readable text (scan?)");
        } catch (TikaDocumentTextExtractor.ExtractionBusyException e) {
            throw new ChatException(HttpStatus.SERVICE_UNAVAILABLE, "server is busy, please retry");
        } catch (TikaDocumentTextExtractor.ExtractionFailureException e) {
            throw new ChatException(HttpStatus.BAD_REQUEST, "file could not be read");
        } catch (IOException e) {
            throw new ChatException(HttpStatus.BAD_REQUEST, "file could not be read");
        } finally {
            if (tmp != null) {
                try {
                    Files.deleteIfExists(tmp);
                } catch (IOException ignored) {
                    // temp cleanup best effort
                }
            }
        }
    }
}
