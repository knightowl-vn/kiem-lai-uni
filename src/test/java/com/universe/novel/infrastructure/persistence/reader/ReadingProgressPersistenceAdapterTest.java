package com.universe.novel.infrastructure.persistence.reader;

import com.universe.novel.application.exceptions.ReadingProgressConcurrencyException;
import com.universe.novel.domain.reader.UserReadingProgress;
import jakarta.persistence.Version;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;

import java.lang.reflect.Field;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ReadingProgressPersistenceAdapterTest {

    private static final UUID PROGRESS_ID =
            UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");

    private static final UUID USER_ID =
            UUID.fromString("11111111-1111-1111-1111-111111111111");

    private static final UUID CHAPTER_1_ID =
            UUID.fromString("22222222-2222-2222-2222-222222222222");

    private static final UUID CHAPTER_20_ID =
            UUID.fromString("33333333-3333-3333-3333-333333333333");

    private static final Instant CREATED_AT =
            Instant.parse("2026-08-25T08:00:00Z");

    private static final Instant UPDATED_AT =
            Instant.parse("2026-08-25T08:30:00Z");

    @Mock
    private SpringDataReadingProgressJpaRepository repository;

    private ReadingProgressPersistenceAdapter adapter;

    @BeforeEach
    void setUp() {
        adapter = new ReadingProgressPersistenceAdapter(repository);
    }

    @Test
    @DisplayName("findByUserId maps JPA entity to UserReadingProgress domain aggregate")
    void shouldMapEntityToDomainWhenFindingByUserId() {
        ReadingProgressJpaEntity entity = createEntity();
        when(repository.findByUserId(USER_ID.toString())).thenReturn(Optional.of(entity));

        Optional<UserReadingProgress> result = adapter.findByUserId(USER_ID);

        assertThat(result).isPresent();
        UserReadingProgress progress = result.get();
        assertThat(progress.getId()).isEqualTo(PROGRESS_ID);
        assertThat(progress.getUserId()).isEqualTo(USER_ID);
        assertThat(progress.getLastOpenedChapterId()).isEqualTo(CHAPTER_1_ID);
        assertThat(progress.getHighestReachedChapterNumber()).isEqualTo(1);
        assertThat(progress.getCreatedAt()).isEqualTo(CREATED_AT);
        assertThat(progress.getUpdatedAt()).isEqualTo(CREATED_AT);
    }

    @Test
    @DisplayName("findById maps JPA entity to UserReadingProgress domain aggregate")
    void shouldMapEntityToDomainWhenFindingById() {
        ReadingProgressJpaEntity entity = createEntity();
        when(repository.findById(PROGRESS_ID.toString())).thenReturn(Optional.of(entity));

        Optional<UserReadingProgress> result = adapter.findById(PROGRESS_ID);

        assertThat(result).isPresent();
        UserReadingProgress progress = result.get();
        assertThat(progress.getId()).isEqualTo(PROGRESS_ID);
        assertThat(progress.getUserId()).isEqualTo(USER_ID);
        assertThat(progress.getLastOpenedChapterId()).isEqualTo(CHAPTER_1_ID);
        assertThat(progress.getHighestReachedChapterNumber()).isEqualTo(1);
        assertThat(progress.getCreatedAt()).isEqualTo(CREATED_AT);
        assertThat(progress.getUpdatedAt()).isEqualTo(CREATED_AT);
    }

    @Test
    @DisplayName("findByUserId and findById return Optional.empty() on null input or missing record")
    void shouldReturnEmptyWhenNotFoundOrNull() {
        assertThat(adapter.findByUserId(null)).isEmpty();
        assertThat(adapter.findById(null)).isEmpty();

        when(repository.findByUserId(USER_ID.toString())).thenReturn(Optional.empty());
        assertThat(adapter.findByUserId(USER_ID)).isEmpty();

        when(repository.findById(PROGRESS_ID.toString())).thenReturn(Optional.empty());
        assertThat(adapter.findById(PROGRESS_ID)).isEmpty();
    }

    @Test
    @DisplayName("save creates and persists new JPA entity when not found in DB")
    void shouldCreateNewEntityWhenSavingNewProgress() {
        UserReadingProgress progress = UserReadingProgress.createInitial(
                PROGRESS_ID,
                USER_ID,
                CHAPTER_1_ID,
                1,
                CREATED_AT
        );

        when(repository.findById(PROGRESS_ID.toString())).thenReturn(Optional.empty());

        ReadingProgressJpaEntity savedEntity = createEntity();
        savedEntity.setPersistenceVersion(0L);
        when(repository.saveAndFlush(any(ReadingProgressJpaEntity.class))).thenReturn(savedEntity);

        UserReadingProgress result = adapter.save(progress);

        ArgumentCaptor<ReadingProgressJpaEntity> captor = ArgumentCaptor.forClass(ReadingProgressJpaEntity.class);
        verify(repository).saveAndFlush(captor.capture());

        ReadingProgressJpaEntity captured = captor.getValue();
        assertThat(captured.getId()).isEqualTo(PROGRESS_ID.toString());
        assertThat(captured.getUserId()).isEqualTo(USER_ID.toString());
        assertThat(captured.getLastOpenedChapterId()).isEqualTo(CHAPTER_1_ID.toString());
        assertThat(captured.getHighestReachedChapterNumber()).isEqualTo(1);
        assertThat(captured.getCreatedAt()).isEqualTo(CREATED_AT);
        assertThat(captured.getUpdatedAt()).isEqualTo(CREATED_AT);

        assertThat(result.getId()).isEqualTo(PROGRESS_ID);
        assertThat(result.getUserId()).isEqualTo(USER_ID);
    }

    @Test
    @DisplayName("save copies only mutable domain state into existing managed JPA entity")
    void shouldCopyMutableStateIntoExistingManagedEntity() {
        UserReadingProgress progress = UserReadingProgress.rehydrate(
                PROGRESS_ID,
                USER_ID,
                CHAPTER_20_ID,
                20,
                CREATED_AT,
                UPDATED_AT
        );

        ReadingProgressJpaEntity managedEntity = createEntity();
        managedEntity.setPersistenceVersion(3L);
        when(repository.findById(PROGRESS_ID.toString())).thenReturn(Optional.of(managedEntity));

        ReadingProgressJpaEntity updatedEntity = new ReadingProgressJpaEntity(
                PROGRESS_ID.toString(),
                USER_ID.toString(),
                CHAPTER_20_ID.toString(),
                20,
                CREATED_AT,
                UPDATED_AT
        );
        updatedEntity.setPersistenceVersion(4L);
        when(repository.saveAndFlush(managedEntity)).thenReturn(updatedEntity);

        UserReadingProgress result = adapter.save(progress);

        // Verify mutable state copied onto managed entity
        assertThat(managedEntity.getLastOpenedChapterId()).isEqualTo(CHAPTER_20_ID.toString());
        assertThat(managedEntity.getHighestReachedChapterNumber()).isEqualTo(20);
        assertThat(managedEntity.getUpdatedAt()).isEqualTo(UPDATED_AT);

        // Verify immutable/managed fields were strictly preserved
        assertThat(managedEntity.getId()).isEqualTo(PROGRESS_ID.toString());
        assertThat(managedEntity.getUserId()).isEqualTo(USER_ID.toString());
        assertThat(managedEntity.getCreatedAt()).isEqualTo(CREATED_AT);
        assertThat(managedEntity.getPersistenceVersion()).isEqualTo(3L);

        assertThat(result.getLastOpenedChapterId()).isEqualTo(CHAPTER_20_ID);
        assertThat(result.getHighestReachedChapterNumber()).isEqualTo(20);
    }

    @Test
    @DisplayName("persistenceVersion is NOT exposed on UserReadingProgress domain model")
    void shouldNotExposePersistenceVersionToDomain() {
        // Assert domain class UserReadingProgress has no persistenceVersion field or getter
        Field[] domainFields = UserReadingProgress.class.getDeclaredFields();
        for (Field field : domainFields) {
            assertThat(field.getName()).isNotEqualTo("persistenceVersion");
            assertThat(field.getName()).isNotEqualTo("aggregateVersion");
        }
    }

    @Test
    @DisplayName("ReadingProgressJpaEntity contains @Version annotation on persistenceVersion field")
    void shouldHaveVersionAnnotationOnJpaEntity() throws NoSuchFieldException {
        Field versionField = ReadingProgressJpaEntity.class.getDeclaredField("persistenceVersion");
        assertThat(versionField.isAnnotationPresent(Version.class)).isTrue();
    }

    @Test
    @DisplayName("Translates OptimisticLockingFailureException to ReadingProgressConcurrencyException")
    void shouldTranslateOptimisticLockingFailureException() {
        UserReadingProgress progress = UserReadingProgress.createInitial(
                PROGRESS_ID,
                USER_ID,
                CHAPTER_1_ID,
                1,
                CREATED_AT
        );

        when(repository.findById(PROGRESS_ID.toString())).thenReturn(Optional.empty());
        when(repository.saveAndFlush(any(ReadingProgressJpaEntity.class)))
                .thenThrow(new OptimisticLockingFailureException("Stale update"));

        assertThatThrownBy(() -> adapter.save(progress))
                .isInstanceOf(ReadingProgressConcurrencyException.class)
                .hasCauseInstanceOf(OptimisticLockingFailureException.class);
    }

    @Test
    @DisplayName("Translates DataIntegrityViolationException with uq_novel_reading_progress_user constraint to ReadingProgressConcurrencyException")
    void shouldTranslateDataIntegrityViolationExceptionWhenUserUniqueConstraintViolated() {
        UserReadingProgress progress = UserReadingProgress.createInitial(
                PROGRESS_ID,
                USER_ID,
                CHAPTER_1_ID,
                1,
                CREATED_AT
        );

        when(repository.findById(PROGRESS_ID.toString())).thenReturn(Optional.empty());
        when(repository.saveAndFlush(any(ReadingProgressJpaEntity.class)))
                .thenThrow(new DataIntegrityViolationException("Duplicate entry '11111111-1111-1111-1111-111111111111' for key 'uq_novel_reading_progress_user'"));

        assertThatThrownBy(() -> adapter.save(progress))
                .isInstanceOf(ReadingProgressConcurrencyException.class)
                .hasCauseInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("Does NOT translate unrelated DataIntegrityViolationException (e.g. FK constraint) to ReadingProgressConcurrencyException")
    void shouldNotTranslateUnrelatedDataIntegrityViolationExceptionForForeignKey() {
        UserReadingProgress progress = UserReadingProgress.createInitial(
                PROGRESS_ID,
                USER_ID,
                CHAPTER_1_ID,
                1,
                CREATED_AT
        );

        when(repository.findById(PROGRESS_ID.toString())).thenReturn(Optional.empty());
        when(repository.saveAndFlush(any(ReadingProgressJpaEntity.class)))
                .thenThrow(new DataIntegrityViolationException("Cannot add or update a child row: a foreign key constraint fails (`novel_reading_progress`, CONSTRAINT `fk_novel_reading_progress_last_chapter` FOREIGN KEY (`last_opened_chapter_id`) REFERENCES `novel_chapters` (`id`))"));

        assertThatThrownBy(() -> adapter.save(progress))
                .isInstanceOf(DataIntegrityViolationException.class)
                .isNotInstanceOf(ReadingProgressConcurrencyException.class);
    }

    @Test
    @DisplayName("Does NOT translate unrelated DataIntegrityViolationException (e.g. CHECK constraint) to ReadingProgressConcurrencyException")
    void shouldNotTranslateUnrelatedDataIntegrityViolationExceptionForCheckConstraint() {
        UserReadingProgress progress = UserReadingProgress.createInitial(
                PROGRESS_ID,
                USER_ID,
                CHAPTER_1_ID,
                1,
                CREATED_AT
        );

        when(repository.findById(PROGRESS_ID.toString())).thenReturn(Optional.empty());
        when(repository.saveAndFlush(any(ReadingProgressJpaEntity.class)))
                .thenThrow(new DataIntegrityViolationException("Check constraint 'chk_novel_reading_progress_highest_num' is violated."));

        assertThatThrownBy(() -> adapter.save(progress))
                .isInstanceOf(DataIntegrityViolationException.class)
                .isNotInstanceOf(ReadingProgressConcurrencyException.class);
    }

    private ReadingProgressJpaEntity createEntity() {
        return new ReadingProgressJpaEntity(
                PROGRESS_ID.toString(),
                USER_ID.toString(),
                CHAPTER_1_ID.toString(),
                1,
                CREATED_AT,
                CREATED_AT
        );
    }
}
