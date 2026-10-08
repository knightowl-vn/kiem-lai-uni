package com.universe.screen.application.usecase;

import com.universe.screen.application.commands.AddScreenFranchiseMemberCommand;
import com.universe.screen.application.commands.RemoveScreenFranchiseMemberCommand;
import com.universe.screen.application.commands.UpdateScreenFranchiseMemberOrderCommand;
import com.universe.screen.application.dto.ScreenFranchiseMemberDTO;
import com.universe.screen.application.dto.ScreenTitleSummaryDTO;
import com.universe.screen.application.exceptions.DuplicateScreenFranchiseMembershipException;
import com.universe.screen.application.exceptions.ScreenFranchiseMembershipNotFoundException;
import com.universe.screen.application.exceptions.ScreenFranchiseNotFoundException;
import com.universe.screen.application.exceptions.ScreenTitleNotFoundException;
import com.universe.screen.application.ports.ScreenFranchiseMembershipRepositoryPort;
import com.universe.screen.application.ports.ScreenFranchiseRepositoryPort;
import com.universe.screen.application.ports.ScreenTitleRepositoryPort;
import com.universe.screen.domain.ScreenFranchiseMembership;
import com.universe.screen.domain.ScreenPresentation;
import com.universe.screen.domain.ScreenStructure;
import com.universe.screen.domain.ScreenTitle;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ScreenFranchiseMembershipUseCaseTest {

    @Mock
    private ScreenFranchiseRepositoryPort franchiseRepository;

    @Mock
    private ScreenFranchiseMembershipRepositoryPort membershipRepository;

    @Mock
    private ScreenTitleRepositoryPort titleRepository;

    private GetScreenFranchiseMembersUseCase getMembersUseCase;
    private GetAvailableScreenTitlesUseCase getAvailableTitlesUseCase;
    private AddScreenFranchiseMemberUseCase addMemberUseCase;
    private UpdateScreenFranchiseMemberOrderUseCase updateOrderUseCase;
    private RemoveScreenFranchiseMemberUseCase removeMemberUseCase;

    private final UUID franchiseId = UUID.randomUUID();
    private final UUID titleId1 = UUID.randomUUID();
    private final UUID titleId2 = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        getMembersUseCase = new GetScreenFranchiseMembersUseCase(franchiseRepository, membershipRepository, titleRepository);
        getAvailableTitlesUseCase = new GetAvailableScreenTitlesUseCase(franchiseRepository, membershipRepository, titleRepository);
        addMemberUseCase = new AddScreenFranchiseMemberUseCase(franchiseRepository, membershipRepository, titleRepository);
        updateOrderUseCase = new UpdateScreenFranchiseMemberOrderUseCase(franchiseRepository, membershipRepository, titleRepository);
        removeMemberUseCase = new RemoveScreenFranchiseMemberUseCase(franchiseRepository, membershipRepository);
    }

    private ScreenTitle createTestTitle(UUID id, String slug, String title) {
        return ScreenTitle.createDraft(
                id, slug, title, ScreenStructure.SERIES, ScreenPresentation.ANIMATION_3D, Instant.parse("2026-10-06T10:00:00Z")
        );
    }

    @Test
    @DisplayName("Membership: list members preserves deterministic order from repository")
    void shouldListMembersInCommittedDeterministicOrder() {
        when(franchiseRepository.existsById(franchiseId)).thenReturn(true);

        UUID mid1 = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID mid2 = UUID.fromString("00000000-0000-0000-0000-000000000002");
        ScreenFranchiseMembership m1 = new ScreenFranchiseMembership(mid1, franchiseId, titleId1, 0);
        ScreenFranchiseMembership m2 = new ScreenFranchiseMembership(mid2, franchiseId, titleId2, 1);

        when(membershipRepository.findByFranchiseId(franchiseId)).thenReturn(List.of(m1, m2));
        when(titleRepository.findById(titleId1)).thenReturn(Optional.of(createTestTitle(titleId1, "part-1", "Part 1")));
        when(titleRepository.findById(titleId2)).thenReturn(Optional.of(createTestTitle(titleId2, "part-2", "Part 2")));

        List<ScreenFranchiseMemberDTO> members = getMembersUseCase.execute(franchiseId);

        assertThat(members).hasSize(2);
        assertThat(members.get(0).membershipId()).isEqualTo(mid1);
        assertThat(members.get(0).displayOrder()).isEqualTo(0);
        assertThat(members.get(0).title()).isEqualTo("Part 1");

        assertThat(members.get(1).membershipId()).isEqualTo(mid2);
        assertThat(members.get(1).displayOrder()).isEqualTo(1);
        assertThat(members.get(1).title()).isEqualTo("Part 2");
    }

    @Test
    @DisplayName("Membership: list members throws ScreenFranchiseNotFoundException if franchise missing")
    void shouldThrowNotFoundWhenListingMembersOfMissingFranchise() {
        when(franchiseRepository.existsById(franchiseId)).thenReturn(false);

        assertThatThrownBy(() -> getMembersUseCase.execute(franchiseId))
                .isInstanceOf(ScreenFranchiseNotFoundException.class);
    }

    @Test
    @DisplayName("Membership: add member with explicit displayOrder saves membership")
    void shouldAddMembershipWithExplicitDisplayOrder() {
        when(franchiseRepository.existsById(franchiseId)).thenReturn(true);
        when(titleRepository.findById(titleId1)).thenReturn(Optional.of(createTestTitle(titleId1, "part-1", "Part 1")));
        when(membershipRepository.existsByFranchiseIdAndTitleId(franchiseId, titleId1)).thenReturn(false);
        when(membershipRepository.save(any(ScreenFranchiseMembership.class))).thenAnswer(inv -> inv.getArgument(0));

        ScreenFranchiseMemberDTO result = addMemberUseCase.execute(
                new AddScreenFranchiseMemberCommand(franchiseId, titleId1, 5)
        );

        assertThat(result.franchiseId()).isEqualTo(franchiseId);
        assertThat(result.titleId()).isEqualTo(titleId1);
        assertThat(result.displayOrder()).isEqualTo(5);
        assertThat(result.title()).isEqualTo("Part 1");
    }

    @Test
    @DisplayName("Membership: add member with null displayOrder auto-assigns next order")
    void shouldAutoAssignNextDisplayOrderWhenNull() {
        when(franchiseRepository.existsById(franchiseId)).thenReturn(true);
        when(titleRepository.findById(titleId2)).thenReturn(Optional.of(createTestTitle(titleId2, "part-2", "Part 2")));
        when(membershipRepository.existsByFranchiseIdAndTitleId(franchiseId, titleId2)).thenReturn(false);

        ScreenFranchiseMembership existing = new ScreenFranchiseMembership(UUID.randomUUID(), franchiseId, titleId1, 2);
        when(membershipRepository.findByFranchiseId(franchiseId)).thenReturn(List.of(existing));
        when(membershipRepository.save(any(ScreenFranchiseMembership.class))).thenAnswer(inv -> inv.getArgument(0));

        ScreenFranchiseMemberDTO result = addMemberUseCase.execute(
                new AddScreenFranchiseMemberCommand(franchiseId, titleId2, null)
        );

        assertThat(result.displayOrder()).isEqualTo(3);
    }

    @Test
    @DisplayName("Membership: add member throws DuplicateScreenFranchiseMembershipException on duplicate title pre-check and does not call save")
    void shouldThrowDuplicateMembershipExceptionWhenAlreadyMember() {
        when(franchiseRepository.existsById(franchiseId)).thenReturn(true);
        when(titleRepository.findById(titleId1)).thenReturn(Optional.of(createTestTitle(titleId1, "part-1", "Part 1")));
        when(membershipRepository.existsByFranchiseIdAndTitleId(franchiseId, titleId1)).thenReturn(true);

        assertThatThrownBy(() -> addMemberUseCase.execute(
                new AddScreenFranchiseMemberCommand(franchiseId, titleId1, 0)
        )).isInstanceOf(DuplicateScreenFranchiseMembershipException.class);
        verify(membershipRepository, never()).save(any());
    }

    @Test
    @DisplayName("Membership: add member propagates DuplicateScreenFranchiseMembershipException on persistence race conflict")
    void shouldPropagateDuplicateMembershipExceptionOnPersistenceRaceConflict() {
        when(franchiseRepository.existsById(franchiseId)).thenReturn(true);
        when(titleRepository.findById(titleId1)).thenReturn(Optional.of(createTestTitle(titleId1, "part-1", "Part 1")));
        when(membershipRepository.existsByFranchiseIdAndTitleId(franchiseId, titleId1)).thenReturn(false);
        when(membershipRepository.save(any(ScreenFranchiseMembership.class)))
                .thenThrow(new DuplicateScreenFranchiseMembershipException(franchiseId, titleId1));

        assertThatThrownBy(() -> addMemberUseCase.execute(
                new AddScreenFranchiseMemberCommand(franchiseId, titleId1, 0)
        )).isInstanceOf(DuplicateScreenFranchiseMembershipException.class);
    }

    @Test
    @DisplayName("Membership: add member rejects negative displayOrder")
    void shouldRejectNegativeDisplayOrderOnAdd() {
        when(franchiseRepository.existsById(franchiseId)).thenReturn(true);
        when(titleRepository.findById(titleId1)).thenReturn(Optional.of(createTestTitle(titleId1, "part-1", "Part 1")));

        assertThatThrownBy(() -> addMemberUseCase.execute(
                new AddScreenFranchiseMemberCommand(franchiseId, titleId1, -1)
        )).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("Membership: add member throws ScreenFranchiseNotFoundException if franchise missing")
    void shouldThrowNotFoundOnAddMemberWithMissingFranchise() {
        when(franchiseRepository.existsById(franchiseId)).thenReturn(false);

        assertThatThrownBy(() -> addMemberUseCase.execute(
                new AddScreenFranchiseMemberCommand(franchiseId, titleId1, 0)
        )).isInstanceOf(ScreenFranchiseNotFoundException.class);
    }

    @Test
    @DisplayName("Membership: add member throws ScreenTitleNotFoundException if title missing")
    void shouldThrowNotFoundOnAddMemberWithMissingTitle() {
        when(franchiseRepository.existsById(franchiseId)).thenReturn(true);
        when(titleRepository.findById(titleId1)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> addMemberUseCase.execute(
                new AddScreenFranchiseMemberCommand(franchiseId, titleId1, 0)
        )).isInstanceOf(ScreenTitleNotFoundException.class);
    }

    @Test
    @DisplayName("Membership: update displayOrder successfully updates and saves")
    void shouldUpdateDisplayOrderSuccessfully() {
        UUID mid = UUID.randomUUID();
        ScreenFranchiseMembership membership = new ScreenFranchiseMembership(mid, franchiseId, titleId1, 0);

        when(franchiseRepository.existsById(franchiseId)).thenReturn(true);
        when(membershipRepository.findById(mid)).thenReturn(Optional.of(membership));
        when(membershipRepository.save(any(ScreenFranchiseMembership.class))).thenAnswer(inv -> inv.getArgument(0));
        when(titleRepository.findById(titleId1)).thenReturn(Optional.of(createTestTitle(titleId1, "part-1", "Part 1")));

        ScreenFranchiseMemberDTO updated = updateOrderUseCase.execute(
                new UpdateScreenFranchiseMemberOrderCommand(franchiseId, mid, 10)
        );

        assertThat(updated.displayOrder()).isEqualTo(10);
        assertThat(membership.getDisplayOrder()).isEqualTo(10);
        verify(membershipRepository).save(membership);
    }

    @Test
    @DisplayName("Membership: update displayOrder rejects negative order")
    void shouldRejectNegativeDisplayOrderOnUpdate() {
        UUID mid = UUID.randomUUID();

        assertThatThrownBy(() -> updateOrderUseCase.execute(
                new UpdateScreenFranchiseMemberOrderCommand(franchiseId, mid, -5)
        )).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("Membership: update displayOrder throws ScreenFranchiseMembershipNotFoundException when missing or wrong franchise")
    void shouldThrowNotFoundOnUpdateMissingMembershipOrWrongFranchise() {
        UUID mid = UUID.randomUUID();
        when(franchiseRepository.existsById(franchiseId)).thenReturn(true);
        when(membershipRepository.findById(mid)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> updateOrderUseCase.execute(
                new UpdateScreenFranchiseMemberOrderCommand(franchiseId, mid, 1)
        )).isInstanceOf(ScreenFranchiseMembershipNotFoundException.class);

        // Mismatched franchise
        ScreenFranchiseMembership wrongFranchiseMember = new ScreenFranchiseMembership(mid, UUID.randomUUID(), titleId1, 0);
        when(membershipRepository.findById(mid)).thenReturn(Optional.of(wrongFranchiseMember));

        assertThatThrownBy(() -> updateOrderUseCase.execute(
                new UpdateScreenFranchiseMemberOrderCommand(franchiseId, mid, 1)
        )).isInstanceOf(ScreenFranchiseMembershipNotFoundException.class);
    }

    @Test
    @DisplayName("Membership: remove member successfully deletes membership")
    void shouldRemoveMemberSuccessfully() {
        UUID mid = UUID.randomUUID();
        ScreenFranchiseMembership membership = new ScreenFranchiseMembership(mid, franchiseId, titleId1, 0);

        when(franchiseRepository.existsById(franchiseId)).thenReturn(true);
        when(membershipRepository.findById(mid)).thenReturn(Optional.of(membership));

        removeMemberUseCase.execute(new RemoveScreenFranchiseMemberCommand(franchiseId, mid));

        verify(membershipRepository).delete(membership);
    }

    @Test
    @DisplayName("Membership: remove member throws ScreenFranchiseMembershipNotFoundException when missing")
    void shouldThrowNotFoundOnRemoveMissingMember() {
        UUID mid = UUID.randomUUID();
        when(franchiseRepository.existsById(franchiseId)).thenReturn(true);
        when(membershipRepository.findById(mid)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> removeMemberUseCase.execute(
                new RemoveScreenFranchiseMemberCommand(franchiseId, mid)
        )).isInstanceOf(ScreenFranchiseMembershipNotFoundException.class);
    }

    @Test
    @DisplayName("Membership: get available titles excludes titles that are already members")
    void shouldExcludeExistingMembersFromAvailableTitles() {
        when(franchiseRepository.existsById(franchiseId)).thenReturn(true);

        ScreenFranchiseMembership m1 = new ScreenFranchiseMembership(UUID.randomUUID(), franchiseId, titleId1, 0);
        when(membershipRepository.findByFranchiseId(franchiseId)).thenReturn(List.of(m1));

        ScreenTitle t1 = createTestTitle(titleId1, "part-1", "Part 1");
        ScreenTitle t2 = createTestTitle(titleId2, "part-2", "Part 2");
        when(titleRepository.findAll()).thenReturn(List.of(t1, t2));

        List<ScreenTitleSummaryDTO> available = getAvailableTitlesUseCase.execute(franchiseId);

        assertThat(available).hasSize(1);
        assertThat(available.get(0).id()).isEqualTo(titleId2);
        assertThat(available.get(0).title()).isEqualTo("Part 2");
    }
}
