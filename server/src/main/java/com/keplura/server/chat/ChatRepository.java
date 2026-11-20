package com.keplura.server.chat;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Conversation reads and writes (M4b). <b>Tenant isolation is enforced here,
 * at query level</b> — the Space/KnowledgeBase rule carried over: every
 * access takes the calling user id plus the path's org and requires a
 * matching {@code membership} row in the same statement. A non-member, a
 * foreign conversation id, or a conversation reached through the wrong org
 * path are all indistinguishable (empty) — the uniform 404 (ADR-0004) is
 * built into the JPQL, not bolted on by the service. Chat adds one more pin
 * on top of M3: {@code created_by} = the caller, because conversations are
 * personal even between co-members of an org.
 *
 * <p>No bare {@code findById}-style access for API read code (the inherited
 * {@code JpaRepository} finders are used ONLY inside service transactions
 * that first proved visibility through a pinned finder).
 */
public interface ChatRepository extends JpaRepository<Conversation, Long> {

    /**
     * One conversation by (id, org path), visible ONLY to its personal owner
     * who is a member of the path's organization.
     */
    @Query("""
            select c from Conversation c
            where c.id = :conversationId
              and c.orgId = :orgId
              and c.createdBy = :userId
              and exists (select 1 from Membership m
                          where m.orgId = :orgId and m.userId = :userId)
            """)
    Optional<Conversation> findVisible(@Param("conversationId") Long conversationId,
                                       @Param("orgId") Long orgId,
                                       @Param("userId") Long userId);

    /** The caller's conversations in one space of their org, newest activity first. */
    @Query("""
            select c from Conversation c
            where c.orgId = :orgId
              and c.spaceId = :spaceId
              and c.createdBy = :userId
              and exists (select 1 from Membership m
                          where m.orgId = :orgId and m.userId = :userId)
            order by c.updatedAt desc, c.id desc
            """)
    List<Conversation> findAllVisible(@Param("orgId") Long orgId,
                                      @Param("spaceId") Long spaceId,
                                      @Param("userId") Long userId);

    /**
     * Bulk "last activity" bump — a separate statement instead of an entity
     * save so the stream-end persistence never loads-then-writes a whole row.
     * Not visibility-guarded: callers pass an id they already resolved
     * through a pinned finder in the same unit of work.
     */
    @Modifying
    @Query("update Conversation c set c.updatedAt = :when where c.id = :conversationId")
    int touch(@Param("conversationId") Long conversationId, @Param("when") Instant when);

    /**
     * Auto-title: rename an as-yet-untitled conversation with its first
     * prompt. Guarded to the exact {@link #findVisible} predicate (owner +
     * org membership) AND to the placeholder title, so a re-title of a named
     * conversation can never happen and a foreign id updates zero rows.
     */
    @Modifying
    @Query("""
            update Conversation c set c.title = :title, c.updatedAt = :when
            where c.id = :conversationId
              and c.orgId = :orgId
              and c.createdBy = :userId
              and c.title = 'Neuer Chat'
              and exists (select 1 from Membership m
                          where m.orgId = :orgId and m.userId = :userId)
            """)
    int titleIfUntitled(@Param("conversationId") Long conversationId,
                        @Param("orgId") Long orgId,
                        @Param("userId") Long userId,
                        @Param("title") String title,
                        @Param("when") Instant when);

    /**
     * Hard delete of one conversation, guarded by the exact same visibility
     * predicate as {@link #findVisible} (a delete must never be broader than
     * the read that authorized it). Returns 0 when invisible → the caller
     * answers the uniform 404. The transcript goes with it via
     * {@link ChatMessageRepository#deleteAllOf}.
     */
    @Modifying
    @Query("""
            delete from Conversation c
            where c.id = :conversationId
              and c.orgId = :orgId
              and c.createdBy = :userId
              and exists (select 1 from Membership m
                          where m.orgId = :orgId and m.userId = :userId)
            """)
    int deleteVisible(@Param("conversationId") Long conversationId,
                      @Param("orgId") Long orgId,
                      @Param("userId") Long userId);
}
