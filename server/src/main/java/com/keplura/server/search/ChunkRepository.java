package com.keplura.server.search;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

/**
 * Chunk reads for the in-JVM (H2/dev/test) search path. <b>The tenant gate
 * lives in the query, not in callers:</b> {@link #findCandidateChunks} pins
 * kb + kb→space + space→org + membership EXISTS (four pins) in ONE statement
 * (the M3c pattern, one level deeper), so a foreign caller can never pull
 * candidate vectors — similarity ranking only ever sees chunks the caller is
 * already entitled to.
 */
public interface ChunkRepository extends JpaRepository<Chunk, Long> {

    /**
     * Every chunk of one kb (visible through its full chain), in stable
     * document/chunk order. The memory store scores these in-JVM. Bounded by
     * the upload size limit / chunk window (max-doc-bytes / min chunk size
     * caps rows per doc) — MVP scale class only, documented in ADR-0007.
     */
    interface Candidate {
        Chunk getChunk();
        String getFilename();
        java.time.Instant getSourceUpdatedAt();
    }

    @Query("""
            select c as chunk, d.filename as filename, d.sourceUpdatedAt as sourceUpdatedAt
            from Chunk c join Document d on d.id = c.documentId
            where c.kbId = :kbId
              and exists (select 1 from KnowledgeBase k
                          where k.id = :kbId and k.spaceId = :spaceId)
              and exists (select 1 from Space s
                          where s.id = :spaceId and s.orgId = :orgId)
              and exists (select 1 from Membership m
                          where m.orgId = :orgId and m.userId = :userId)
              and """ + com.keplura.server.access.AccessFilter.JPQL_CHUNK + """
            order by c.documentId, c.chunkIdx
            """)
    List<Candidate> findCandidateChunks(@Param("kbId") Long kbId,
                                    @Param("spaceId") Long spaceId,
                                    @Param("orgId") Long orgId,
                                    @Param("userId") Long userId);

    /** Chunks of one document (indexing/inspection; chain proven upstream). */
    List<Chunk> findByDocumentIdOrderByChunkIdx(Long documentId);

    /** Bounded document preview, with the complete authorization chain inside SQL. */
    @Query("""
            select c from Chunk c
            where c.documentId=:documentId and c.kbId=:kbId
              and c.chunkIdx between :first and :last
              and exists (select 1 from KnowledgeBase k where k.id=:kbId and k.spaceId=:spaceId)
              and exists (select 1 from Space s where s.id=:spaceId and s.orgId=:orgId)
              and exists (select 1 from Membership m where m.orgId=:orgId and m.userId=:userId)
              and """ + com.keplura.server.access.AccessFilter.JPQL_CHUNK + """
            order by c.chunkIdx
            """)
    List<Chunk> findPreviewWindow(@Param("documentId") Long documentId, @Param("kbId") Long kbId,
            @Param("spaceId") Long spaceId, @Param("orgId") Long orgId, @Param("userId") Long userId,
            @Param("first") int first, @Param("last") int last);

    /** Removes all chunks of one document (delete path; chain proven upstream). */
    @org.springframework.data.jpa.repository.Modifying
    @Query("delete from Chunk c where c.documentId = :documentId")
    int deleteByDocumentId(@Param("documentId") Long documentId);

    /** True searchability probe for the indexStatus field (a pre-M3d txt has none). */
    boolean existsByDocumentId(Long documentId);
}
