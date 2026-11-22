package com.keplura.server.tool;

import org.springframework.jdbc.core.JdbcTemplate;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;

/** Durable budgets and single-use continuation capability. No credentials or private retrieval snapshots. */
public final class ToolQuestionStore {
    public record State(long promptId, long conversationId, long userId, long orgId, long spaceId,
                        String prompt, String dialogue, String catalog, String webContext, int models, int attempts,
                        boolean continued, boolean followupUsed, Long decisionCallId,boolean internalSeen,String publicTokens) { }
    private final JdbcTemplate jdbc;
    public ToolQuestionStore(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public void create(ToolContext ctx, long promptId, String prompt, String catalog, String webContext) {
        jdbc.update("""
                insert into tool_question(prompt_message_id,conversation_id,user_id,org_id,space_id,prompt_text,
                  dialogue_json,catalog_json,web_context,created_at) values(?,?,?,?,?,?,'[]',?,?,?)""",
                promptId, ctx.conversationId(), ctx.userId(), ctx.orgId(), ctx.spaceId(), prompt, catalog, webContext, Timestamp.from(Instant.now()));
    }
    public Optional<State> find(long promptId) {
        return jdbc.query("select * from tool_question where prompt_message_id=?", (rs,n) -> new State(
                rs.getLong("prompt_message_id"),rs.getLong("conversation_id"),rs.getLong("user_id"),
                rs.getLong("org_id"),rs.getLong("space_id"),rs.getString("prompt_text"),rs.getString("dialogue_json"),
                rs.getString("catalog_json"),rs.getString("web_context"),rs.getInt("model_requests"),rs.getInt("attempts"),
                rs.getBoolean("continued"),rs.getBoolean("followup_used"),rs.getObject("decision_call_id",Long.class),
                rs.getBoolean("internal_seen"),rs.getString("public_tokens")), promptId).stream().findFirst();
    }
    public boolean model(long promptId) {
        return jdbc.update("update tool_question set model_requests=model_requests+1 where prompt_message_id=? and model_requests<5", promptId)==1;
    }
    public boolean attempt(long promptId) {
        return jdbc.update("update tool_question set attempts=attempts+1 where prompt_message_id=? and attempts<8", promptId)==1;
    }
    public void checkpoint(long promptId, String dialogue, boolean ready) {
        jdbc.update("update tool_question set dialogue_json=?,ready=? where prompt_message_id=? and continued=false", dialogue, ready, promptId);
    }
    public boolean offer(long promptId, long cardId, String hash, Instant now) {
        return jdbc.update("""
                update tool_question set continuation_hash=?,decision_call_id=?,continuation_expires_at=? where prompt_message_id=?
                and ready=true and continued=false and continuation_hash is null""", hash, cardId, Timestamp.from(now.plusSeconds(60)), promptId)==1;
    }
    public boolean claim(long promptId, long cardId, String hash, Instant now) {
        return jdbc.update("""
                update tool_question set continued=true,continuation_hash=null,ready=false where prompt_message_id=?
                and decision_call_id=? and continuation_hash=? and continuation_expires_at>?
                and ready=true and continued=false""", promptId,cardId,hash,Timestamp.from(now))==1;
    }
    public void delete(long conversationId) { jdbc.update("delete from tool_question where conversation_id=?",conversationId); }

    public void markInternal(long promptId) {
        if(jdbc.update("update tool_question set internal_seen=true where prompt_message_id=?",promptId)!=1)
            throw new IllegalStateException("question provenance unavailable");
    }
    public void savePublicTokens(long promptId,String tokens) {
        jdbc.update("update tool_question set public_tokens=? where prompt_message_id=?",tokens,promptId);
    }

    /** One proposal, only in the consumed continuation of this specific parent decision. */
    public boolean claimFollowup(long promptId, long parentCallId) {
        return jdbc.update("""
                update tool_question set followup_used=true where prompt_message_id=? and decision_call_id=?
                and continued=true and followup_used=false""", promptId, parentCallId)==1;
    }
}
