package com.keplura.server.tool;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.sql.Timestamp;
import java.sql.Types;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Aufrufkarten (TU-02, Tabelle {@code tool_call}). Zustandswechsel sind bedingte UPDATEs, damit
 * ein ausstehender Aufruf genau einmal verbraucht wird (Doppelklick, parallele Entscheidungen,
 * Wiederholung einer HTTP-Anfrage). Keine Zugangsdaten; Argumente und Ergebnis nur für die
 * Person des Chats sichtbar.
 */
@Component
public class ToolCallStore {

    /** Zustände einer Karte. */
    public enum Status {
        /** wartet auf die Bestätigung der Person (nur CRITICAL) */ PENDING,
        /** externer Aufruf begonnen */ RUNNING,
        SUCCEEDED, FAILED,
        /** von der Person abgelehnt */ REJECTED,
        /** vom Server nicht ausgeführt (Prüfung fehlgeschlagen) */ BLOCKED,
        /** Bestätigungsfrist abgelaufen */ EXPIRED,
        /** durch eine neue Nachricht im Chat abgebrochen */ CANCELLED,
        /** Ausgang unklar (z. B. Abbruch während des Aufrufs) — wird nie wiederholt */ UNKNOWN
    }

    /** Frist für eine Bestätigung. */
    public static final Duration CONFIRMATION_TTL = Duration.ofMinutes(10);
    /** Ab hier gilt ein RUNNING ohne Abschluss in der Anzeige als „unklar“. */
    public static final Duration RUNNING_STALE_AFTER = Duration.ofMinutes(5);
    static final int MAX_RESULT_CHARS = ToolExecutionService.MAX_RESULT_CHARS;

    /** Vollständige Zeile (serverintern). */
    public record Row(long id, String publicId, long conversationId, Long promptMessageId, long userId, long orgId,
                      long spaceId, String adapter, String toolId, String functionName, String toolName, Long sourceId,
                      String sourceName, ToolRisk risk, String definitionHash, String argumentsJson, Status status,
                      boolean needsConfirmation, String resultText, String error, Instant createdAt,
                      Instant expiresAt, Instant startedAt, boolean superseded) {
    }

    /** Maximal speicherbare Länge der kanonischen Argumente (= Turn-Limit). */
    public static final int MAX_ARGUMENTS_JSON = ToolExecutionService.MAX_ARGUMENT_CHARS;

    /**
     * SQL-Bedingung „Generation noch gültig“ für eine Karte des Chats {@code ?} und der Frage
     * {@code ?}: keine neuere Frage im Chat und kein Stopp, der diese Frage einschließt.
     */
    private static final String CURRENT_GENERATION = """
            not exists (select 1 from chat_message m where m.conversation_id = %1$s and m.role = 'user' and m.id > %2$s)
            and not exists (select 1 from tool_call_stop s where s.conversation_id = %1$s and s.stopped_prompt_id >= %2$s)""";

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper = JsonMapper.builder().build();

    public ToolCallStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Kanonischer JSON-Text der Argumente oder {@code null}, wenn kein Objekt bzw. zu groß. Nie Ersatzwerte. */
    public String canonical(JsonNode arguments) {
        if (arguments == null || !arguments.isObject()) return null;
        String text = mapper.writeValueAsString(arguments);
        return text.length() > MAX_ARGUMENTS_JSON ? null : text;
    }

