package com.keplura.server.search;

import com.keplura.server.knowledge.KnowledgeBaseRepository;
import com.keplura.server.org.MembershipRepository;
import com.keplura.server.space.SpaceRepository;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * RAG-light query path (M3d): embed the query, ask the active
 * {@link SearchStore} for the top-K most similar chunks of ONE knowledge
 * base, return each hit with full source attribution (documentId + filename
 * + chunkIdx + score). "RAG-light" = retrieval with citations, no generated
 * answer — chat/prompting is explicitly a later milestone.
 *
 * <p>Authorization is the M3c rule one level deeper and happens BEFORE any
 * embedding work: membership (org) → space pinned to org → kb pinned to
 * space. Every denial — foreign org, wrong space path, wrong kb, unknown id
 * — collapses into the same uniform 404 as the document endpoints, so the
 * search route cannot probe tenant structure either. The store itself
 * re-pins the chain inside its SQL (tenant gate before ranking); this guard
 * is what produces the honest 404 vs an empty-looking success.
 *
 * <p>Input limits: blank/oversized query → 400 (measured before the
 * embedding call, so a huge query cannot turn into a huge gateway request);
 * topK defaults to 5 and is clamped to 50 — the scan/ANN cost stays bounded
 * by the kb's chunk count anyway, the cap just stops "topK=1e6" responses.
 */
@Service
public class SearchService {

    /** 404 — the kb chain is invisible to this caller (identical at every level). */
    public static class SearchTargetNotFoundException extends RuntimeException {
        public SearchTargetNotFoundException() {
            super("knowledge base not found for this caller");
        }
    }

    /** 400 — blank or oversized query. */
    public static class InvalidQueryException extends RuntimeException {
        public InvalidQueryException(String message) {
            super(message);
        }
    }

    public record SearchResponse(String query, int topK, String backend, String model,
                                 List<SearchHit> results) {
    }

    static final int MAX_TOPK = 50;
    static final int DEFAULT_TOPK = 5;

    private final MembershipRepository membershipRepository;
    private final SpaceRepository spaceRepository;
    private final KnowledgeBaseRepository kbRepository;
    private final EmbeddingService embeddings;
    private final SearchStore store;
    private final int maxQueryChars;
    private LocalReranker reranker;

    @org.springframework.beans.factory.annotation.Autowired
    void setReranker(LocalReranker reranker) { this.reranker = reranker; }

    public SearchService(MembershipRepository membershipRepository,
                         SpaceRepository spaceRepository,
                         KnowledgeBaseRepository kbRepository,
                         EmbeddingService embeddings,
                         SearchStore store,
                         @Value("${keplura.search.max-query-chars:4000}") int maxQueryChars) {
        this.membershipRepository = membershipRepository;
        this.spaceRepository = spaceRepository;
        this.kbRepository = kbRepository;
        this.embeddings = embeddings;
        this.store = store;
        this.maxQueryChars = maxQueryChars;
    }

    /**
     * Search one kb through its full tenant path. Guard order matters: the
     * query syntax check can run first (cheap, leaks nothing — 400 on a
     * blank query says nothing about the chain), but EVERY chain failure
     * returns 404 before an embedding or store call happens.
     */
    @Transactional(readOnly = true)
    public SearchResponse search(Long userId, Long orgId, Long spaceId, Long kbId,
                                 String rawQuery, Integer requestedTopK) {
        String query = (rawQuery == null) ? "" : rawQuery.strip();
        if (query.isEmpty()) {
            throw new InvalidQueryException("query must not be blank");
        }
        if (query.length() > maxQueryChars) {
            throw new InvalidQueryException("query exceeds " + maxQueryChars + " characters");
        }
        requireKb(userId, orgId, spaceId, kbId);

        int topK = (requestedTopK == null || requestedTopK <= 0) ? DEFAULT_TOPK
                : Math.min(requestedTopK, MAX_TOPK);
        float[] queryVector = embeddings.embed(query);
        boolean reranking = reranker != null && reranker.enabled();
        int candidates = reranking ? Math.min(MAX_TOPK, Math.max(20, topK * 4)) : topK;
        List<SearchHit> hits = store instanceof PgVectorEmbeddingStore
                ? store.search(query, queryVector, kbId, spaceId, orgId, userId, candidates)
                : store.search(queryVector, kbId, spaceId, orgId, userId, candidates);
        if (reranking) hits = reranker.rerank(query, hits, topK);
        return new SearchResponse(query, topK, store.backendName(), embeddings.modelTag(), hits);
    }

    private void requireKb(Long userId, Long orgId, Long spaceId, Long kbId) {
        membershipRepository.findByOrgIdAndUserId(orgId, userId)
                .orElseThrow(SearchTargetNotFoundException::new);
        spaceRepository.findByIdInOrgForMember(spaceId, orgId, userId)
                .orElseThrow(SearchTargetNotFoundException::new);
        kbRepository.findByIdInSpaceForMember(kbId, spaceId, orgId, userId)
                .orElseThrow(SearchTargetNotFoundException::new);
    }
}
