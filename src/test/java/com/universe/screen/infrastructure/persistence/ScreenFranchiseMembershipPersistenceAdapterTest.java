package com.universe.screen.infrastructure.persistence;

import com.universe.screen.domain.ScreenFranchiseMembership;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.universe.screen.application.exceptions.DuplicateScreenFranchiseMembershipException;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ScreenFranchiseMembershipPersistenceAdapterTest {

    @Mock
    private SpringDataScreenFranchiseMembershipJpaRepository repository;

    private ScreenFranchiseMembershipPersistenceAdapter adapter;

    private final UUID id = UUID.randomUUID();
    private final UUID franchiseId = UUID.randomUUID();
    private final UUID titleId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        adapter = new ScreenFranchiseMembershipPersistenceAdapter(repository);
    }

    @Test
    @DisplayName("PersistenceAdapter: save persists entity and maps to domain")
    void shouldSaveAndMapToDomain() {
        ScreenFranchiseMembership membership = new ScreenFranchiseMembership(id, franchiseId, titleId, 0);
        ScreenFranchiseMembershipJpaEntity entity = ScreenFranchiseMembershipPersistenceMapper.toEntity(membership);

        when(repository.findById(id.toString())).thenReturn(Optional.empty());
        when(repository.saveAndFlush(any(ScreenFranchiseMembershipJpaEntity.class))).thenReturn(entity);

        ScreenFranchiseMembership saved = adapter.save(membership);

        assertThat(saved.getId()).isEqualTo(id);
        assertThat(saved.getFranchiseId()).isEqualTo(franchiseId);
        assertThat(saved.getTitleId()).isEqualTo(titleId);
        assertThat(saved.getDisplayOrder()).isEqualTo(0);
        verify(repository).saveAndFlush(any(ScreenFranchiseMembershipJpaEntity.class));
    }

    @Test
    @DisplayName("PersistenceAdapter: save translates duplicate constraint violation to DuplicateScreenFranchiseMembershipException")
    void shouldTranslateDuplicateConstraintViolationToDuplicateScreenFranchiseMembershipException() {
        ScreenFranchiseMembership membership = new ScreenFranchiseMembership(id, franchiseId, titleId, 0);
        ConstraintViolationException cve = new ConstraintViolationException(
                "Duplicate entry for key 'uq_screen_franchise_memberships_franchise_title'",
                null,
                "uq_screen_franchise_memberships_franchise_title"
        );
        DataIntegrityViolationException dive = new DataIntegrityViolationException("Constraint violation", cve);

        when(repository.findById(id.toString())).thenReturn(Optional.empty());
        when(repository.saveAndFlush(any(ScreenFranchiseMembershipJpaEntity.class))).thenThrow(dive);

        assertThatThrownBy(() -> adapter.save(membership))
                .isInstanceOf(DuplicateScreenFranchiseMembershipException.class)
                .hasCause(dive);
    }

    @Test
    @DisplayName("PersistenceAdapter: save rethrows unrelated DataIntegrityViolationException")
    void shouldRethrowUnrelatedDataIntegrityViolationException() {
        ScreenFranchiseMembership membership = new ScreenFranchiseMembership(id, franchiseId, titleId, 0);
        DataIntegrityViolationException dive = new DataIntegrityViolationException("Unrelated DB error");

        when(repository.findById(id.toString())).thenReturn(Optional.empty());
        when(repository.saveAndFlush(any(ScreenFranchiseMembershipJpaEntity.class))).thenThrow(dive);

        assertThatThrownBy(() -> adapter.save(membership))
                .isInstanceOf(DataIntegrityViolationException.class)
                .isSameAs(dive);
    }

    @Test
    @DisplayName("PersistenceAdapter: save does not translate unrelated FK-style constraint containing franchise/title words")
    void shouldNotTranslateUnrelatedForeignKeyConstraintsContainingKeywords() {
        ScreenFranchiseMembership membership = new ScreenFranchiseMembership(id, franchiseId, titleId, 0);
        ConstraintViolationException cveFranchiseFk = new ConstraintViolationException(
                "Cannot add or update a child row: a foreign key constraint fails (`fk_screen_franchise_memberships_franchise`)",
                null,
                "fk_screen_franchise_memberships_franchise"
        );
        DataIntegrityViolationException dive = new DataIntegrityViolationException("FK constraint violation", cveFranchiseFk);

        when(repository.findById(id.toString())).thenReturn(Optional.empty());
        when(repository.saveAndFlush(any(ScreenFranchiseMembershipJpaEntity.class))).thenThrow(dive);

        assertThatThrownBy(() -> adapter.save(membership))
                .isInstanceOf(DataIntegrityViolationException.class)
                .isSameAs(dive);

        ConstraintViolationException cveTitleFk = new ConstraintViolationException(
                "Cannot add or update a child row: a foreign key constraint fails (`fk_screen_franchise_memberships_title`)",
                null,
                "fk_screen_franchise_memberships_title"
        );
        DataIntegrityViolationException diveTitle = new DataIntegrityViolationException("FK constraint violation", cveTitleFk);
        when(repository.saveAndFlush(any(ScreenFranchiseMembershipJpaEntity.class))).thenThrow(diveTitle);

        assertThatThrownBy(() -> adapter.save(membership))
                .isInstanceOf(DataIntegrityViolationException.class)
                .isSameAs(diveTitle);
    }

    @Test
    @DisplayName("PersistenceAdapter: findById retrieves and maps entity")
    void shouldFindById() {
        ScreenFranchiseMembership membership = new ScreenFranchiseMembership(id, franchiseId, titleId, 2);
        ScreenFranchiseMembershipJpaEntity entity = ScreenFranchiseMembershipPersistenceMapper.toEntity(membership);

        when(repository.findById(id.toString())).thenReturn(Optional.of(entity));

        Optional<ScreenFranchiseMembership> found = adapter.findById(id);

        assertThat(found).isPresent();
        assertThat(found.get().getId()).isEqualTo(id);
        assertThat(found.get().getDisplayOrder()).isEqualTo(2);
    }

    @Test
    @DisplayName("PersistenceAdapter: findByFranchiseId retrieves ordered list")
    void shouldFindByFranchiseId() {
        ScreenFranchiseMembership membership = new ScreenFranchiseMembership(id, franchiseId, titleId, 0);
        ScreenFranchiseMembershipJpaEntity entity = ScreenFranchiseMembershipPersistenceMapper.toEntity(membership);

        when(repository.findByFranchiseId(franchiseId.toString())).thenReturn(List.of(entity));

        List<ScreenFranchiseMembership> list = adapter.findByFranchiseId(franchiseId);

        assertThat(list).hasSize(1);
        assertThat(list.get(0).getId()).isEqualTo(id);
    }

    @Test
    @DisplayName("PersistenceAdapter: delete calls repository deleteById")
    void shouldDeleteMembership() {
        ScreenFranchiseMembership membership = new ScreenFranchiseMembership(id, franchiseId, titleId, 0);

        adapter.delete(membership);

        verify(repository).deleteById(id.toString());
    }

    @Test
    @DisplayName("PersistenceAdapter: existsByFranchiseIdAndTitleId delegates to repository")
    void shouldCheckExistsByFranchiseAndTitle() {
        when(repository.existsByFranchiseIdAndTitleId(franchiseId.toString(), titleId.toString())).thenReturn(true);

        boolean exists = adapter.existsByFranchiseIdAndTitleId(franchiseId, titleId);

        assertThat(exists).isTrue();
    }
}
