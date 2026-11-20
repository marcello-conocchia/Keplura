package com.keplura.server.knowledge;

import com.keplura.server.org.MembershipRepository;
import com.keplura.server.space.SpaceRepository;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Knowledge-base use-cases for the authenticated session (M3c). The
 * authorization model mirrors {@link com.keplura.server.space.SpaceService}
 * one level down: <b>org membership IS the kb ACL</b> — deliberate MVP
 * decision, no kb-level role table (docs/adr/0004 carry-over).
 *
 * <p>Every method proves the chain in order — membership for the PATH's org,
 * then the space pin, then the kb read pinned to the PATH's space — and every
 * denial shape (foreign org, foreign space, space under the wrong org, kb
 * under the wrong space, unknown id) collapses into
 * {@link KnowledgeBaseNotFoundException}: a uniform 404 that never confirms
 * existence at any level. Input problems (blank/overlong name) stay 400 —
 * they are validation, not authorization, and only ever occur AFTER the
 * membership guard so they cannot probe tenant ids either.
 */
@Service
public class KnowledgeBaseService {

    /** Raised by the controller into 400 — input problem, not authorization. */
    public static class InvalidKbNameException extends RuntimeException {
        public InvalidKbNameException(String message) {
            super(message);
        }
    }

    /** Raised by the controller into 404 — absent OR not yours, indistinguishable. */
    public static class KnowledgeBaseNotFoundException extends RuntimeException {
        public KnowledgeBaseNotFoundException() {
            super("knowledge base not found for this caller");
        }
    }

    /** Raised by the controller into 409 — the (space, name) unique key forbids it. */
    public static class DuplicateKbNameException extends RuntimeException {
        public DuplicateKbNameException() {
            super("knowledge base name already taken in this space");
        }
    }

    /** 403 — member of the org, but rename/delete is reserved to org OWNERs. */
    public static class NotOwnerException extends RuntimeException {
        public NotOwnerException() {
            super("only an org owner may change this knowledge base");
        }
    }

    private DocumentRepository documentRepository;
    private DocumentService documentService;
    private org.springframework.jdbc.core.JdbcTemplate jdbc;

    @org.springframework.beans.factory.annotation.Autowired
    void setDeleteCollaborators(DocumentRepository documentRepository, DocumentService documentService,
                                org.springframework.jdbc.core.JdbcTemplate jdbc) {
        this.documentRepository = documentRepository;
        this.documentService = documentService;
        this.jdbc = jdbc;
    }

    private final KnowledgeBaseRepository kbRepository;
    private final SpaceRepository spaceRepository;
    private final MembershipRepository membershipRepository;

    public KnowledgeBaseService(KnowledgeBaseRepository kbRepository,
                                SpaceRepository spaceRepository,
                                MembershipRepository membershipRepository) {
        this.kbRepository = kbRepository;
        this.spaceRepository = spaceRepository;
        this.membershipRepository = membershipRepository;
    }

    /**
     * Creates a kb inside the caller's space. Membership in {@code orgId} and
     * the space's OWNERSHIP of that org are proven BEFORE anything touches the
     * kb table; a concurrent insert racing past the name pre-check hits the
     * DB-level (space_id, name) unique key and surfaces as the same 409.
     */
    @Transactional
    public KnowledgeBase create(Long userId, Long orgId, Long spaceId, String rawName) {
        requireSpace(userId, orgId, spaceId);
        KnowledgeBase kb;
        try {
            kb = KnowledgeBase.create(spaceId, rawName);
        } catch (IllegalArgumentException e) {
            throw new InvalidKbNameException(e.getMessage());
        }
        if (kbRepository.findBySpaceIdAndName(spaceId, kb.getName()).isPresent()) {
            throw new DuplicateKbNameException();
        }
        try {
            return kbRepository.saveAndFlush(kb);
        } catch (DataIntegrityViolationException e) {
            // lost the race against an identical (space, name) — same outcome as the pre-check
            throw new DuplicateKbNameException();
        }
    }

