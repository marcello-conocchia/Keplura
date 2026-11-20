package com.keplura.server.org;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentMatchers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
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
 * M2c: unit-level decision logic of {@link OrgService} with mocked
 * repositories — the wire-level proof lives in OrgApiIntegrationTest.
 */
@ExtendWith(MockitoExtension.class)
class OrgServiceTest {

    @Mock
    private OrganizationRepository organizationRepository;

    @Mock
    private MembershipRepository membershipRepository;

    /** SEC-2: OrgService now reads display names for the roster (listMembers). */
    @Mock
    private com.keplura.server.identity.UserRepository userRepository;

    private OrgService service;

    @BeforeEach
    void setUp() {
        service = new OrgService(organizationRepository, membershipRepository, userRepository);
    }

    @Test
    void createPersistsOrgAndOwnerMembershipTogether() {
        when(organizationRepository.saveAndFlush(any(Organization.class))).thenAnswer(inv -> {
            Organization org = inv.getArgument(0);
            ReflectionTestUtils.setField(org, "id", 7L);
            return org;
        });

        Organization created = service.createOrg(42L, " Nova Labs ");

        assertThat(created.getName()).isEqualTo("Nova Labs");
        verify(membershipRepository).saveAndFlush(ArgumentMatchers.argThat(m ->
                m.getOrgId().equals(7L) && m.getUserId().equals(42L)
                        && m.getRole() == MembershipRole.OWNER));
    }

    @Test
    void blankNameBecomesInvalidOrgNameNotDatabaseError() {
        assertThatThrownBy(() -> service.createOrg(1L, "   "))
                .isInstanceOf(OrgService.InvalidOrgNameException.class);
        verify(organizationRepository, never()).saveAndFlush(any());
    }

    @Test
    void listIsBuiltStrictlyFromThisUsersMemberships() {
        Long me = 5L;
        Membership m1 = Membership.grant(11L, me, MembershipRole.OWNER);
        Membership m2 = Membership.grant(12L, me, MembershipRole.MEMBER);
        when(membershipRepository.findByUserId(me)).thenReturn(List.of(m1, m2));
        when(organizationRepository.findAllById(any(Iterable.class))).thenReturn(List.of(
                orgWithId(12L, "Second"), orgWithId(11L, "First")));

        List<OrgService.OrgMembership> mine = service.listMyOrgs(me);

        // stable id order, role carried from the matching membership row
        assertThat(mine).extracting(v -> v.organization().getName())
                .containsExactly("First", "Second");
        assertThat(mine).extracting(OrgService.OrgMembership::role)
                .containsExactly(MembershipRole.OWNER, MembershipRole.MEMBER);
    }

    @Test
    void listIsEmptyWithoutMembershipsAndTouchesNoOrgTable() {
        when(membershipRepository.findByUserId(9L)).thenReturn(List.of());

        assertThat(service.listMyOrgs(9L)).isEmpty();
        verify(organizationRepository, never()).findAllById(any(Iterable.class));
    }

    @Test
    void foreignAndUnknownIdsBothThrowTheSameNotFound() {
        when(membershipRepository.findByOrgIdAndUserId(anyLong(), anyLong()))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getMyOrg(99L, 3L))
                .isInstanceOf(OrgService.OrgNotFoundException.class);
        assertThatThrownBy(() -> service.getMyOrg(99L, 999L))
                .isInstanceOf(OrgService.OrgNotFoundException.class);
        // no membership -> the org table is never even consulted (no side channel)
        verify(organizationRepository, never()).findByIdForMember(anyLong(), anyLong());
    }

    @Test
    void memberSeesOrgThroughBothGuards() {
        when(membershipRepository.findByOrgIdAndUserId(3L, 9L))
                .thenReturn(Optional.of(Membership.grant(3L, 9L, MembershipRole.MEMBER)));
        when(organizationRepository.findByIdForMember(3L, 9L))
                .thenReturn(Optional.of(orgWithId(3L, "Acme")));

        OrgService.OrgMembership view = service.getMyOrg(9L, 3L);

        assertThat(view.organization().getName()).isEqualTo("Acme");
        assertThat(view.role()).isEqualTo(MembershipRole.MEMBER);
    }

    private static Organization orgWithId(Long id, String name) {
        Organization org = Organization.create(name);
        ReflectionTestUtils.setField(org, "id", id);
        return org;
    }
}
