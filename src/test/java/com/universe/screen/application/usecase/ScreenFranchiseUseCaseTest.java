package com.universe.screen.application.usecase;

import com.universe.screen.application.commands.CreateScreenFranchiseCommand;
import com.universe.screen.application.commands.UpdateScreenFranchiseCommand;
import com.universe.screen.application.dto.ScreenFranchiseDTO;
import com.universe.screen.application.exceptions.ScreenFranchiseNotFoundException;
import com.universe.screen.application.exceptions.ScreenFranchiseSlugAlreadyExistsException;
import com.universe.screen.application.ports.ScreenFranchiseMembershipRepositoryPort;
import com.universe.screen.application.ports.ScreenFranchiseRepositoryPort;
import com.universe.screen.domain.ScreenFranchise;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

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
class ScreenFranchiseUseCaseTest {

    @Mock
    private ScreenFranchiseRepositoryPort franchiseRepository;

    @Mock
    private ScreenFranchiseMembershipRepositoryPort membershipRepository;

    private GetScreenFranchiseListUseCase getListUseCase;
    private GetScreenFranchiseDetailUseCase getDetailUseCase;
    private CreateScreenFranchiseUseCase createUseCase;
    private UpdateScreenFranchiseUseCase updateUseCase;

    @BeforeEach
    void setUp() {
        getListUseCase = new GetScreenFranchiseListUseCase(franchiseRepository, membershipRepository);
        getDetailUseCase = new GetScreenFranchiseDetailUseCase(franchiseRepository, membershipRepository);
        createUseCase = new CreateScreenFranchiseUseCase(franchiseRepository);
        updateUseCase = new UpdateScreenFranchiseUseCase(franchiseRepository, membershipRepository);
    }

