package com.keplura.server.knowledge;

import com.keplura.server.org.Membership;
import com.keplura.server.org.MembershipRepository;
import com.keplura.server.org.MembershipRole;
import com.keplura.server.space.Space;
import com.keplura.server.space.SpaceRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * M3c: unit-level decision logic of {@link KnowledgeBaseService} with mocked
 * repositories (wire proof lives in KnowledgeBaseApiIntegrationTest). Theme
 * inherited from M3b: a missing guard short-circuits EVERYTHING — with no
 * membership the space table is never consulted, with a foreign space the kb
 * table is never consulted — so authorization failure and non-existence can
 * never diverge into different observable outcomes.
 */
@ExtendWith(MockitoExtension.class)
class KnowledgeBaseServiceTest {

    @Mock
    private KnowledgeBaseRepository kbRepository;

    @Mock
    private SpaceRepository spaceRepository;

    @Mock
    private MembershipRepository membershipRepository;

    private KnowledgeBaseService service;

    @BeforeEach
    void setUp() {
        service = new KnowledgeBaseService(kbRepository, spaceRepository, membershipRepository);
    }

    private void givenMember(Long orgId, Long userId) {
        when(membershipRepository.findByOrgIdAndUserId(orgId, userId))
                .thenReturn(Optional.of(Membership.grant(orgId, userId, MembershipRole.MEMBER)));
    }

    private void givenSpaceInOrg(Long spaceId, Long orgId, Long userId) {
        givenMember(orgId, userId);
        Space space = Space.create(orgId, "S" + spaceId);
        ReflectionTestUtils.setField(space, "id", spaceId);
        when(spaceRepository.findByIdInOrgForMember(spaceId, orgId, userId))
                .thenReturn(Optional.of(space));
    }

    // ---- create ----

    @Test
    void createPersistsKbStampedWithThePathSpace() {
        givenSpaceInOrg(7L, 3L, 9L);
        when(kbRepository.findBySpaceIdAndName(7L, "Projekte")).thenReturn(Optional.empty());
        when(kbRepository.saveAndFlush(any(KnowledgeBase.class))).thenAnswer(inv -> {
            KnowledgeBase kb = inv.getArgument(0);
            ReflectionTestUtils.setField(kb, "id", 11L);
            return kb;
        });

        KnowledgeBase created = service.create(9L, 3L, 7L, "  Projekte  ");

        assertThat(created.getId()).isEqualTo(11L);
        assertThat(created.getSpaceId()).isEqualTo(7L);
        assertThat(created.getName()).isEqualTo("Projekte");
    }

    @Test
    void nonMemberCannotCreateAndKbTableStaysUntouched() {
        when(membershipRepository.findByOrgIdAndUserId(3L, 99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.create(99L, 3L, 7L, "Boom"))
                .isInstanceOf(KnowledgeBaseService.KnowledgeBaseNotFoundException.class);
        verify(spaceRepository, never()).findByIdInOrgForMember(anyLong(), anyLong(), anyLong());
        verify(kbRepository, never()).saveAndFlush(any());
        verify(kbRepository, never()).findBySpaceIdAndName(anyLong(), any());
    }

    @Test
    void foreignSpaceCannotHostAKbEvenForAnOrgMember() {
        // member of org 3, but space 7 belongs to org 4 -> space pin fails ->
        // the kb table must not be consulted at all (uniform 404)
        givenMember(3L, 9L);
        when(spaceRepository.findByIdInOrgForMember(7L, 3L, 9L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.create(9L, 3L, 7L, "Nope"))
                .isInstanceOf(KnowledgeBaseService.KnowledgeBaseNotFoundException.class);
        verify(kbRepository, never()).saveAndFlush(any());
    }

    @Test
    void duplicateNameAnswers409AndLostRaceMapsToTheSame409() {
        givenSpaceInOrg(7L, 3L, 9L);
        when(kbRepository.findBySpaceIdAndName(7L, "Dup"))
                .thenReturn(Optional.of(KnowledgeBase.create(7L, "Dup")));

        assertThatThrownBy(() -> service.create(9L, 3L, 7L, "Dup"))
                .isInstanceOf(KnowledgeBaseService.DuplicateKbNameException.class);

        // lost the race pre-insert path: same outcome, no 500
        org.mockito.Mockito.reset(kbRepository);
        when(kbRepository.findBySpaceIdAndName(7L, "Dup")).thenReturn(Optional.empty());
        when(kbRepository.saveAndFlush(any())).thenThrow(new DataIntegrityViolationException("unique"));
        assertThatThrownBy(() -> service.create(9L, 3L, 7L, "Dup"))
                .isInstanceOf(KnowledgeBaseService.DuplicateKbNameException.class);
    }

    @Test
    void blankOrOverlongNamesAre400AfterMembershipHolds() {
        givenSpaceInOrg(7L, 3L, 9L);

        assertThatThrownBy(() -> service.create(9L, 3L, 7L, "   "))
                .isInstanceOf(KnowledgeBaseService.InvalidKbNameException.class);
        assertThatThrownBy(() -> service.create(9L, 3L, 7L, "x".repeat(KnowledgeBase.MAX_NAME_LENGTH + 1)))
                .isInstanceOf(KnowledgeBaseService.InvalidKbNameException.class);
        verify(kbRepository, never()).saveAndFlush(any());
    }

    // ---- list / get ----

    @Test
    void listRequiresTheFullChainAndReturnsRepoResult() {
        givenSpaceInOrg(7L, 3L, 9L);
        when(kbRepository.findAllInSpaceForMember(7L, 3L, 9L))
                .thenReturn(List.of(KnowledgeBase.create(7L, "Ein")));

        assertThat(service.list(9L, 3L, 7L)).hasSize(1);

        // non-member collapses before the repo is touched
        org.mockito.Mockito.reset(membershipRepository, spaceRepository, kbRepository);
        when(membershipRepository.findByOrgIdAndUserId(3L, 42L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.list(42L, 3L, 7L))
                .isInstanceOf(KnowledgeBaseService.KnowledgeBaseNotFoundException.class);
        verify(kbRepository, never()).findAllInSpaceForMember(anyLong(), anyLong(), anyLong());
    }

    @Test
    void getMissingKbIsTheSame404AsAForeignOne() {
        givenSpaceInOrg(7L, 3L, 9L);
        when(kbRepository.findByIdInSpaceForMember(11L, 7L, 3L, 9L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.get(9L, 3L, 7L, 11L))
                .isInstanceOf(KnowledgeBaseService.KnowledgeBaseNotFoundException.class);
    }
}
