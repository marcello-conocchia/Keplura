package com.keplura.server.org;

import com.keplura.server.identity.PasswordHasher;
import com.keplura.server.identity.User;
import com.keplura.server.identity.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.TestPropertySource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * M2c: persistence behaviour of {@link Organization} / {@link Membership}
 * against embedded H2 (slice test, same convention as UserRepositoryTest).
 * The unique (org_id, user_id) key and the membership-filtered finder are the
 * DB-level half of the tenant isolation story — they must hold even if every
 * service-layer check were bypassed.
 */
@DataJpaTest
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:org-test;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
class OrgRepositoryTest {

    private static final PasswordHasher HASHER = new PasswordHasher();

    @Autowired
    private OrganizationRepository organizationRepository;

    @Autowired
    private MembershipRepository membershipRepository;

    @Autowired
    private UserRepository userRepository;

    private Long newUserId(String email) {
        return userRepository.saveAndFlush(User.create(email, HASHER.hash("pw"), email)).getId();
    }

    @Test
    void persistsOrganizationWithCreatedAtAndTrimsName() {
        Organization saved = organizationRepository.saveAndFlush(Organization.create("  Acme GmbH  "));

        assertThat(saved.getId()).isNotNull();
        assertThat(saved.getName()).isEqualTo("Acme GmbH");
        assertThat(saved.getCreatedAt()).isNotNull();
    }

    @Test
    void duplicateGrantOfSameOrgAndUserViolatesThePrimaryKey() {
        // The Persistable new-flag forces INSERT even though the id is set, so
        // a second grant of the same (org, user) pair hits the PK instead of
        // silently merging (and overwriting) the existing role.
        Long userId = newUserId("dup@example.com");
        Organization org = organizationRepository.saveAndFlush(Organization.create("Dup Corp"));

        membershipRepository.saveAndFlush(Membership.grant(org.getId(), userId, MembershipRole.OWNER));

        assertThatThrownBy(() -> membershipRepository.saveAndFlush(
                Membership.grant(org.getId(), userId, MembershipRole.MEMBER)))
                .isInstanceOf(DataIntegrityViolationException.class);
        // NOTE: session is poisoned after the failed flush (M2a lesson) — no
        // further queries in this test.
    }

    @Test
    void userMayBelongToManyOrgsWithDifferentRoles() {
        Long userId = newUserId("multi@example.com");
        Organization a = organizationRepository.saveAndFlush(Organization.create("A"));
        Organization b = organizationRepository.saveAndFlush(Organization.create("B"));

        membershipRepository.saveAndFlush(Membership.grant(a.getId(), userId, MembershipRole.OWNER));
        membershipRepository.saveAndFlush(Membership.grant(b.getId(), userId, MembershipRole.MEMBER));

        List<Membership> mine = membershipRepository.findByUserId(userId);
        assertThat(mine).hasSize(2);
        assertThat(mine).extracting(Membership::getRole)
                .containsExactlyInAnyOrder(MembershipRole.OWNER, MembershipRole.MEMBER);
    }

    @Test
    void findByIdForMemberHidesForeignOrgsButRevealsMemberOrgs() {
        Long member = newUserId("member@example.com");
        Long stranger = newUserId("stranger@example.com");
        Organization org = organizationRepository.saveAndFlush(Organization.create("Secret Sauce"));
        membershipRepository.saveAndFlush(Membership.grant(org.getId(), member, MembershipRole.MEMBER));

        assertThat(organizationRepository.findByIdForMember(org.getId(), member)).isPresent();
        // The IDOR core: same id, non-member caller -> empty (never "exists but denied").
        assertThat(organizationRepository.findByIdForMember(org.getId(), stranger)).isEmpty();
        // Unknown id is also empty -> the two cases are indistinguishable upstream.
        assertThat(organizationRepository.findByIdForMember(987654L, member)).isEmpty();
    }

    @Test
    void createRejectsBlankAndOverlongNames() {
        assertThatThrownBy(() -> Organization.create(null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Organization.create("   "))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Organization.create("x".repeat(Organization.MAX_NAME_LENGTH + 1)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void grantRejectsNullCoordinates() {
        assertThatThrownBy(() -> Membership.grant(null, 1L, MembershipRole.OWNER))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Membership.grant(1L, null, MembershipRole.OWNER))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Membership.grant(1L, 1L, null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
