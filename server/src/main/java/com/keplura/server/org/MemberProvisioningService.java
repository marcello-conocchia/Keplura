package com.keplura.server.org;

import com.keplura.server.identity.PasswordHasher;
import com.keplura.server.identity.User;
import com.keplura.server.identity.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;

/**
 * PILOT 4e: Verwalter:innen einer Organisation nehmen einen Mitarbeiter direkt
 * auf. Gibt es das Konto (E-Mail) schon, wird es nur Mitglied — sein Passwort
 * bleibt unberührt. Ist es neu, entsteht ein Konto mit einmalig angezeigtem
 * Startpasswort, das beim ersten Login ersetzt werden muss.
 *
 * <p>Security-Hinweise (für das Security-Gate): nur OWNER der Organisation;
 * das Startpasswort wird nie gespeichert oder geloggt (nur der Argon2-Hash);
 * die Antwort "bestehendes Konto" verrät Verwalter:innen, dass die E-Mail
 * bereits registriert ist — für eine Firmeninstallation bewusst akzeptiert.
 */
@Service
public class MemberProvisioningService {

    /** Ohne leicht verwechselbare Zeichen (0/O, 1/l/I). */
    private static final char[] ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789".toCharArray();
    private static final SecureRandom RANDOM = new SecureRandom();

    public static class InvalidMemberException extends RuntimeException {
        public InvalidMemberException(String message) {
            super(message);
        }
    }

    public static class AlreadyMemberException extends RuntimeException {
        public AlreadyMemberException() {
            super("already a member");
        }
    }

    /** startPassword is non-null ONLY for a newly created account (shown once). */
    public record Provisioned(Long userId, String displayName, boolean createdAccount, String startPassword) {
    }

    private final OrgService orgService;
    private final MembershipRepository membershipRepository;
    private final UserRepository userRepository;
    private final PasswordHasher passwordHasher;

    public MemberProvisioningService(OrgService orgService, MembershipRepository membershipRepository,
                                     UserRepository userRepository, PasswordHasher passwordHasher) {
        this.orgService = orgService;
        this.membershipRepository = membershipRepository;
        this.userRepository = userRepository;
        this.passwordHasher = passwordHasher;
    }

    @Transactional
    public Provisioned addMember(Long callerUserId, Long orgId, String rawEmail, String rawDisplayName) {
        OrgService.OrgMembership caller = orgService.getMyOrg(callerUserId, orgId);
        if (caller.role() != MembershipRole.OWNER) {
            throw new OrgService.NotOwnerException();
        }
        if (rawEmail == null || rawEmail.isBlank() || !rawEmail.contains("@") || rawEmail.length() > 320) {
            throw new InvalidMemberException("email is invalid");
        }
        String email = User.normalizeEmail(rawEmail);
        var existing = userRepository.findByEmail(email);
        if (existing.isPresent()) {
            User user = existing.get();
            if (membershipRepository.findByOrgIdAndUserId(orgId, user.getId()).isPresent()) {
                throw new AlreadyMemberException();
            }
            membershipRepository.saveAndFlush(Membership.grant(orgId, user.getId(), MembershipRole.MEMBER));
            return new Provisioned(user.getId(), user.getDisplayName(), false, null);
        }
        String displayName = rawDisplayName == null || rawDisplayName.isBlank()
                ? email.substring(0, email.indexOf('@')) : rawDisplayName.trim();
        if (displayName.length() > 200) {
            throw new InvalidMemberException("display name too long");
        }
        String startPassword = generateStartPassword();
        String startHash = passwordHasher.hash(startPassword);
        User user = User.create(email, startHash, displayName);
        user.issueStartPassword(startHash);
        User saved = userRepository.saveAndFlush(user);
        membershipRepository.saveAndFlush(Membership.grant(orgId, saved.getId(), MembershipRole.MEMBER));
        return new Provisioned(saved.getId(), saved.getDisplayName(), true, startPassword);
    }

    /** 4 Gruppen à 4 Zeichen, z. B. "Kp7x-3mQa-Zt9r-W2hc" (~ 93 Bit). */
    static String generateStartPassword() {
        StringBuilder sb = new StringBuilder(19);
        for (int g = 0; g < 4; g++) {
            if (g > 0) sb.append('-');
            for (int i = 0; i < 4; i++) {
                sb.append(ALPHABET[RANDOM.nextInt(ALPHABET.length)]);
            }
        }
        return sb.toString();
    }
}
