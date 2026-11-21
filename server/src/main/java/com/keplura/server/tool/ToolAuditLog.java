package com.keplura.server.tool;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.util.Objects;

/**
 * Pflicht-Audit für jeden Werkzeugaufruf ({@code tool_call_log}), ohne Argumente und Ergebnisse.
 * Der Beginn wird VOR der externen Ausführung geschrieben; scheitert das, findet kein Aufruf statt.
 */
@Component
public class ToolAuditLog {

    static final String STARTED = "Aufruf begonnen; Abschluss noch nicht bestätigt.";

    private final JdbcTemplate jdbc;

    public ToolAuditLog(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Schreibt den Beginn und liefert die Protokoll-ID. Wirft bei jedem Fehler. */
    public long begin(ToolContext ctx, ToolDefinition tool) {
        return begin(ctx, tool, null);
    }

    /** Wie {@link #begin(ToolContext, ToolDefinition)}, mit Verweis auf die Aufrufkarte (TU-02). */
    public long begin(ToolContext ctx, ToolDefinition tool, Long toolCallId) {
        var keys = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            var st = connection.prepareStatement("""
                    insert into tool_call_log (called_at, user_id, org_id, space_id, conversation_id, adapter, tool_id,
                      source_id, source_name, tool_name, risk, duration_ms, ok, error, tool_call_id)
                    values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0, false, ?, ?)""", new String[]{"id"});
            st.setTimestamp(1, Timestamp.from(Instant.now()));
            st.setLong(2, ctx.userId());
            st.setLong(3, ctx.orgId());
            st.setLong(4, ctx.spaceId());
            st.setLong(5, ctx.conversationId());
            st.setString(6, tool.adapter());
            st.setString(7, cap(tool.id(), 300));
            if (tool.sourceId() == null) st.setNull(8, Types.BIGINT); else st.setLong(8, tool.sourceId());
            st.setString(9, cap(tool.sourceName(), 120));
            st.setString(10, cap(tool.toolName(), 128));
            st.setString(11, tool.risk() == null ? null : tool.risk().name());
            st.setString(12, STARTED);
            if (toolCallId == null) st.setNull(13, Types.BIGINT); else st.setLong(13, toolCallId);
            return st;
        }, keys);
        return Objects.requireNonNull(keys.getKey(), "audit id missing").longValue();
    }

    /** Abschluss; Fehler hier verhindern nichts mehr (der Aufruf ist bereits erfolgt). */
    public void finish(long auditId, long durationMs, boolean ok, String error) {
        jdbc.update("update tool_call_log set duration_ms = ?, ok = ?, error = ? where id = ?",
                durationMs, ok, error == null ? null : cap(error, 300), auditId);
    }

    private static String cap(String s, int max) {
        if (s == null) return "";
        return s.length() > max ? s.substring(0, max) : s;
    }
}
