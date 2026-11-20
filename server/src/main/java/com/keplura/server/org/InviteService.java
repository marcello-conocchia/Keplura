package com.keplura.server.org;

import com.keplura.server.identity.User;
import com.keplura.server.identity.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;

/**
 * Invite use-cases (SEC-2, docs/adr/0004 extended). The server stays
 * authoritative on both ends of the flow:
 *
 * <ul>
 *   <li><b>Create</b> requires the caller's live OWNER role in the target org
 *       (checked through OrgService.getMyOrg, so a foreign org id is the
 *       uniform 404 before the role check can even hint at existence). The
 *       plaintext token is returned EXACTLY ONCE and never stored — only its
 *       SHA-256 digest rides to the database.</li>
 *   <li><b>Accept</b> is by-token for ANY authenticated user: it pins the
 *       caller's session id as the new MEMBER (the token body carries no
 *       identity), marks the row used and grants the membership in ONE
 *       transaction. Re-use, expiry and unknown tokens all collapse into the
 *       same {@link InvalidInviteException} (uniform 404 — the API cannot
 *       probe which tokens exist).</li>
 * </ul>
 *
 * <p>No email is sent and no user is created: accounts stay dev-seeded until
 * OIDC (explicit Non-Goal) — an invite moves an EXISTING account into an org.
 * The granted role is always MEMBER (see V8 CHECK).
 */
@Service
public class InviteService {

    /** Every invite dies this old — a short life keeps the out-of-band handoff safe. */
    public static final Duration INVITE_TTL = Duration.ofDays(7);

    /** Plaintext token entropy: 32 SecureRandom bytes, lowercase hex (64 chars). */
    private static final int TOKEN_BYTES = 32;

    private static final SecureRandom RANDOM = new SecureRandom();

    /** A created invite plus the ONE-TIME plaintext token for the response. */
    public record CreatedInvite(OrgInvite invite, String token) {
    }

    /** A pending invite paired with its inviter's display name (list view). */
    public record InviteView(OrgInvite invite, String inviterName) {
    }

    /** Raised into 404 by the accept controller — unknown/used/expired, indistinguishable. */
    public static class InvalidInviteException extends RuntimeException {
        public InvalidInviteException() {
            super("invite not found");
        }
    }

    /** Raised into 409 — the caller is already a member; the invite stays usable. */
    public static class AlreadyMemberException extends RuntimeException {
        public AlreadyMemberException() {
            super("already a member of this organization");
        }
    }

    private final OrgInviteRepository inviteRepository;
    private final OrgService orgService;
    private final UserRepository userRepository;
    private final OrganizationRepository organizationRepository;
    private final MembershipRepository membershipRepository;

    public InviteService(OrgInviteRepository inviteRepository,
                         OrgService orgService,
                         UserRepository userRepository,
                         OrganizationRepository organizationRepository,
                         MembershipRepository membershipRepository) {
        this.inviteRepository = inviteRepository;
        this.orgService = orgService;
        this.userRepository = userRepository;
        this.organizationRepository = organizationRepository;
        this.membershipRepository = membershipRepository;
    }

