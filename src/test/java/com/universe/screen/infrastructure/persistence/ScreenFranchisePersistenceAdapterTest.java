package com.universe.screen.infrastructure.persistence;

import com.universe.screen.domain.ScreenFranchise;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.universe.screen.application.exceptions.ScreenFranchiseSlugAlreadyExistsException;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ScreenFranchisePersistenceAdapterTest {

    @Mock
    private SpringDataScreenFranchiseJpaRepository repository;

    private ScreenFranchisePersistenceAdapter adapter;

    private final UUID id = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        adapter = new ScreenFranchisePersistenceAdapter(repository);
    }

    @Test
    @DisplayName("PersistenceAdapter: save persists franchise entity and maps to domain")
    void shouldSaveAndMapToDomain() {
        ScreenFranchise franchise = new ScreenFranchise(id, "kiem-lai", "Kiếm Lai");
        ScreenFranchiseJpaEntity entity = ScreenFranchisePersistenceMapper.toEntity(franchise);

        when(repository.findById(id.toString())).thenReturn(Optional.empty());
        when(repository.saveAndFlush(any(ScreenFranchiseJpaEntity.class))).thenReturn(entity);

        ScreenFranchise saved = adapter.save(franchise);

        assertThat(saved.getId()).isEqualTo(id);
        assertThat(saved.getName()).isEqualTo("Kiếm Lai");
        assertThat(saved.getSlug()).isEqualTo("kiem-lai");
        verify(repository).saveAndFlush(any(ScreenFranchiseJpaEntity.class));
    }

    @Test
    @DisplayName("PersistenceAdapter: save translates slug constraint violation to ScreenFranchiseSlugAlreadyExistsException")
    void shouldTranslateSlugConstraintViolationToScreenFranchiseSlugAlreadyExistsException() {
        ScreenFranchise franchise = new ScreenFranchise(id, "kiem-lai", "Kiếm Lai");
        ConstraintViolationException cve = new ConstraintViolationException(
                "Duplicate entry 'kiem-lai' for key 'screen_franchises.uq_screen_franchises_slug'",
                null,
                "uq_screen_franchises_slug"
        );
        DataIntegrityViolationException dive = new DataIntegrityViolationException("Constraint violation", cve);

        when(repository.findById(id.toString())).thenReturn(Optional.empty());
        when(repository.saveAndFlush(any(ScreenFranchiseJpaEntity.class))).thenThrow(dive);

        assertThatThrownBy(() -> adapter.save(franchise))
                .isInstanceOf(ScreenFranchiseSlugAlreadyExistsException.class)
                .hasMessageContaining("kiem-lai")
                .hasCause(dive);
    }

    @Test
    @DisplayName("PersistenceAdapter: save rethrows unrelated DataIntegrityViolationException")
    void shouldRethrowUnrelatedDataIntegrityViolationException() {
        ScreenFranchise franchise = new ScreenFranchise(id, "kiem-lai", "Kiếm Lai");
        DataIntegrityViolationException dive = new DataIntegrityViolationException("Unrelated DB error");

        when(repository.findById(id.toString())).thenReturn(Optional.empty());
        when(repository.saveAndFlush(any(ScreenFranchiseJpaEntity.class))).thenThrow(dive);

        assertThatThrownBy(() -> adapter.save(franchise))
                .isInstanceOf(DataIntegrityViolationException.class)
                .isSameAs(dive);
    }

    @Test
    @DisplayName("PersistenceAdapter: save does not translate different constraint whose name merely contains 'slug'")
    void shouldNotTranslateDifferentConstraintContainingSlug() {
        ScreenFranchise franchise = new ScreenFranchise(id, "kiem-lai", "Kiếm Lai");
        ConstraintViolationException cve = new ConstraintViolationException(
                "Check constraint failed: chk_screen_franchises_slug_length",
                null,
                "chk_screen_franchises_slug_length"
        );
        DataIntegrityViolationException dive = new DataIntegrityViolationException("Constraint violation", cve);

        when(repository.findById(id.toString())).thenReturn(Optional.empty());
        when(repository.saveAndFlush(any(ScreenFranchiseJpaEntity.class))).thenThrow(dive);

        assertThatThrownBy(() -> adapter.save(franchise))
                .isInstanceOf(DataIntegrityViolationException.class)
                .isSameAs(dive);
    }

    @Test
    @DisplayName("PersistenceAdapter: findAll returns all franchises mapped to domain")
    void shouldFindAll() {
        ScreenFranchise franchise = new ScreenFranchise(id, "kiem-lai", "Kiếm Lai");
        ScreenFranchiseJpaEntity entity = ScreenFranchisePersistenceMapper.toEntity(franchise);

        when(repository.findAllOrderByNameAsc()).thenReturn(List.of(entity));

        List<ScreenFranchise> result = adapter.findAll();

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getName()).isEqualTo("Kiếm Lai");
    }

    @Test
    @DisplayName("PersistenceAdapter: findById retrieves and maps franchise")
    void shouldFindById() {
        ScreenFranchise franchise = new ScreenFranchise(id, "kiem-lai", "Kiếm Lai");
        ScreenFranchiseJpaEntity entity = ScreenFranchisePersistenceMapper.toEntity(franchise);

        when(repository.findById(id.toString())).thenReturn(Optional.of(entity));

        Optional<ScreenFranchise> found = adapter.findById(id);

        assertThat(found).isPresent();
        assertThat(found.get().getId()).isEqualTo(id);
    }

    @Test
    @DisplayName("PersistenceAdapter: findBySlug normalizes slug and retrieves franchise")
    void shouldFindBySlug() {
        ScreenFranchise franchise = new ScreenFranchise(id, "kiem-lai", "Kiếm Lai");
        ScreenFranchiseJpaEntity entity = ScreenFranchisePersistenceMapper.toEntity(franchise);

        when(repository.findBySlug("kiem-lai")).thenReturn(Optional.of(entity));

        Optional<ScreenFranchise> found = adapter.findBySlug("  KIEM-LAI  ");

        assertThat(found).isPresent();
        assertThat(found.get().getSlug()).isEqualTo("kiem-lai");
    }

    @Test
    @DisplayName("PersistenceAdapter: existsById and existsBySlug delegate to repository")
    void shouldCheckExistence() {
        when(repository.existsById(id.toString())).thenReturn(true);
        when(repository.existsBySlug("kiem-lai")).thenReturn(true);

        assertThat(adapter.existsById(id)).isTrue();
        assertThat(adapter.existsBySlug("kiem-lai")).isTrue();
    }
}
