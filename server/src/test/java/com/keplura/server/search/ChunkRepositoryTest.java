package com.keplura.server.search;

import com.keplura.server.identity.PasswordHasher;
import com.keplura.server.identity.User;
import com.keplura.server.identity.UserRepository;
import com.keplura.server.knowledge.Document;
import com.keplura.server.knowledge.DocumentRepository;
import com.keplura.server.knowledge.KnowledgeBase;
import com.keplura.server.knowledge.KnowledgeBaseRepository;
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
import org.springframework.test.context.TestPropertySource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * M3d: the chunk candidate query is the LAST tenant gate before ranking —
 * it must pin kb + space + org-membership in ONE statement, exactly like the
 * M3c document reads. A foreign user, a wrong space path or a wrong kb path
 * must see ZERO rows for a victim's chunks (empty candidate list ⇒ empty
 * result list regardless of scores).
 */
@DataJpaTest
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:chunk-test;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
class ChunkRepositoryTest {

    private static final PasswordHasher HASHER = new PasswordHasher();

    @Autowired private org.springframework.jdbc.core.JdbcTemplate jdbc;
    @Autowired private jakarta.persistence.EntityManager entityManager;
    @Autowired private ChunkRepository chunkRepository;
    @Autowired private DocumentRepository documentRepository;
    @Autowired private KnowledgeBaseRepository kbRepository;
    @Autowired private SpaceRepository spaceRepository;
    @Autowired private OrganizationRepository organizationRepository;
    @Autowired private MembershipRepository membershipRepository;
    @Autowired private UserRepository userRepository;

    private record Chain(Long orgId, Long spaceId, Long kbId, Long docId, Long userId) {
    }

    private Chain buildChain(String email, String orgName) {
        Long userId = userRepository.saveAndFlush(User.create(email, HASHER.hash("pw"), email)).getId();
        Long orgId = organizationRepository.saveAndFlush(Organization.create(orgName)).getId();
        membershipRepository.saveAndFlush(Membership.grant(orgId, userId, MembershipRole.MEMBER));
        Long spaceId = spaceRepository.saveAndFlush(Space.create(orgId, "S-" + orgName)).getId();
        Long kbId = kbRepository.saveAndFlush(KnowledgeBase.create(spaceId, "KB-" + orgName)).getId();
        Document doc = documentRepository.saveAndFlush(Document.of(kbId, "a.txt",
                "text/plain; charset=utf-8", 5L, "ab".repeat(32), "orgs/x/a", userId));
        chunkRepository.saveAndFlush(Chunk.of(doc.getId(), kbId, 0, "chunk null", "[1.0,0.0]"));
        chunkRepository.saveAndFlush(Chunk.of(doc.getId(), kbId, 1, "chunk eins", "[0.0,1.0]"));
        return new Chain(orgId, spaceId, kbId, doc.getId(), userId);
    }

    @Test
    void memberSeesAllChunksThroughTheCorrectChain() {
        Chain victim = buildChain("v@e.com", "Victor");
        List<Chunk> rows = chunkRepository.findCandidateChunks(
                victim.kbId(), victim.spaceId(), victim.orgId(), victim.userId()).stream()
                .map(ChunkRepository.Candidate::getChunk).toList();
        assertThat(rows).hasSize(2);
        assertThat(rows).extracting(Chunk::getChunkIdx).containsExactly(0, 1);
        assertThat(rows).allSatisfy(c -> assertThat(c.getText()).startsWith("chunk "));
    }

    @Test
    void sourceTimestampUsesUploadDateOrOriginalFileModificationAndSurvivesRanking() {
        Chain chain = buildChain("freshness@example.test", "Freshness");
        entityManager.clear();
        var upload = documentRepository.findByIdInKbForMember(chain.docId(), chain.kbId(),
                chain.spaceId(), chain.orgId(), chain.userId()).orElseThrow();
        assertThat(upload.getSourceUpdatedAt()).isEqualTo(upload.getCreatedAt());
        var direct = chunkRepository.findCandidateChunks(chain.kbId(), chain.spaceId(), chain.orgId(), chain.userId());
        assertThat(direct).allSatisfy(c -> assertThat(c.getSourceUpdatedAt()).isEqualTo(upload.getCreatedAt()));

        var modified = java.time.Instant.parse("2020-04-12T10:30:00Z");
        jdbc.update("insert into data_source_file (data_source_id, relative_path, document_id, size_bytes, modified_at, acl_fingerprint) values (?, ?, ?, ?, ?, ?)",
                1L, "handbuch.txt", chain.docId(), 5L, java.sql.Timestamp.from(modified), "");
        entityManager.clear();
        var imported = documentRepository.findByIdInKbForMember(chain.docId(), chain.kbId(),
                chain.spaceId(), chain.orgId(), chain.userId()).orElseThrow();
        assertThat(imported.getSourceUpdatedAt()).isEqualTo(modified);
        assertThat(imported.getCreatedAt()).isAfter(modified);
        var hits = new MemoryEmbeddingStore(chunkRepository).search(new float[]{1, 0}, chain.kbId(),
                chain.spaceId(), chain.orgId(), chain.userId(), 2);
        assertThat(hits).hasSize(2).allSatisfy(h -> {
            assertThat(h.sourceUpdatedAt()).isEqualTo(modified);
            assertThat(h.withScore(0.5).sourceUpdatedAt()).isEqualTo(modified);
        });
    }

    @Test
    void foreignUserSeesNothingEvenWithFullyCorrectPathIds() {
        Chain victim = buildChain("v2@e.com", "Victor2");
        Long attackerId = userRepository.saveAndFlush(User.create("a2@e.com", HASHER.hash("pw"), "a2")).getId();

        assertThat(chunkRepository.findCandidateChunks(
                victim.kbId(), victim.spaceId(), victim.orgId(), attackerId)).isEmpty();
    }

    @Test
    void attackerChainNeverYieldsVictimChunks() {
        Chain victim = buildChain("v3@e.com", "Victor3");
        Chain attacker = buildChain("a3@e.com", "Mallory3");

        // victim's kb id probed under the ATTACKER's org/space path
        assertThat(chunkRepository.findCandidateChunks(
                victim.kbId(), attacker.spaceId(), attacker.orgId(), attacker.userId())).isEmpty();
        // and the other direction (correct kb under wrong space): empty too
        assertThat(chunkRepository.findCandidateChunks(
                victim.kbId(), victim.spaceId(), attacker.orgId(), attacker.userId())).isEmpty();
        // unknown ids collapse identically
        assertThat(chunkRepository.findCandidateChunks(
                victim.kbId(), 987654L, 987654L, 987654L)).isEmpty();
    }

    @Test
    void uniqueKeyMakesReindexingTheSameChunkPositionFail() {
        Chain chain = buildChain("v4@e.com", "Victor4");
        assertThat(chunkRepository.findByDocumentIdOrderByChunkIdx(chain.docId())).hasSize(2);
        org.junit.jupiter.api.Assertions.assertThrows(
                org.springframework.dao.DataIntegrityViolationException.class,
                () -> chunkRepository.saveAndFlush(
                        Chunk.of(chain.docId(), chain.kbId(), 0, "dup", "[1.0,1.0]")));
    }
}