    /**
     * Legt eine Karte an. {@code argumentsJson} wird exakt so gespeichert (nie ersetzt); {@code null}
     * nur für gesperrte Pläne. PENDING-Karten entstehen nur, solange die Generation der Frage gültig
     * ist (atomar im INSERT); sonst {@link Optional#empty()}. Wirft bei jedem anderen Fehler.
     */
    public Optional<Row> create(ToolContext ctx, Long promptMessageId, ToolDefinition tool, String definitionHash,
                                String argumentsJson, Status status, boolean needsConfirmation, String error, Instant now) {
        if (argumentsJson != null && argumentsJson.length() > MAX_ARGUMENTS_JSON) {
            throw new IllegalArgumentException("arguments too large to store exactly");
        }
        if ((status == Status.PENDING || status == Status.RUNNING) && argumentsJson == null) {
            throw new IllegalArgumentException("an executable card needs its exact arguments");
        }
        if (status == Status.PENDING && promptMessageId == null) {
            throw new IllegalArgumentException("a pending card must belong to a question");
        }
        String publicId = UUID.randomUUID().toString();
        String args = argumentsJson;
        Instant expires = status == Status.PENDING ? now.plus(CONFIRMATION_TTL) : null;
        Instant started = status == Status.RUNNING ? now : null;
        String argsJson = args;
        var keys = new GeneratedKeyHolder();
        jdbc.update(c -> {
            var st = c.prepareStatement("""
                    insert into tool_call (public_id, conversation_id, prompt_message_id, user_id, org_id, space_id,
                      adapter, tool_id, function_name, tool_name, source_id, source_name, risk, definition_hash,
                      fingerprint, arguments_json, status, needs_confirmation, error, created_at, expires_at, started_at)
                    values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""", new String[]{"id"});
            int i = 1;
            st.setString(i++, publicId);
            st.setLong(i++, ctx.conversationId());
            if (promptMessageId == null) st.setNull(i++, Types.BIGINT); else st.setLong(i++, promptMessageId);
            st.setLong(i++, ctx.userId());
            st.setLong(i++, ctx.orgId());
            st.setLong(i++, ctx.spaceId());
            st.setString(i++, tool.adapter());
            st.setString(i++, cap(tool.id(), 300));
            st.setString(i++, cap(tool.functionName(), 64));
            st.setString(i++, cap(tool.toolName(), 128));
            if (tool.sourceId() == null) st.setNull(i++, Types.BIGINT); else st.setLong(i++, tool.sourceId());
            st.setString(i++, cap(tool.sourceName(), 120));
            st.setString(i++, tool.risk() == null ? null : tool.risk().name());
            st.setString(i++, definitionHash);
            st.setString(i++, tool.fingerprint() == null ? null : cap(tool.fingerprint(), 64));
            st.setString(i++, argsJson);
            st.setString(i++, status.name());
            st.setBoolean(i++, needsConfirmation);
            st.setString(i++, error == null ? null : cap(error, 300));
            st.setTimestamp(i++, Timestamp.from(now));
            st.setTimestamp(i++, expires == null ? null : Timestamp.from(expires));
            st.setTimestamp(i, started == null ? null : Timestamp.from(started));
            return st;
        }, keys);
        long id = Objects.requireNonNull(keys.getKey(), "tool_call id missing").longValue();
        if (status == Status.PENDING) {
            // Superseded meanwhile (newer question or stop)? Then it never becomes decidable. The
            // decision itself re-checks the generation atomically, so this only keeps the card honest.
            int cancelled = jdbc.update("update tool_call set status = 'CANCELLED', decided_at = ? where id = ? and status = 'PENDING' and not ("
                    + CURRENT_GENERATION.formatted("tool_call.conversation_id", "tool_call.prompt_message_id") + ")",
                    Timestamp.from(now), id);
            if (cancelled == 1) return Optional.empty();
        }
        return Optional.of(find(id).orElseThrow());
    }

    public Optional<Row> find(long id) {
        return jdbc.query(SELECT + " where id = ?", ROWS, id).stream().findFirst();
    }

    /** Karte nur für genau diese Person, Organisation und diesen Chat. */
    public Optional<Row> findOwned(String publicId, long userId, long orgId, long conversationId) {
        if (publicId == null || publicId.length() > 36) return Optional.empty();
        return jdbc.query(SELECT + " where public_id = ? and user_id = ? and org_id = ? and conversation_id = ?",
                ROWS, publicId, userId, orgId, conversationId).stream().findFirst();
    }

    public List<Row> listOwned(long userId, long orgId, long conversationId) {
        return jdbc.query(SELECT + " where user_id = ? and org_id = ? and conversation_id = ? order by created_at, id",
                ROWS, userId, orgId, conversationId);
    }

