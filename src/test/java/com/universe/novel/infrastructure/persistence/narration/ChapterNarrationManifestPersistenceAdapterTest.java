package com.universe.novel.infrastructure.persistence.narration;

import com.universe.novel.domain.narration.ChapterNarrationManifest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("ChapterNarrationManifestPersistenceAdapter Unit Tests")
class ChapterNarrationManifestPersistenceAdapterTest {

    @Mock
    private SpringDataChapterNarrationManifestJpaRepository repository;

    private ChapterNarrationManifestPersistenceAdapter adapter;

    private static final String VALID_HASH = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";

    @BeforeEach
    void setUp() {
        adapter = new ChapterNarrationManifestPersistenceAdapter(repository);
    }

    @Test
    @DisplayName("findByChapterId returns mapped domain object when entity exists")
    void shouldFindAndMapDomainObject() {
        UUID chapterId = UUID.randomUUID();
        Instant now = Instant.now();

        ChapterNarrationManifestJpaEntity entity = new ChapterNarrationManifestJpaEntity(
                chapterId.toString(),
                2L,
                VALID_HASH,
                now,
                now
        );

        when(repository.findById(chapterId.toString())).thenReturn(Optional.of(entity));

        Optional<ChapterNarrationManifest> result = adapter.findByChapterId(chapterId);

        assertThat(result).isPresent();
        ChapterNarrationManifest manifest = result.get();
        assertThat(manifest.getChapterId()).isEqualTo(chapterId);
        assertThat(manifest.getSourceContentVersion()).isEqualTo(2L);
        assertThat(manifest.getManifestHash()).isEqualTo(VALID_HASH);
        assertThat(manifest.getCreatedAt()).isEqualTo(now);
        assertThat(manifest.getUpdatedAt()).isEqualTo(now);
    }

    @Test
    @DisplayName("findByChapterId returns empty optional when entity not found")
    void shouldReturnEmptyWhenNotFound() {
        UUID chapterId = UUID.randomUUID();
        when(repository.findById(chapterId.toString())).thenReturn(Optional.empty());

        assertThat(adapter.findByChapterId(chapterId)).isEmpty();
    }

    @Test
    @DisplayName("findByChapterId returns empty optional without calling repository when chapterId is null")
    void shouldReturnEmptyWhenNullChapterId() {
        assertThat(adapter.findByChapterId(null)).isEmpty();
        verifyNoInteractions(repository);
    }

    @Test
    @DisplayName("save maps domain to JPA entity and calls saveAndFlush")
    void shouldSaveAndMapEntity() {
        UUID chapterId = UUID.randomUUID();
        Instant now = Instant.now();

        ChapterNarrationManifest manifest = ChapterNarrationManifest.create(
                chapterId,
                1L,
                VALID_HASH,
                now
        );

        ChapterNarrationManifestJpaEntity savedEntity = new ChapterNarrationManifestJpaEntity(
                chapterId.toString(),
                1L,
                VALID_HASH,
                now,
                now
        );

        when(repository.saveAndFlush(any(ChapterNarrationManifestJpaEntity.class))).thenReturn(savedEntity);

        ChapterNarrationManifest result = adapter.save(manifest);

        ArgumentCaptor<ChapterNarrationManifestJpaEntity> captor = ArgumentCaptor.forClass(ChapterNarrationManifestJpaEntity.class);
        verify(repository).saveAndFlush(captor.capture());

        ChapterNarrationManifestJpaEntity passed = captor.getValue();
        assertThat(passed.getChapterId()).isEqualTo(chapterId.toString());
        assertThat(passed.getSourceContentVersion()).isEqualTo(1L);
        assertThat(passed.getManifestHash()).isEqualTo(VALID_HASH);
        assertThat(passed.getCreatedAt()).isEqualTo(now);
        assertThat(passed.getUpdatedAt()).isEqualTo(now);

        assertThat(result.getChapterId()).isEqualTo(chapterId);
        assertThat(result.getSourceContentVersion()).isEqualTo(1L);
    }

    @Test
    @DisplayName("save rejects null manifest")
    void shouldRejectNullSave() {
        assertThatThrownBy(() -> adapter.save(null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