    /** Lowercase hex SHA-256 of the plaintext token; the ONLY stored form. */
    public static String hashToken(String plaintext) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(plaintext.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 missing from the JVM", e);
        }
    }

    /** OWNER-only: mint a one-time invite; the plaintext token is returned for exactly one response. */
    @Transactional
    public CreatedInvite create(Long callerUserId, Long orgId) {
        requireOwner(callerUserId, orgId);
        byte[] buf = new byte[TOKEN_BYTES];
        RANDOM.nextBytes(buf);
        String plaintext = HexFormat.of().formatHex(buf);
        OrgInvite invite = OrgInvite.create(orgId, callerUserId, hashToken(plaintext),
                MembershipRole.MEMBER, Instant.now().plus(INVITE_TTL));
        OrgInvite saved = inviteRepository.saveAndFlush(invite);
        return new CreatedInvite(saved, plaintext);
    }

    /** Pending invites of an org (OWNER only), each paired with its inviter's name. */
    @Transactional(readOnly = true)
    public List<InviteView> listPending(Long callerUserId, Long orgId) {
        requireOwner(callerUserId, orgId);
        Instant now = Instant.now();
        return inviteRepository.findByOrgIdOrderByCreatedAtDesc(orgId).stream()
                .filter(inv -> inv.isPending(now))
                .map(inv -> new InviteView(inv, userRepository.findById(inv.getInviterId())
                        .map(User::getDisplayName)
                        .orElse("(removed user)")))
                .toList();
    }

    /**
     * OWNER revokes invite {@code inviteId} of {@code orgId}. The delete is
     * tenant-pinned (org_id AND id): a foreign invite id answers 0 rows,
     * which the controller maps to the uniform 404 like everywhere else.
     */
    @Transactional
    public boolean revoke(Long callerUserId, Long orgId, Long inviteId) {
        requireOwner(callerUserId, orgId);
        return inviteRepository.deleteByOrgIdAndId(orgId, inviteId) > 0;
    }

    /**
     * Redeem {@code plaintextToken} for the CALLER (any authenticated user).
     * One transaction: token row -> validity -> prechecks -> ATOMIC
     * used-stamp (conditional UPDATE, FINDING-1) -> membership grant, so a
     * crash cannot leave a spent token without a member or vice versa — and
     * concurrent accepts of one token cannot both win: the loser's UPDATE
     * matches 0 rows and gets the uniform 404 ("already used").
     *
     * @return the org that was joined (id + name for the response body)
     */
    @Transactional
    public Organization accept(Long callerUserId, String plaintextToken) {
        if (plaintextToken == null || plaintextToken.isBlank()) {
            throw new InvalidInviteException();
        }
        OrgInvite invite = inviteRepository.findByTokenHash(hashToken(plaintextToken.trim()))
                .orElseThrow(InvalidInviteException::new);
        Instant now = Instant.now();
        if (!invite.isPending(now)) {
            // used OR expired — one signal, deliberately not distinguishable
            // from "unknown token" (no probing). Revoked rows are deleted, so
            // they land in the unknown branch anyway.
            throw new InvalidInviteException();
        }
        // Membership check WITHOUT the throwing OrgService path: an exception
        // through the inner @Transactional readOnly proxy would mark the
        // shared transaction rollback-only (UnexpectedRollbackException ->
        // 500 on the already-member branch, caught by InviteApiIntegration-
        // Test). Direct repository reads carry no transactional boundary.
        // Order matters: org existence first (a vanished org is a uniform
        // 404, not a "you may join" lie and not a 409 hint), then membership.
        if (!organizationRepository.existsById(invite.getOrgId())) {
            throw new InvalidInviteException();
        }
        if (membershipRepository.findByOrgIdAndUserId(invite.getOrgId(), callerUserId).isPresent()) {
            // An existing membership is a conflict, not a re-grant; the
            // invite stays pending for someone else.
            throw new AlreadyMemberException();
        }
        // FINDING-1 (race-safe redemption): the isPending() check above is a
        // read that a concurrent accept can pass on the SAME row before
        // either one writes — stamping via JPA dirty-checking would be a
        // lost update and mint two MEMBERs from a one-time token. The
        // conditional UPDATE below lets the database decide the winner:
        // the row lock serialises the two statements, exactly one sees
        // used_at IS NULL and updates 1 row; every loser re-evaluates the
        // predicate against the committed row (used_at is now set) and
        // updates 0.
        int redeemed = inviteRepository.redeemAtomically(invite.getTokenHash(), callerUserId, now);
        if (redeemed != 1) {
            // Lost the race (or expired between the precheck and the UPDATE):
            // honest uniform 404 — the token WAS used, by the winner. No
            // membership grant happens on this path.
            throw new InvalidInviteException();
        }
        orgService.grantMembership(invite.getOrgId(), callerUserId, invite.getRole());
        Organization org = organizationRepository.findById(invite.getOrgId())
                .orElseThrow(() -> new IllegalStateException(
                        "invite references an org that vanished mid-transaction"));
        return org;
    }

    /**
     * The OWNER pre-check shared by create/list/revoke: getMyOrg throws
     * {@link OrgService.OrgNotFoundException} for non-members (uniform 404,
     * no existence leak), and only a member whose role is NOT OWNER reaches
     * {@link NotOwnerException} (403). Order matters for the anti-probing
     * contract — existence first, then privilege.
     */
    private void requireOwner(Long callerUserId, Long orgId) {
        OrgService.OrgMembership view = orgService.getMyOrg(callerUserId, orgId);
        if (view.role() != MembershipRole.OWNER) {
            throw new OrgService.NotOwnerException();
        }
    }
}
