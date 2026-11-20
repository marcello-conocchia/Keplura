package com.keplura.server.knowledge;

import com.keplura.server.identity.PasswordHasher;
import com.keplura.server.identity.User;
import com.keplura.server.identity.UserRepository;
import com.keplura.server.org.Membership;
import com.keplura.server.org.MembershipRepository;
import com.keplura.server.org.MembershipRole;
import com.keplura.server.org.Organization;
import com.keplura.server.org.OrganizationRepository;
import com.keplura.server.space.Space;
import com.keplura.server.space.SpaceRepository;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.TestPropertySource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * M3c: persistence behaviour of {@link KnowledgeBase} against embedded H2
 * (slice test, same convention as SpaceRepositoryTest). The (space_id, name)
 * unique key and the membership-filtered finders are the DB-level half of the
 * kb tenant story — they must hold even if every service-layer check were
 * bypassed.
 */
@DataJpaTest
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:kb-test;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
class KnowledgeBaseRepositoryTest {

    private static final PasswordHasher HASHER = new PasswordHasher();

    @Autowired
    private KnowledgeBaseRepository kbRepository;

    @Autowired
    private DocumentRepository documentRepository;

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

    private Long newSpace(Long orgId, String name) {
        return spaceRepository.saveAndFlush(Space.create(orgId, name)).getId();
    }

    @Test
    void persistsKbWithSpaceIdCreatedAtAndTrimmedName() {
        Long spaceId = newSpace(newOrg("Acme"), "Hauptspace");
        KnowledgeBase saved = kbRepository.saveAndFlush(KnowledgeBase.create(spaceId,  "  Handbuch  "));

        assertThat(saved.getId()).isNotNull();
        assertThat(saved.getSpaceId()).isEqualTo(spaceId);
        assertThat(saved.getName()).isEqualTo("Handbuch");
        assertThat(saved.getCreatedAt()).isNotNull();
    }

    @Test
    void duplicateNameWithinOneSpaceViolatesKeyDifferentSpacesMayReuseIt() {
        Long spaceA = newSpace(newOrg("A Corp"), "Space A");
        Long spaceB = newSpace(newOrg("B Corp"), "Space B");
        kbRepository.saveAndFlush(KnowledgeBase.create(spaceA, "Shared"));

        // same name in another space is fine
        assertThat(kbRepository.saveAndFlush(KnowledgeBase.create(spaceB, "Shared")).getId()).isNotNull();

        // ...but twice inside space A must hit the (space_id, name) key
        assertThatThrownBy(() -> kbRepository.saveAndFlush(KnowledgeBase.create(spaceA, "Shared")))
                .isInstanceOf(DataIntegrityViolationException.class);
        // NOTE: session is poisoned after the failed flush (M2a lesson) — no
        // further queries in this test.
    }

    @Test
    void findAllInSpaceForMemberRevealsOnlyMemberOrgSpacesKbs() {
        Long member = newUserId("kb-member@example.com");
        Long stranger = newUserId("kb-stranger@example.com");
        Long orgId = newOrg("Secret Ltd");
        Long spaceId = newSpace(orgId, "Intern");
        kbRepository.saveAndFlush(KnowledgeBase.create(spaceId, "Ablagen"));

        assertThat(kbRepository.findAllInSpaceForMember(spaceId, orgId, stranger)).isEmpty();

        membershipRepository.saveAndFlush(Membership.grant(orgId, member, MembershipRole.MEMBER));
        assertThat(kbRepository.findAllInSpaceForMember(spaceId, orgId, member))
                .extracting(KnowledgeBase::getName).containsExactly("Ablagen");
        // stranger still sees nothing (uniform empty; service guard 404s first anyway)
        assertThat(kbRepository.findAllInSpaceForMember(spaceId, orgId, stranger)).isEmpty();
    }

