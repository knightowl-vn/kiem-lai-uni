package com.universe.screen.infrastructure.persistence;

import com.universe.screen.application.exceptions.ScreenTitleSlugAlreadyExistsException;
import com.universe.screen.domain.ScreenPresentation;
import com.universe.screen.domain.ScreenPublicationStatus;
import com.universe.screen.domain.ScreenStructure;
import com.universe.screen.domain.ScreenTitle;
import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Sort;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ScreenTitlePersistenceAdapterTest {

    @Mock
    private SpringDataScreenTitleJpaRepository repository;

    private ScreenTitlePersistenceAdapter adapter;

    private final UUID id = UUID.randomUUID();
    private final Instant now = Instant.parse("2026-10-06T10:00:00Z");

    @BeforeEach
    void setUp() {
        adapter = new ScreenTitlePersistenceAdapter(repository);
    }

    @Test
    @DisplayName("PersistenceAdapter: save persists title entity and maps to domain")
    void shouldSaveAndMapToDomain() {
        ScreenTitle title = ScreenTitle.createDraft(
                id, "title-slug", "Title Display", ScreenStructure.SERIES, ScreenPresentation.ANIMATION_3D, now
        );
        ScreenTitleJpaEntity entity = ScreenTitlePersistenceMapper.toEntity(title);

        when(repository.findById(id.toString())).thenReturn(Optional.empty());
        when(repository.saveAndFlush(any(ScreenTitleJpaEntity.class))).thenReturn(entity);

        ScreenTitle saved = adapter.save(title);

        assertThat(saved.getId()).isEqualTo(id);
        assertThat(saved.getTitle()).isEqualTo("Title Display");
        assertThat(saved.getSlug()).isEqualTo("title-slug");
        verify(repository).saveAndFlush(any(ScreenTitleJpaEntity.class));
    }

    @Test
    @DisplayName("PersistenceAdapter: save translates uq_screen_titles_slug constraint violation to ScreenTitleSlugAlreadyExistsException")
    void shouldTranslateSlugConstraintViolationToScreenTitleSlugAlreadyExistsException() {
        ScreenTitle title = ScreenTitle.createDraft(
                id, "duplicate-slug", "Title Display", ScreenStructure.SERIES, ScreenPresentation.ANIMATION_3D, now
        );
        ConstraintViolationException cve = new ConstraintViolationException(
                "Duplicate entry 'duplicate-slug' for key 'screen_titles.uq_screen_titles_slug'",
                null,
                "uq_screen_titles_slug"
        );
        DataIntegrityViolationException dive = new DataIntegrityViolationException("Constraint violation", cve);

        when(repository.findById(id.toString())).thenReturn(Optional.empty());
        when(repository.saveAndFlush(any(ScreenTitleJpaEntity.class))).thenThrow(dive);

        assertThatThrownBy(() -> adapter.save(title))
                .isInstanceOf(ScreenTitleSlugAlreadyExistsException.class)
                .hasMessageContaining("duplicate-slug")
                .hasCause(dive);
    }

    @Test
    @DisplayName("PersistenceAdapter: save translates uq_screen_titles_slug constraint violation on update to ScreenTitleSlugAlreadyExistsException")
    void shouldTranslateSlugConstraintViolationOnUpdateToScreenTitleSlugAlreadyExistsException() {
        ScreenTitle title = ScreenTitle.createDraft(
                id, "duplicate-slug", "Title Display", ScreenStructure.SERIES, ScreenPresentation.ANIMATION_3D, now
        );
        ScreenTitleJpaEntity existingEntity = ScreenTitlePersistenceMapper.toEntity(title);

        ConstraintViolationException cve = new ConstraintViolationException(
                "Duplicate entry 'duplicate-slug' for key 'screen_titles.uq_screen_titles_slug'",
                null,
                "uq_screen_titles_slug"
        );
        DataIntegrityViolationException dive = new DataIntegrityViolationException("Constraint violation", cve);

        when(repository.findById(id.toString())).thenReturn(Optional.of(existingEntity));
        when(repository.saveAndFlush(existingEntity)).thenThrow(dive);

        assertThatThrownBy(() -> adapter.save(title))
                .isInstanceOf(ScreenTitleSlugAlreadyExistsException.class)
                .hasMessageContaining("duplicate-slug")
                .hasCause(dive);
    }

    @Test
    @DisplayName("PersistenceAdapter: save rethrows unrelated DataIntegrityViolationException")
    void shouldRethrowUnrelatedDataIntegrityViolationException() {
        ScreenTitle title = ScreenTitle.createDraft(
                id, "some-slug", "Title Display", ScreenStructure.SERIES, ScreenPresentation.ANIMATION_3D, now
        );
        DataIntegrityViolationException dive = new DataIntegrityViolationException("Unrelated DB failure");

        when(repository.findById(id.toString())).thenReturn(Optional.empty());
        when(repository.saveAndFlush(any(ScreenTitleJpaEntity.class))).thenThrow(dive);

        assertThatThrownBy(() -> adapter.save(title))
                .isInstanceOf(DataIntegrityViolationException.class)
                .isSameAs(dive);
    }

    @Test
    @DisplayName("PersistenceAdapter: save does not translate different constraint whose name merely contains 'slug'")
    void shouldNotTranslateDifferentConstraintContainingSlug() {
        ScreenTitle title = ScreenTitle.createDraft(
                id, "some-slug", "Title Display", ScreenStructure.SERIES, ScreenPresentation.ANIMATION_3D, now
        );
        ConstraintViolationException cve = new ConstraintViolationException(
                "Check constraint failed: chk_screen_titles_slug_length",
                null,
                "chk_screen_titles_slug_length"
        );
        DataIntegrityViolationException dive = new DataIntegrityViolationException("Constraint violation", cve);

        when(repository.findById(id.toString())).thenReturn(Optional.empty());
        when(repository.saveAndFlush(any(ScreenTitleJpaEntity.class))).thenThrow(dive);

        assertThatThrownBy(() -> adapter.save(title))
                .isInstanceOf(DataIntegrityViolationException.class)
                .isSameAs(dive);
    }

    @Test
    @DisplayName("PersistenceAdapter: findAll returns titles sorted and mapped to domain")
    void shouldFindAllTitles() {
        ScreenTitle title = ScreenTitle.createDraft(
                id, "title-slug", "Title Display", ScreenStructure.SERIES, ScreenPresentation.ANIMATION_3D, now
        );
        ScreenTitleJpaEntity entity = ScreenTitlePersistenceMapper.toEntity(title);

        when(repository.findAll(any(Sort.class))).thenReturn(List.of(entity));

        List<ScreenTitle> titles = adapter.findAll();

        assertThat(titles).hasSize(1);
        assertThat(titles.get(0).getId()).isEqualTo(id);
        assertThat(titles.get(0).getTitle()).isEqualTo("Title Display");
    }

    @Test
    @DisplayName("PersistenceAdapter: findById retrieves and maps title")
    void shouldFindById() {
        ScreenTitle title = ScreenTitle.createDraft(
                id, "title-slug", "Title Display", ScreenStructure.SERIES, ScreenPresentation.ANIMATION_3D, now
        );
        ScreenTitleJpaEntity entity = ScreenTitlePersistenceMapper.toEntity(title);

        when(repository.findById(id.toString())).thenReturn(Optional.of(entity));

        Optional<ScreenTitle> found = adapter.findById(id);

        assertThat(found).isPresent();
        assertThat(found.get().getId()).isEqualTo(id);
    }

    @Test
    @DisplayName("PersistenceAdapter: findBySlug normalizes slug and retrieves title")
    void shouldFindBySlug() {
        ScreenTitle title = ScreenTitle.createDraft(
                id, "title-slug", "Title Display", ScreenStructure.SERIES, ScreenPresentation.ANIMATION_3D, now
        );
        ScreenTitleJpaEntity entity = ScreenTitlePersistenceMapper.toEntity(title);

        when(repository.findBySlug("title-slug")).thenReturn(Optional.of(entity));

        Optional<ScreenTitle> found = adapter.findBySlug("  TITLE-SLUG  ");

        assertThat(found).isPresent();
        assertThat(found.get().getSlug()).isEqualTo("title-slug");
    }

    @Test
    @DisplayName("PersistenceAdapter: findPublishedById filters by PUBLISHED status")
    void shouldFindPublishedById() {
        ScreenTitle title = ScreenTitle.createDraft(
                id, "title-slug", "Title Display", ScreenStructure.SERIES, ScreenPresentation.ANIMATION_3D, now
        );
        title.publish(now.plusSeconds(100));
        ScreenTitleJpaEntity entity = ScreenTitlePersistenceMapper.toEntity(title);

        when(repository.findByIdAndStatus(id.toString(), "PUBLISHED")).thenReturn(Optional.of(entity));

        Optional<ScreenTitle> found = adapter.findPublishedById(id);

        assertThat(found).isPresent();
        assertThat(found.get().getStatus()).isEqualTo(ScreenPublicationStatus.PUBLISHED);
    }

    @Test
    @DisplayName("PersistenceAdapter: findPublishedBySlug filters by PUBLISHED status")
    void shouldFindPublishedBySlug() {
        ScreenTitle title = ScreenTitle.createDraft(
                id, "title-slug", "Title Display", ScreenStructure.SERIES, ScreenPresentation.ANIMATION_3D, now
        );
        title.publish(now.plusSeconds(100));
        ScreenTitleJpaEntity entity = ScreenTitlePersistenceMapper.toEntity(title);

        when(repository.findBySlugAndStatus("title-slug", "PUBLISHED")).thenReturn(Optional.of(entity));

        Optional<ScreenTitle> found = adapter.findPublishedBySlug("  TITLE-SLUG  ");

        assertThat(found).isPresent();
        assertThat(found.get().getStatus()).isEqualTo(ScreenPublicationStatus.PUBLISHED);
    }

    @Test
    @DisplayName("PersistenceAdapter: existsById and existsBySlug delegate properly")
    void shouldCheckExistence() {
        when(repository.existsById(id.toString())).thenReturn(true);
        when(repository.existsBySlug("title-slug")).thenReturn(true);

        assertThat(adapter.existsById(id)).isTrue();
        assertThat(adapter.existsBySlug("title-slug")).isTrue();
    }
}