    /** A server-bound question is live only until a newer question or Stop supersedes it. */
    public boolean isCurrentQuestion(long conversationId, long promptMessageId) {
        return jdbc.queryForObject("select count(*) from chat_message q where q.id = ? and q.conversation_id = ? and q.role = 'user' and "
                + CURRENT_GENERATION.formatted("q.conversation_id", "q.id"), Integer.class,
                promptMessageId, conversationId) == 1;
    }

    /** Bounded previous confirmed results; authorization is rechecked by ToolExecutionService. */
    public List<Row> completedBefore(long userId, long orgId, long conversationId, long promptMessageId) {
        return jdbc.query(SELECT + " where user_id = ? and org_id = ? and conversation_id = ?"
                + " and prompt_message_id < ? and needs_confirmation = true and status = 'SUCCEEDED'"
                + " order by id desc limit 8", ROWS, userId, orgId, conversationId, promptMessageId);
    }

    /**
     * Verbraucht eine ausstehende Bestätigung atomar: PENDING → {@code next}, nur wenn noch nicht
     * abgelaufen. {@code true} genau für den einen Aufrufer, der den Wechsel bekommen hat.
     */
    public boolean claimPending(long id, Status next, Instant now) {
        // Running additionally requires the question's generation to be current, atomically.
        String generation = next == Status.RUNNING
                ? " and prompt_message_id is not null and "
                  + CURRENT_GENERATION.formatted("tool_call.conversation_id", "tool_call.prompt_message_id") : "";
        return jdbc.update("""
                update tool_call set status = ?, decided_at = ?, started_at = ?
                where id = ? and status = 'PENDING' and expires_at > ?""" + generation,
                next.name(), Timestamp.from(now), next == Status.RUNNING ? Timestamp.from(now) : null,
                id, Timestamp.from(now)) == 1;
    }

    /** Ausstehende Karte endgültig abbrechen (z. B. Generation überholt). */
    public boolean cancel(long id, Instant now) {
        return jdbc.update("update tool_call set status = 'CANCELLED', decided_at = ? where id = ? and status = 'PENDING'",
                Timestamp.from(now), id) == 1;
    }

    /**
     * Stopp im Chat: alle Pläne bis einschließlich der neuesten Frage werden ungültig — auch solche,
     * die ein noch laufender Turn erst später anlegen würde. Offene Karten werden abgebrochen.
     */
    public void stop(long conversationId, Instant now) {
        Long latest = jdbc.queryForObject(
                "select max(id) from chat_message where conversation_id = ? and role = 'user'", Long.class, conversationId);
        long stopped = latest == null ? 0L : latest;
        int updated = jdbc.update("update tool_call_stop set stopped_prompt_id = ?, stopped_at = ? where conversation_id = ? and stopped_prompt_id <= ?",
                stopped, Timestamp.from(now), conversationId, stopped);
        if (updated == 0) {
            try {
                jdbc.update("insert into tool_call_stop (conversation_id, stopped_prompt_id, stopped_at) values (?, ?, ?)",
                        conversationId, stopped, Timestamp.from(now));
            } catch (org.springframework.dao.DuplicateKeyException race) {
                jdbc.update("update tool_call_stop set stopped_prompt_id = ?, stopped_at = ? where conversation_id = ? and stopped_prompt_id <= ?",
                        stopped, Timestamp.from(now), conversationId, stopped);
            }
        }
        jdbc.update("update tool_call set status = 'CANCELLED', decided_at = ? where conversation_id = ? and status = 'PENDING' and prompt_message_id <= ?",
                Timestamp.from(now), conversationId, stopped);
    }

    /** Bereits verbrauchte Werkzeugzeit aller Aufrufe derselben Frage (laufende bis {@code now}). */
    public Duration timeUsed(long conversationId, long promptMessageId, Instant now) {
        Duration total = Duration.ZERO;
        List<Duration> spans = jdbc.query("""
                select started_at, finished_at from tool_call
                where conversation_id = ? and prompt_message_id = ? and started_at is not null""", (rs, n) -> {
            Instant start = rs.getTimestamp(1).toInstant();
            Timestamp fin = rs.getTimestamp(2);
            Instant end = fin == null ? now : fin.toInstant();
            return end.isAfter(start) ? Duration.between(start, end) : Duration.ZERO;
        }, conversationId, promptMessageId);
        for (Duration d : spans) total = total.plus(d);
        return total;
    }

