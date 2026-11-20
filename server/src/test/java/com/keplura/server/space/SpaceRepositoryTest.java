package com.keplura.server.space;

import com.keplura.server.identity.PasswordHasher;
import com.keplura.server.identity.User;
import com.keplura.server.identity.UserRepository;
import com.keplura.server.org.Membership;
import com.keplura.server.org.MembershipRepository;
import com.keplura.server.org.MembershipRole;
import com.keplura.server.org.Organization;
import com.keplura.server.org.OrganizationRepository;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.TestPropertySource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * M3b: persistence behaviour of {@link Space} against embedded H2 (slice test,
 * same convention as OrgRepositoryTest). The (org_id, name) unique key and the
 * membership-filtered finders are the DB-level half of the space tenant story —
 * they must hold even if every service-layer check were bypassed.
 */
@DataJpaTest
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:space-test;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
class SpaceRepositoryTest {

    private static final PasswordHasher HASHER = new PasswordHasher();

    @Autowired
    private SpaceRepository spaceRepository;

    @Autowired
    private OrganizationRepository organizationRepository;

    @Autowired
    private MembershipRepository membershipRepository;

    @Autowired
    private UserRepository userRepository;

    private Long newUserId(String email) {
        return userRepository.saveAndFlush(User.create(email, HASHER.hash("pw"), email)).getId();
    }

    private Long newOrg(String name) {
        return organizationRepository.saveAndFlush(Organization.create(name)).getId();
    }

    @Test
    void persistsSpaceWithOrgIdCreatedAtAndTrimmedName() {
        Long orgId = newOrg("Acme");
        Space saved = spaceRepository.saveAndFlush(Space.create(orgId, "  Prototypen  "));

        assertThat(saved.getId()).isNotNull();
        assertThat(saved.getOrgId()).isEqualTo(orgId);
        assertThat(saved.getName()).isEqualTo("Prototypen");
        assertThat(saved.getCreatedAt()).isNotNull();
    }

    @Test
    void duplicateNameWithinOneOrgViolatesTheUniqueKeyDifferentOrgsMayReuseIt() {
        Long orgA = newOrg("A Corp");
        Long orgB = newOrg("B Corp");
        spaceRepository.saveAndFlush(Space.create(orgA, "Shared"));

        // same name in another org is fine
        assertThat(spaceRepository.saveAndFlush(Space.create(orgB, "Shared")).getId()).isNotNull();

        // ...but twice inside org A must hit the (org_id, name) key
        assertThatThrownBy(() -> spaceRepository.saveAndFlush(Space.create(orgA, "Shared")))
                .isInstanceOf(DataIntegrityViolationException.class);
        // NOTE: session is poisoned after the failed flush (M2a lesson) — no
        // further queries in this test.
    }

    @Test
    void findAllInOrgForMemberRevealsOnlyMemberOrgsSpaces() {
        Long member = newUserId("s-member@example.com");
        Long stranger = newUserId("s-stranger@example.com");
        Long orgId = newOrg("Secret Ltd");
        spaceRepository.saveAndFlush(Space.create(orgId, "Inside"));

        List<Space> beforeGrant = spaceRepository.findAllInOrgForMember(orgId, stranger);
        assertThat(beforeGrant).isEmpty(); // no membership -> not even an empty-org answer with data

        membershipRepository.saveAndFlush(Membership.grant(orgId, member, MembershipRole.MEMBER));
        assertThat(spaceRepository.findAllInOrgForMember(orgId, member))
                .extracting(Space::getName).containsExactly("Inside");
        // stranger still sees nothing (uniform empty, upstream becomes 404 via service guard)
        assertThat(spaceRepository.findAllInOrgForMember(orgId, stranger)).isEmpty();
    }

    @Test
    void findByIdInOrgForMemberPinsTheOrgSoWrongPathYieldsEmpty() {
        Long memberBoth = newUserId("s-both@example.com");
        Long orgMine = newOrg("Mine Org");
        Long orgYours = newOrg("Yours Org");
        membershipRepository.saveAndFlush(Membership.grant(orgMine, memberBoth, MembershipRole.OWNER));
        membershipRepository.saveAndFlush(Membership.grant(orgYours, memberBoth, MembershipRole.OWNER));
        Space space = spaceRepository.saveAndFlush(Space.create(orgYours, "Yours Space"));

        // correct path works
        assertThat(spaceRepository.findByIdInOrgForMember(space.getId(), orgYours, memberBoth))
                .isPresent();
        // THE M3b IDOR core: my membership in org "Mine" + the victim space id
        // reached through MY org path -> empty, exactly like an unknown id.
        assertThat(spaceRepository.findByIdInOrgForMember(space.getId(), orgMine, memberBoth))
                .isEmpty();
        assertThat(spaceRepository.findByIdInOrgForMember(987654L, orgYours, memberBoth))
                .isEmpty();
    }

    @Test
    void createRejectsNullOrgAndBlankOrOverlongNames() {
        assertThatThrownBy(() -> Space.create(null, "No Tenant"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Space.create(1L, "   "))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Space.create(1L, "x".repeat(Space.MAX_NAME_LENGTH + 1)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