    /** Every kb of the space — empty ONLY provable after the chain holds. */
    @Transactional(readOnly = true)
    public List<KnowledgeBase> list(Long userId, Long orgId, Long spaceId) {
        requireSpace(userId, orgId, spaceId);
        return kbRepository.findAllInSpaceForMember(spaceId, orgId, userId);
    }

    /**
     * One kb by id, ONLY through its own space's path and only for members of
     * the owning org. The repo query pins {@code k.spaceId}, so a victim kb id
     * probed under the attacker's own space path 404s exactly like a
     * never-existing id (the M3c IDOR probe, level 3).
     */
    @Transactional(readOnly = true)
    public KnowledgeBase get(Long userId, Long orgId, Long spaceId, Long kbId) {
        requireSpace(userId, orgId, spaceId);
        return kbRepository.findByIdInSpaceForMember(kbId, spaceId, orgId, userId)
                .orElseThrow(KnowledgeBaseNotFoundException::new);
    }

    /** Renames a kb (org OWNER only): 400 invalid, 409 duplicate, 403, uniform 404. */
    @Transactional
    public KnowledgeBase rename(Long userId, Long orgId, Long spaceId, Long kbId, String rawName) {
        KnowledgeBase kb = requireOwnedKb(userId, orgId, spaceId, kbId);
        String before = kb.getName();
        try {
            kb.rename(rawName);
        } catch (IllegalArgumentException e) {
            throw new InvalidKbNameException(e.getMessage());
        }
        if (!kb.getName().equals(before)
                && kbRepository.findBySpaceIdAndName(spaceId, kb.getName()).isPresent()) {
            throw new DuplicateKbNameException();
        }
        try {
            return kbRepository.saveAndFlush(kb);
        } catch (DataIntegrityViolationException e) {
            throw new DuplicateKbNameException();
        }
    }

    /**
     * Deletes a kb with all its documents (files after commit), chunks and
     * vectors (org OWNER only). Conversations that were grounded in it keep
     * their history and continue as plain (non-RAG) chats.
     */
    @Transactional
    public void delete(Long userId, Long orgId, Long spaceId, Long kbId) {
        deleteAuthorized(requireOwnedKb(userId, orgId, spaceId, kbId));
    }

    /** Cascade step for a kb whose authorization the caller already proved (space delete). */
    @Transactional
    public void deleteAuthorized(KnowledgeBase kb) {
        for (Document doc : documentRepository.findByKbId(kb.getId())) {
            documentService.deleteAuthorized(doc);
        }
        jdbc.update("update chat_conversation set knowledge_base_id = null where knowledge_base_id = ?",
                kb.getId());
        kbRepository.delete(kb);
        kbRepository.flush();
    }

    /** Space delete cascade: removes every kb of the space inside the caller's transaction. */
    @org.springframework.context.event.EventListener
    public void onSpaceDeleting(com.keplura.server.space.SpaceDeletingEvent event) {
        for (KnowledgeBase kb : kbRepository.findBySpaceId(event.spaceId())) {
            deleteAuthorized(kb);
        }
    }

    private KnowledgeBase requireOwnedKb(Long userId, Long orgId, Long spaceId, Long kbId) {
        KnowledgeBase kb = get(userId, orgId, spaceId, kbId);
        boolean owner = membershipRepository.findByOrgIdAndUserId(orgId, userId)
                .map(m -> m.getRole() == com.keplura.server.org.MembershipRole.OWNER)
                .orElse(false);
        if (!owner) {
            throw new NotOwnerException();
        }
        return kb;
    }

    /** Chain guard for the two levels above the kb: membership (org), then the space pinned to it. */
    private void requireSpace(Long userId, Long orgId, Long spaceId) {
        membershipRepository.findByOrgIdAndUserId(orgId, userId)
                .orElseThrow(KnowledgeBaseNotFoundException::new);
        spaceRepository.findByIdInOrgForMember(spaceId, orgId, userId)
                .orElseThrow(KnowledgeBaseNotFoundException::new);
    }
}
