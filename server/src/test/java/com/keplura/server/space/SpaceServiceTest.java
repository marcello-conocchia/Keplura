package com.keplura.server.space;

import com.keplura.server.org.Membership;
import com.keplura.server.org.MembershipRepository;
import com.keplura.server.org.MembershipRole;

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
 * M3b: unit-level decision logic of {@link SpaceService} with mocked
 * repositories — the wire-level proof lives in SpaceApiIntegrationTest.
 * The recurring theme: a missing membership short-circuits EVERYTHING (the
 * space table is never consulted) so authorization failure and non-existence
 * cannot diverge into different observable outcomes.
 */
@ExtendWith(MockitoExtension.class)
class SpaceServiceTest {

    @Mock
    private SpaceRepository spaceRepository;

    @Mock
    private MembershipRepository membershipRepository;

    private SpaceService service;

    @BeforeEach
    void setUp() {
        service = new SpaceService(spaceRepository, membershipRepository);
    }

    private void givenMember(Long orgId, Long userId) {
        when(membershipRepository.findByOrgIdAndUserId(orgId, userId))
                .thenReturn(Optional.of(Membership.grant(orgId, userId, MembershipRole.MEMBER)));
    }

    // ---- create ----

    @Test
    void createPersistsSpaceStampedWithThePathOrg() {
        givenMember(3L, 9L);
        when(spaceRepository.findByOrgIdAndName(3L, "Projekte")).thenReturn(Optional.empty());
        when(spaceRepository.saveAndFlush(any(Space.class))).thenAnswer(inv -> {
            Space space = inv.getArgument(0);
            ReflectionTestUtils.setField(space, "id", 11L);
            return space;
        });

        Space created = service.createSpace(9L, 3L, "  Projekte  ");

        assertThat(created.getId()).isEqualTo(11L);
        assertThat(created.getOrgId()).isEqualTo(3L);
        assertThat(created.getName()).isEqualTo("Projekte");
    }

    @Test
    void nonMemberCannotCreateAndSpaceTableStaysUntouched() {
        when(membershipRepository.findByOrgIdAndUserId(3L, 99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.createSpace(99L, 3L, "Boom"))
                .isInstanceOf(SpaceService.SpaceNotFoundException.class);
        verify(spaceRepository, never()).saveAndFlush(any());
        verify(spaceRepository, never()).findByOrgIdAndName(anyLong(), any());
    }

    @Test
    void blankNameBecomesInvalidSpaceNameNotDatabaseError() {
        givenMember(3L, 9L);

        assertThatThrownBy(() -> service.createSpace(9L, 3L, "   "))
                .isInstanceOf(SpaceService.InvalidSpaceNameException.class);
        assertThatThrownBy(() -> service.createSpace(9L, 3L, "x".repeat(Space.MAX_NAME_LENGTH + 1)))
                .isInstanceOf(SpaceService.InvalidSpaceNameException.class);
        verify(spaceRepository, never()).saveAndFlush(any());
    }

    @Test
    void duplicateNameInSameOrgIsRejectedBeforeInsert() {
        givenMember(3L, 9L);
        when(spaceRepository.findByOrgIdAndName(3L, "Dup")).thenReturn(Optional.of(space(7L, 3L, "Dup")));

        assertThatThrownBy(() -> service.createSpace(9L, 3L, "Dup"))
                .isInstanceOf(SpaceService.DuplicateSpaceNameException.class);
        verify(spaceRepository, never()).saveAndFlush(any());
    }

    @Test
    void lostRaceAgainstUniqueKeySurfacesAsConflictNotFiveHundred() {
        givenMember(3L, 9L);
        when(spaceRepository.findByOrgIdAndName(3L, "Race")).thenReturn(Optional.empty());
        when(spaceRepository.saveAndFlush(any(Space.class)))
                .thenThrow(new DataIntegrityViolationException("unique key violated"));

        assertThatThrownBy(() -> service.createSpace(9L, 3L, "Race"))
                .isInstanceOf(SpaceService.DuplicateSpaceNameException.class);
    }

    // ---- list ----

    @Test
    void listGoesThroughTheMembershipFilteredFinder() {
        givenMember(3L, 9L);
        when(spaceRepository.findAllInOrgForMember(3L, 9L)).thenReturn(List.of(space(1L, 3L, "A")));

        assertThat(service.listSpaces(9L, 3L)).hasSize(1);
    }

    @Test
    void listOfForeignOrgIsUniform404AndSpaceTableNeverRead() {
        when(membershipRepository.findByOrgIdAndUserId(4L, 9L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.listSpaces(9L, 4L))
                .isInstanceOf(SpaceService.SpaceNotFoundException.class);
        verify(spaceRepository, never()).findAllInOrgForMember(anyLong(), anyLong());
    }

    // ---- get one ----

    @Test
    void memberSeesSpaceThroughTheOrgPinnedFinder() {
        givenMember(3L, 9L);
        when(spaceRepository.findByIdInOrgForMember(11L, 3L, 9L))
                .thenReturn(Optional.of(space(11L, 3L, "Visible")));

        Space view = service.getSpace(9L, 3L, 11L);

        assertThat(view.getName()).isEqualTo("Visible");
    }

    @Test
    void foreignSpaceIdAndWrongOrgPathAndUnknownIdAllCollapseToTheSame404() {
        givenMember(3L, 9L); // member of org 3, NOT of org 4 which owns space 11
        when(spaceRepository.findByIdInOrgForMember(anyLong(), anyLong(), anyLong()))
                .thenReturn(Optional.empty());

        // victim's space under the attacker's own org path -> empty from the query
        assertThatThrownBy(() -> service.getSpace(9L, 3L, 11L))
                .isInstanceOf(SpaceService.SpaceNotFoundException.class);
        // unknown id -> identical
        assertThatThrownBy(() -> service.getSpace(9L, 3L, 99999L))
                .isInstanceOf(SpaceService.SpaceNotFoundException.class);
    }

    @Test
    void nonMemberGets404BeforeAnySpaceLookup() {
        when(membershipRepository.findByOrgIdAndUserId(3L, 99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getSpace(99L, 3L, 11L))
                .isInstanceOf(SpaceService.SpaceNotFoundException.class);
        verify(spaceRepository, never()).findByIdInOrgForMember(anyLong(), anyLong(), anyLong());
    }

    private static Space space(Long id, Long orgId, String name) {
        Space space = Space.create(orgId, name);
        ReflectionTestUtils.setField(space, "id", id);
        return space;
    }
}