    @Test
    @DisplayName("Franchise: list returns all franchises with member count")
    void shouldListFranchisesWithMemberCount() {
        UUID f1Id = UUID.randomUUID();
        UUID f2Id = UUID.randomUUID();
        ScreenFranchise f1 = new ScreenFranchise(f1Id, "kiem-lai-1", "Kiếm Lai 1");
        ScreenFranchise f2 = new ScreenFranchise(f2Id, "kiem-lai-2", "Kiếm Lai 2");

        when(franchiseRepository.findAll()).thenReturn(List.of(f1, f2));
        when(membershipRepository.findByFranchiseId(f1Id)).thenReturn(Collections.emptyList());
        when(membershipRepository.findByFranchiseId(f2Id)).thenReturn(List.of(
                org.mockito.Mockito.mock(com.universe.screen.domain.ScreenFranchiseMembership.class)
        ));

        List<ScreenFranchiseDTO> result = getListUseCase.execute();

        assertThat(result).hasSize(2);
        assertThat(result.get(0).name()).isEqualTo("Kiếm Lai 1");
        assertThat(result.get(0).memberCount()).isEqualTo(0);
        assertThat(result.get(1).name()).isEqualTo("Kiếm Lai 2");
        assertThat(result.get(1).memberCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("Franchise: get detail returns franchise when found")
    void shouldReturnFranchiseDetailWhenFound() {
        UUID id = UUID.randomUUID();
        ScreenFranchise franchise = new ScreenFranchise(id, "kiem-lai", "Kiếm Lai");

        when(franchiseRepository.findById(id)).thenReturn(Optional.of(franchise));
        when(membershipRepository.findByFranchiseId(id)).thenReturn(Collections.emptyList());

        ScreenFranchiseDTO result = getDetailUseCase.execute(id);

        assertThat(result.id()).isEqualTo(id);
        assertThat(result.name()).isEqualTo("Kiếm Lai");
        assertThat(result.slug()).isEqualTo("kiem-lai");
        assertThat(result.memberCount()).isEqualTo(0);
    }

    @Test
    @DisplayName("Franchise: get detail throws ScreenFranchiseNotFoundException when not found")
    void shouldThrowNotFoundWhenFranchiseMissing() {
        UUID id = UUID.randomUUID();
        when(franchiseRepository.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> getDetailUseCase.execute(id))
                .isInstanceOf(ScreenFranchiseNotFoundException.class);
    }

    @Test
    @DisplayName("Franchise: create successfully saves franchise")
    void shouldCreateFranchiseSuccessfully() {
        when(franchiseRepository.existsBySlug("kiem-lai")).thenReturn(false);
        when(franchiseRepository.save(any(ScreenFranchise.class))).thenAnswer(inv -> inv.getArgument(0));

        ScreenFranchiseDTO dto = createUseCase.execute(new CreateScreenFranchiseCommand("Kiếm Lai", "kiem-lai"));

        assertThat(dto.name()).isEqualTo("Kiếm Lai");
        assertThat(dto.slug()).isEqualTo("kiem-lai");
        verify(franchiseRepository).save(any(ScreenFranchise.class));
    }

    @Test
    @DisplayName("Franchise: create throws ScreenFranchiseSlugAlreadyExistsException on duplicate slug pre-check and does not call save")
    void shouldThrowExceptionWhenCreatingWithDuplicateSlug() {
        when(franchiseRepository.existsBySlug("kiem-lai")).thenReturn(true);

        assertThatThrownBy(() -> createUseCase.execute(new CreateScreenFranchiseCommand("Kiếm Lai", "kiem-lai")))
                .isInstanceOf(ScreenFranchiseSlugAlreadyExistsException.class);
        verify(franchiseRepository, never()).save(any());
    }

    @Test
    @DisplayName("Franchise: create throws ScreenFranchiseSlugAlreadyExistsException when persistence reports slug conflict race")
    void shouldThrowSlugAlreadyExistsExceptionWhenCreatePersistenceRaceOccurs() {
        when(franchiseRepository.existsBySlug("kiem-lai")).thenReturn(false);
        when(franchiseRepository.save(any(ScreenFranchise.class)))
                .thenThrow(new ScreenFranchiseSlugAlreadyExistsException("kiem-lai", new RuntimeException("DB duplicate key uq_screen_franchises_slug")));

        assertThatThrownBy(() -> createUseCase.execute(new CreateScreenFranchiseCommand("Kiếm Lai", "kiem-lai")))
                .isInstanceOf(ScreenFranchiseSlugAlreadyExistsException.class)
                .hasMessageContaining("kiem-lai");
    }

    @Test
    @DisplayName("Franchise: create rejects blank name or slug")
    void shouldRejectBlankNameOrSlugOnCreate() {
        assertThatThrownBy(() -> createUseCase.execute(new CreateScreenFranchiseCommand("", "kiem-lai")))
                .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> createUseCase.execute(new CreateScreenFranchiseCommand("Kiếm Lai", "")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("Franchise: update successfully modifies name and slug")
    void shouldUpdateFranchiseSuccessfully() {
        UUID id = UUID.randomUUID();
        ScreenFranchise franchise = new ScreenFranchise(id, "kiem-lai-old", "Kiếm Lai Cũ");

        when(franchiseRepository.findById(id)).thenReturn(Optional.of(franchise));
        when(franchiseRepository.findBySlug("kiem-lai-new")).thenReturn(Optional.empty());
        when(franchiseRepository.save(any(ScreenFranchise.class))).thenAnswer(inv -> inv.getArgument(0));
        when(membershipRepository.findByFranchiseId(id)).thenReturn(Collections.emptyList());

        ScreenFranchiseDTO updated = updateUseCase.execute(
                new UpdateScreenFranchiseCommand(id, "Kiếm Lai Mới", "kiem-lai-new")
        );

        assertThat(updated.name()).isEqualTo("Kiếm Lai Mới");
        assertThat(updated.slug()).isEqualTo("kiem-lai-new");
        verify(franchiseRepository).save(franchise);
    }

    @Test
    @DisplayName("Franchise: update throws ScreenFranchiseSlugAlreadyExistsException if another franchise has slug on pre-check and does not call save")
    void shouldThrowExceptionWhenUpdatingToExistingSlugOfAnotherFranchise() {
        UUID id = UUID.randomUUID();
        UUID otherId = UUID.randomUUID();
        ScreenFranchise franchise = new ScreenFranchise(id, "slug-1", "Franchise 1");
        ScreenFranchise other = new ScreenFranchise(otherId, "slug-2", "Franchise 2");

        when(franchiseRepository.findById(id)).thenReturn(Optional.of(franchise));
        when(franchiseRepository.findBySlug("slug-2")).thenReturn(Optional.of(other));

        assertThatThrownBy(() -> updateUseCase.execute(
                new UpdateScreenFranchiseCommand(id, "Franchise 1 Updated", "slug-2")
        )).isInstanceOf(ScreenFranchiseSlugAlreadyExistsException.class);
        verify(franchiseRepository, never()).save(any());
    }

    @Test
    @DisplayName("Franchise: update throws ScreenFranchiseSlugAlreadyExistsException when persistence reports slug conflict race")
    void shouldThrowSlugAlreadyExistsExceptionWhenUpdatePersistenceRaceOccurs() {
        UUID id = UUID.randomUUID();
        ScreenFranchise franchise = new ScreenFranchise(id, "slug-1", "Franchise 1");

        when(franchiseRepository.findById(id)).thenReturn(Optional.of(franchise));
        when(franchiseRepository.findBySlug("slug-2")).thenReturn(Optional.empty());
        when(franchiseRepository.save(any(ScreenFranchise.class)))
                .thenThrow(new ScreenFranchiseSlugAlreadyExistsException("slug-2", new RuntimeException("DB duplicate key uq_screen_franchises_slug")));

        assertThatThrownBy(() -> updateUseCase.execute(
                new UpdateScreenFranchiseCommand(id, "Franchise 1 Updated", "slug-2")
        )).isInstanceOf(ScreenFranchiseSlugAlreadyExistsException.class)
                .hasMessageContaining("slug-2");
    }

    @Test
    @DisplayName("Franchise: update allows keeping existing slug for same franchise")
    void shouldAllowKeepingSameSlugOnUpdate() {
        UUID id = UUID.randomUUID();
        ScreenFranchise franchise = new ScreenFranchise(id, "slug-1", "Franchise 1");

        when(franchiseRepository.findById(id)).thenReturn(Optional.of(franchise));
        when(franchiseRepository.findBySlug("slug-1")).thenReturn(Optional.of(franchise));
        when(franchiseRepository.save(any(ScreenFranchise.class))).thenAnswer(inv -> inv.getArgument(0));
        when(membershipRepository.findByFranchiseId(id)).thenReturn(Collections.emptyList());

        ScreenFranchiseDTO updated = updateUseCase.execute(
                new UpdateScreenFranchiseCommand(id, "Franchise 1 Renamed", "slug-1")
        );

        assertThat(updated.name()).isEqualTo("Franchise 1 Renamed");
        assertThat(updated.slug()).isEqualTo("slug-1");
    }

    @Test
    @DisplayName("Franchise: update throws ScreenFranchiseNotFoundException when franchise missing")
    void shouldThrowNotFoundOnUpdateMissingFranchise() {
        UUID id = UUID.randomUUID();
        when(franchiseRepository.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> updateUseCase.execute(
                new UpdateScreenFranchiseCommand(id, "Franchise", "slug")
        )).isInstanceOf(ScreenFranchiseNotFoundException.class);
    }
}