    /** Abgelaufene ausstehende Karte endgültig als EXPIRED markieren. */
    public boolean expire(long id, Instant now) {
        return jdbc.update("update tool_call set status = 'EXPIRED', decided_at = ? where id = ? and status = 'PENDING' and expires_at <= ?",
                Timestamp.from(now), id, Timestamp.from(now)) == 1;
    }

    /** Neue Nachricht im Chat: alle noch offenen Bestätigungen dieses Chats abbrechen. */
    public int cancelPending(long userId, long conversationId, Instant now) {
        // Older turns that plan later are refused by the generation guard in create/claim.
        return jdbc.update("update tool_call set status = 'CANCELLED', decided_at = ? where user_id = ? and conversation_id = ? and status = 'PENDING'",
                Timestamp.from(now), userId, conversationId);
    }

    /** Abschluss eines RUNNING-Aufrufs (nur aus RUNNING heraus). */
    public void finish(long id, Status status, String resultText, String error, Instant now) {
        String result = resultText;
        if (result != null && result.length() > MAX_RESULT_CHARS) result = result.substring(0, MAX_RESULT_CHARS) + "\n[gekürzt]";
        jdbc.update("update tool_call set status = ?, result_text = ?, error = ?, finished_at = ? where id = ? and status = 'RUNNING'",
                status.name(), result, error == null ? null : cap(error, 300), Timestamp.from(now), id);
    }

    public void deleteConversation(long conversationId) {
        jdbc.update("delete from tool_call where conversation_id = ?", conversationId);
        jdbc.update("delete from tool_call_stop where conversation_id = ?", conversationId);
    }

    /** Anzeigezustand: abgelaufene PENDING als EXPIRED, hängende RUNNING als UNKNOWN (ohne DB-Änderung). */
    public static Status effectiveStatus(Row r, Instant now) {
        if (r.status() == Status.PENDING && r.superseded()) return Status.CANCELLED;
        if (r.status() == Status.PENDING && r.expiresAt() != null && !r.expiresAt().isAfter(now)) return Status.EXPIRED;
        if (r.status() == Status.RUNNING && r.startedAt() != null
                && r.startedAt().plus(RUNNING_STALE_AFTER).isBefore(now)) return Status.UNKNOWN;
        return r.status();
    }

    private static final String SELECT = """
            select id, public_id, conversation_id, prompt_message_id, user_id, org_id, space_id, adapter, tool_id,
              function_name, tool_name, source_id, source_name, risk, definition_hash, arguments_json, status,
              needs_confirmation, result_text, error, created_at, expires_at, started_at,
              case when prompt_message_id is null then false when (""" + " "
            + CURRENT_GENERATION.formatted("tool_call.conversation_id", "tool_call.prompt_message_id")
            + ") then false else true end as superseded from tool_call";

    private static final RowMapper<Row> ROWS = (rs, n) -> {
        long prompt = rs.getLong(4);
        Long promptId = rs.wasNull() ? null : prompt;
        long source = rs.getLong(12);
        Long sourceId = rs.wasNull() ? null : source;
        Status status;
        try {
            status = Status.valueOf(rs.getString(17));
        } catch (IllegalArgumentException e) {
            status = Status.UNKNOWN;
        }
        return new Row(rs.getLong(1), rs.getString(2), rs.getLong(3), promptId, rs.getLong(5), rs.getLong(6),
                rs.getLong(7), rs.getString(8), rs.getString(9), rs.getString(10), rs.getString(11), sourceId,
                rs.getString(13), ToolRisk.parse(rs.getString(14)), rs.getString(15), rs.getString(16), status,
                rs.getBoolean(18), rs.getString(19), rs.getString(20), instant(rs.getTimestamp(21)),
                instant(rs.getTimestamp(22)), instant(rs.getTimestamp(23)), rs.getBoolean(24));
    };

    private static Instant instant(Timestamp t) {
        return t == null ? null : t.toInstant();
    }

    private static String cap(String s, int max) {
        if (s == null) return "";
        return s.length() > max ? s.substring(0, max) : s;
    }
}