    @Test
    void findByIdInSpaceForMemberPinsTheSpaceSoWrongPathYieldsEmpty() {
        Long both = newUserId("kb-both@example.com");
        Long orgMine = newOrg("Mine Org");
        Long orgYours = newOrg("Yours Org");
        membershipRepository.saveAndFlush(Membership.grant(orgMine, both, MembershipRole.OWNER));
        membershipRepository.saveAndFlush(Membership.grant(orgYours, both, MembershipRole.OWNER));
        Long mySpace = newSpace(orgMine, "My Space");
        Long yourSpace = newSpace(orgYours, "Your Space");
        KnowledgeBase kb = kbRepository.saveAndFlush(KnowledgeBase.create(yourSpace, "Yours KB"));

        // correct path works
        assertThat(kbRepository.findByIdInSpaceForMember(kb.getId(), yourSpace, orgYours, both))
                .isPresent();
        // THE M3c IDOR core (level 3): my own org+space path + the victim kb
        // id -> empty, exactly like an unknown id.
        assertThat(kbRepository.findByIdInSpaceForMember(kb.getId(), mySpace, orgMine, both))
                .isEmpty();
        assertThat(kbRepository.findByIdInSpaceForMember(987654L, yourSpace, orgYours, both))
                .isEmpty();
        // victim kb under MY space but with YOUR org id (member there) — the
        // space pin still collapses it: the kb belongs to the other space.
        assertThat(kbRepository.findByIdInSpaceForMember(kb.getId(), mySpace, orgYours, both))
                .isEmpty();
    }

    @Test
    void createRejectsNullSpaceAndBlankOrOverlongNames() {
        assertThatThrownBy(() -> KnowledgeBase.create(null, "No Tenant"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> KnowledgeBase.create(1L, "   "))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> KnowledgeBase.create(1L, "x".repeat(KnowledgeBase.MAX_NAME_LENGTH + 1)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void documentFinderChainPinsKbSpaceAndMembershipInOneQuery() {
        Long owner = newUserId("doc-owner@example.com");
        Long stranger = newUserId("doc-stranger@example.com");
        Long orgId = newOrg("Doc Co");
        Long spaceId = newSpace(orgId, "D-Space");
        membershipRepository.saveAndFlush(Membership.grant(orgId, owner, MembershipRole.OWNER));
        KnowledgeBase kb = kbRepository.saveAndFlush(KnowledgeBase.create(spaceId, "D-KB"));
        Document doc = documentRepository.saveAndFlush(Document.of(kb.getId(), "a.txt",
                "text/plain; charset=utf-8", 3L, "0".repeat(64),
                "orgs/" + orgId + "/spaces/" + spaceId + "/kbs/" + kb.getId() + "/u-1", owner));

        // owner through the correct chain: found, and listed in upload order
        assertThat(documentRepository.findByIdInKbForMember(doc.getId(), kb.getId(), spaceId, orgId, owner))
                .isPresent();
        assertThat(documentRepository.findAllInKbForMember(kb.getId(), spaceId, orgId, owner))
                .extracting(Document::getFilename).containsExactly("a.txt");
        // stranger (no membership) sees nothing even with correct coordinates
        assertThat(documentRepository.findByIdInKbForMember(doc.getId(), kb.getId(), spaceId, orgId, stranger))
                .isEmpty();
        // right org, WRONG space path pinning the kb -> empty (kb belongs to spaceId)
        Long otherSpace = newSpace(orgId, "Other Space");
        assertThat(documentRepository.findByIdInKbForMember(doc.getId(), kb.getId(), otherSpace, orgId, owner))
                .isEmpty();
        // wrong kb path -> empty even though the space/org are the real ones
        KnowledgeBase otherKb = kbRepository.saveAndFlush(KnowledgeBase.create(spaceId, "Other KB"));
        assertThat(documentRepository.findByIdInKbForMember(doc.getId(), otherKb.getId(), spaceId, orgId, owner))
                .isEmpty();
    }
}
