package com.keplura.server.chat;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

/**
 * Message reads for chat transcripts (M4b). Messages carry NO tenant pins of
 * their own (see {@link ChatMessage}): every read reaches them ONLY through
 * a conversation id the service already resolved through
 * {@link ChatRepository#findVisible} — so the conversation id is a proven-
 * scoped coordinate here, never user-supplied input.
 */
public interface ChatMessageRepository extends JpaRepository<ChatMessage, Long> {

    /** The transcript of one (already-authorized) conversation, in append order. */
    @Query("""
            select m from ChatMessage m
            where m.conversationId = :conversationId
            order by m.id
            """)
    List<ChatMessage> findTranscript(@Param("conversationId") Long conversationId);

    /**
     * Last N messages of one conversation via {@link org.springframework.data.domain.Pageable}
     * (the context window handed to the gateway). Query order is DESC id so
     * the page size picks the NEWEST N; the service flips the list back to
     * chronological order. Portable across H2/PostgreSQL without dialect games.
     */
    @Query("""
            select m from ChatMessage m
            where m.conversationId = :conversationId
            order by m.id desc
            """)
    List<ChatMessage> findLatest(@Param("conversationId") Long conversationId,
                                 org.springframework.data.domain.Pageable pageable);

    /** Transcript delete, called ONLY from the conversation-delete transaction
     * that already passed the same visibility gate as {@code deleteVisible}. */
    @Modifying
    @Query("delete from ChatMessage m where m.conversationId = :conversationId")
    int deleteAllOf(@Param("conversationId") Long conversationId);
}
