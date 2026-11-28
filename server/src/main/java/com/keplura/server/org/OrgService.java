package com.keplura.server.org;

import com.keplura.server.identity.User;
import com.keplura.server.identity.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Organization use-cases for the authenticated session (M2c, docs/adr/0004).
 * The server is authoritative: the caller's identity is ONLY ever the session
 * principal id the controller passes in — never anything from the request body
 * or path. Creation writes organization + OWNER membership in one transaction
 * so there is no window where an org exists without a member who can see it.
 */
@Service
public class OrgService {

    /** An organization paired with the caller's role in it — the list-view unit. */
    public record OrgMembership(Organization organization, MembershipRole role) {
    }

    /** 409 — the change would leave the org without any OWNER. */
    public static class LastOwnerException extends RuntimeException {
        public LastOwnerException() {
            super("an organization needs at least one owner");
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

    private final OrganizationRepository organizationRepository;
    private final MembershipRepository membershipRepository;
    private final UserRepository userRepository;

    public OrgService(OrganizationRepository organizationRepository,
                      MembershipRepository membershipRepository,
                      UserRepository userRepository) {
        this.organizationRepository = organizationRepository;
        this.membershipRepository = membershipRepository;
        this.userRepository = userRepository;
    }

    /** Raised by the controller into 400 — input problem, not authorization. */
    public static class InvalidOrgNameException extends RuntimeException {
        public InvalidOrgNameException(String message) {
            super(message);
        }
    }

    /** Raised by the controller into 404 — "absent OR not yours", indistinguishable. */
    public static class OrgNotFoundException extends RuntimeException {
        public OrgNotFoundException() {
            super("organization not found");
        }
    }

    @Transactional
    public Organization createOrg(Long userId, String rawName) {
        Organization org;
        try {
            org = Organization.create(rawName);
        } catch (IllegalArgumentException e) {
            throw new InvalidOrgNameException(e.getMessage());
        }
        Organization saved = organizationRepository.saveAndFlush(org);
        membershipRepository.saveAndFlush(Membership.grant(saved.getId(), userId, MembershipRole.OWNER));
        return saved;
    }

    /**
     * One organization of the caller plus the caller's role in it. The id set
     * comes from the membership table for THIS user only, so the result can
     * never contain an org the caller is not in (tenant isolation, ADR-0004).
     */
    @Transactional(readOnly = true)
    public List<OrgMembership> listMyOrgs(Long userId) {
        List<Membership> mine = membershipRepository.findByUserId(userId);
        if (mine.isEmpty()) {
            return List.of();
        }
        Map<Long, MembershipRole> roles = mine.stream()
                .collect(Collectors.toMap(Membership::getOrgId, Membership::getRole,
                        (a, b) -> a));
        return organizationRepository.findAllById(roles.keySet()).stream()
                .sorted(Comparator.comparing(Organization::getId))
                .map(org -> new OrgMembership(org, roles.get(org.getId())))
                .toList();
    }

    /**
     * One organization by id, visible ONLY to a member: the membership row for
     * (orgId, userId) must exist first, and the org is then loaded through the
     * membership-filtered finder — two independent guards (docs/adr/0004).
     * Non-member and non-existent id both surface as {@link OrgNotFoundException}
     * (uniform 404), so the API cannot be used to probe which org ids exist.
     */
    @Transactional(readOnly = true)
    public OrgMembership getMyOrg(Long userId, Long orgId) {
        Membership membership = membershipRepository.findByOrgIdAndUserId(orgId, userId)
                .orElseThrow(OrgNotFoundException::new);
        Organization org = organizationRepository.findByIdForMember(orgId, userId)
                .orElseThrow(OrgNotFoundException::new);
        return new OrgMembership(org, membership.getRole());
    }

    /**
     * The one membership WRITE beyond org creation (SEC-2): an invite
     * redemption grants the promised role. Private on purpose — the ONLY
     * caller is {@link InviteService#accept} inside its redeem transaction,
     * so no controller can ever hand out membership without a valid,
     * unexpired, unspent token. The save is an INSERT by construction
     * (Membership.isNew), so a duplicate grant surfaces as a PK violation,
     * never a silent role UPDATE (see the Membership entity javadoc).
     */
    @Transactional
    void grantMembership(Long orgId, Long userId, MembershipRole role) {
        membershipRepository.saveAndFlush(Membership.grant(orgId, userId, role));
    }

    /**
     * Full roster of an org (SEC-2 OWNER panel): membership rows joined to
     * user display names, oldest member first (the OWNER who created the org
     * is therefore index 0). OWNER-gated through the same pre-check the
     * invite surfaces use — existence (404) before privilege (403).
     */
    @Transactional(readOnly = true)
    public List<MemberView> listMembers(Long callerUserId, Long orgId) {
        OrgMembership view = getMyOrg(callerUserId, orgId);
        if (view.role() != MembershipRole.OWNER) {
            throw new NotOwnerException();
        }
        return membershipRepository.findByOrgIdOrderByCreatedAtAsc(orgId).stream()
                .map(m -> new MemberView(m.getUserId(),
                        userRepository.findById(m.getUserId())
                                .map(User::getDisplayName)
                                .orElse("(removed user)"),
                        m.getRole().name(), m.getCreatedAt()))
                .toList();
    }

    /** Renames an org (OWNER only): 400 invalid, 403, uniform 404. */
    @Transactional
    public Organization renameOrg(Long callerUserId, Long orgId, String rawName) {
        Organization org = requireOwner(callerUserId, orgId).organization();
        try {
            org.rename(rawName);
        } catch (IllegalArgumentException e) {
            throw new InvalidOrgNameException(e.getMessage());
        }
        return organizationRepository.saveAndFlush(org);
    }

    /**
     * Deletes an org with everything in it (OWNER only): spaces → knowledge
     * bases → documents/files, chats (via {@link OrgDeletingEvent}), open
     * invites and memberships. User accounts stay (they may belong to other orgs).
     */
    @Transactional
    public void deleteOrg(Long callerUserId, Long orgId) {
        Organization org = requireOwner(callerUserId, orgId).organization();
        events.publishEvent(new OrgDeletingEvent(orgId));
        jdbc.update("delete from org_invite where org_id = ?", orgId);
        jdbc.update("delete from membership where org_id = ?", orgId);
        // TU-06b: personal OAuth connections never outlive the organization.
        jdbc.update("delete from tool_oauth_flow where org_id = ?", orgId);
        jdbc.update("delete from tool_oauth_connection where org_id = ?", orgId);
        organizationRepository.delete(org);
        organizationRepository.flush();
    }

    /**
     * Removes a member. OWNERs may remove anyone; every member may remove
     * themselves (leave). The last OWNER can never be removed (409) — delete
     * the org instead.
     */
    @Transactional
    public void removeMember(Long callerUserId, Long orgId, Long memberUserId) {
        OrgMembership caller = getMyOrg(callerUserId, orgId);
        if (!callerUserId.equals(memberUserId) && caller.role() != MembershipRole.OWNER) {
            throw new NotOwnerException();
        }
        Membership target = membershipRepository.findByOrgIdAndUserId(orgId, memberUserId)
                .orElseThrow(OrgNotFoundException::new);
        if (target.getRole() == MembershipRole.OWNER
                && membershipRepository.countByOrgIdAndRole(orgId, MembershipRole.OWNER) <= 1) {
            throw new LastOwnerException();
        }
        membershipRepository.delete(target);
        membershipRepository.flush();
        // TU-06b: whoever leaves (or is removed) loses their personal connections of this organization at once.
        jdbc.update("delete from tool_oauth_flow where user_id = ? and org_id = ?", memberUserId, orgId);
        jdbc.update("delete from tool_oauth_connection where user_id = ? and org_id = ?", memberUserId, orgId);
    }

    private OrgMembership requireOwner(Long callerUserId, Long orgId) {
        OrgMembership view = getMyOrg(callerUserId, orgId);
        if (view.role() != MembershipRole.OWNER) {
            throw new NotOwnerException();
        }
        return view;
    }

    /** A roster row: user id, display name, role, join instant. */
    public record MemberView(Long userId, String displayName, String role, Instant joinedAt) {
    }

    /** Raised into 403 by the SEC-2 invite/member surfaces (OWNER-only reads). */
    public static class NotOwnerException extends RuntimeException {
        public NotOwnerException() {
            super("owner role required");
        }
    }
}
