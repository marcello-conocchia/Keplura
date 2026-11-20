package com.keplura.server.space;

import com.keplura.server.org.MembershipRepository;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Space use-cases for the authenticated session (M3b). Authorization model:
 * <b>org membership IS the space ACL</b> — deliberate MVP decision, no
 * SpaceMembership table until a real permission difference exists
 * (docs/adr/0004 carry-over: M3 queries chain through org membership).
 *
 * <p>Every method takes the caller id ONLY from the session principal (the
 * controller passes {@code AuthPrincipal.userId}), checks the membership row
 * for the PATH's org first, and then reads through membership-filtered
 * queries ({@link SpaceRepository}) — two independent guards, so neither a
 * foreign org nor a space reached through the wrong org path returns data.
 * All denial shapes collapse into {@link SpaceNotFoundException} (uniform
 * 404): the API never confirms that an org or space id exists.
 */
@Service
public class SpaceService {

    /** Raised by the controller into 400 — input problem, not authorization. */
    public static class InvalidSpaceNameException extends RuntimeException {
        public InvalidSpaceNameException(String message) {
            super(message);
        }
    }

    /** Raised by the controller into 404 — absent OR not yours, indistinguishable. */
    public static class SpaceNotFoundException extends RuntimeException {
        public SpaceNotFoundException() {
            super("space not found for this caller");
        }
    }

    /** Raised by the controller into 409 — the (org, name) unique key forbids it. */
    public static class DuplicateSpaceNameException extends RuntimeException {
        public DuplicateSpaceNameException() {
            super("space name already taken in this organization");
        }
    }

    /** 403 — member of the org, but rename/delete is reserved to org OWNERs. */
    public static class NotOwnerException extends RuntimeException {
        public NotOwnerException() {
            super("only an org owner may change this space");
        }
    }

    private org.springframework.context.ApplicationEventPublisher events;
    private org.springframework.jdbc.core.JdbcTemplate jdbc;

    @org.springframework.beans.factory.annotation.Autowired
    void setDeleteCollaborators(org.springframework.context.ApplicationEventPublisher events,
                                org.springframework.jdbc.core.JdbcTemplate jdbc) {
        this.events = events;
        this.jdbc = jdbc;
    }

    private final SpaceRepository spaceRepository;
    private final MembershipRepository membershipRepository;

    public SpaceService(SpaceRepository spaceRepository,
                        MembershipRepository membershipRepository) {
        this.spaceRepository = spaceRepository;
        this.membershipRepository = membershipRepository;
    }

    /**
     * Creates a space in the caller's organization. Membership in {@code orgId}
     * is proven BEFORE anything touches the space table; a concurrent insert
     * racing past the name pre-check hits the DB-level (org_id, name) unique
     * key and is surfaced as the same 409, never as a 500.
     */
    @Transactional
    public Space createSpace(Long userId, Long orgId, String rawName) {
        requireMembership(userId, orgId);
        Space space;
        try {
            space = Space.create(orgId, rawName);
        } catch (IllegalArgumentException e) {
            throw new InvalidSpaceNameException(e.getMessage());
        }
        if (spaceRepository.findByOrgIdAndName(orgId, space.getName()).isPresent()) {
            throw new DuplicateSpaceNameException();
        }
        try {
            return spaceRepository.saveAndFlush(space);
        } catch (DataIntegrityViolationException e) {
            // lost the race against an identical (org, name) — same outcome as the pre-check
            throw new DuplicateSpaceNameException();
        }
    }

    /** Every space of the org — empty ONLY provable after membership holds. */
    @Transactional(readOnly = true)
    public List<Space> listSpaces(Long userId, Long orgId) {
        requireMembership(userId, orgId);
        return spaceRepository.findAllInOrgForMember(orgId, userId);
    }

    /**
     * One space by id, ONLY through its own organization's path and only for
     * members of it. The repo query additionally pins {@code s.orgId}, so a
     * victim's space id probed under the attacker's org path 404s exactly like
     * a never-existing id (the M3b IDOR probe).
     */
    @Transactional(readOnly = true)
    public Space getSpace(Long userId, Long orgId, Long spaceId) {
        requireMembership(userId, orgId);
        return spaceRepository.findByIdInOrgForMember(spaceId, orgId, userId)
                .orElseThrow(SpaceNotFoundException::new);
    }

    /** Renames a space (org OWNER only): 400 invalid, 409 duplicate, 403, uniform 404. */
    @Transactional
    public Space renameSpace(Long userId, Long orgId, Long spaceId, String rawName) {
        Space space = requireOwnedSpace(userId, orgId, spaceId);
        String before = space.getName();
        try {
            space.rename(rawName);
        } catch (IllegalArgumentException e) {
            throw new InvalidSpaceNameException(e.getMessage());
        }
        if (!space.getName().equals(before)
                && spaceRepository.findByOrgIdAndName(orgId, space.getName()).isPresent()) {
            throw new DuplicateSpaceNameException();
        }
        try {
            return spaceRepository.saveAndFlush(space);
        } catch (org.springframework.dao.DataIntegrityViolationException e) {
            throw new DuplicateSpaceNameException();
        }
    }

    /**
     * Deletes a space (org OWNER only): its knowledge bases with all
     * documents (via {@link SpaceDeletingEvent}, same transaction), its chat
     * conversations and messages, then the row.
     */
    @Transactional
    public void deleteSpace(Long userId, Long orgId, Long spaceId) {
        deleteAuthorized(requireOwnedSpace(userId, orgId, spaceId));
    }

    /** Org delete cascade: every space of the org, inside the caller's transaction. */
    @org.springframework.context.event.EventListener
    public void onOrgDeleting(com.keplura.server.org.OrgDeletingEvent event) {
        for (Space space : spaceRepository.findByOrgId(event.orgId())) {
            deleteAuthorized(space);
        }
    }

    private void deleteAuthorized(Space space) {
        Long orgId = space.getOrgId();
        Long spaceId = space.getId();
        events.publishEvent(new SpaceDeletingEvent(orgId, spaceId));
        jdbc.update("""
                delete from chat_message where conversation_id in
                  (select id from chat_conversation where org_id = ? and space_id = ?)
                """, orgId, spaceId);
        jdbc.update("delete from chat_conversation where org_id = ? and space_id = ?", orgId, spaceId);
        spaceRepository.delete(space);
        spaceRepository.flush();
    }

    private Space requireOwnedSpace(Long userId, Long orgId, Long spaceId) {
        Space space = getSpace(userId, orgId, spaceId);
        boolean owner = membershipRepository.findByOrgIdAndUserId(orgId, userId)
                .map(m -> m.getRole() == com.keplura.server.org.MembershipRole.OWNER)
                .orElse(false);
        if (!owner) {
            throw new NotOwnerException();
        }
        return space;
    }

    /** First guard: the (org, user) membership row must exist for the path's org. */
    private void requireMembership(Long userId, Long orgId) {
        membershipRepository.findByOrgIdAndUserId(orgId, userId)
                .orElseThrow(SpaceNotFoundException::new);
    }
}
