package com.universe.novel.infrastructure.persistence.narration;

import com.universe.novel.domain.narration.ChapterNarrationAudio;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

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
@DisplayName("ChapterNarrationAudioPersistenceAdapter Unit Tests")
class ChapterNarrationAudioPersistenceAdapterTest {

    @Mock
    private SpringDataChapterNarrationAudioJpaRepository repository;

    private ChapterNarrationAudioPersistenceAdapter adapter;

    private static final UUID ID = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final UUID SEGMENT_ID = UUID.fromString("20000000-0000-0000-0000-000000000002");
    private static final UUID VOICE_ID = UUID.fromString("30000000-0000-0000-0000-000000000003");
    private static final UUID MEDIA_ASSET_ID = UUID.fromString("40000000-0000-0000-0000-000000000004");
    private static final Instant NOW = Instant.parse("2026-09-05T12:00:00Z");

    @BeforeEach
    void setUp() {
        adapter = new ChapterNarrationAudioPersistenceAdapter(repository);
    }

    @Test
    @DisplayName("Should find audio assignment by ID")
    void shouldFindById() {
        ChapterNarrationAudioJpaEntity entity = new ChapterNarrationAudioJpaEntity(
                ID.toString(), SEGMENT_ID.toString(), VOICE_ID.toString(), MEDIA_ASSET_ID.toString(),
                1L, NOW, NOW
        );
        when(repository.findById(ID.toString())).thenReturn(Optional.of(entity));

        Optional<ChapterNarrationAudio> result = adapter.findById(ID);

        assertThat(result).isPresent();
        assertThat(result.get().getId()).isEqualTo(ID);
        assertThat(result.get().getSegmentId()).isEqualTo(SEGMENT_ID);
        assertThat(result.get().getManagedVoiceId()).isEqualTo(VOICE_ID);
        assertThat(result.get().getMediaAssetId()).isEqualTo(MEDIA_ASSET_ID);
        assertThat(result.get().getGeneratedSynthesisRevision()).isEqualTo(1L);
    }

    @Test
    @DisplayName("Should return empty optional when findById receives null")
    void shouldReturnEmptyWhenFindByIdNull() {
        assertThat(adapter.findById(null)).isEmpty();
    }

    @Test
    @DisplayName("Should find audio assignment by segmentId and managedVoiceId")
    void shouldFindBySegmentIdAndManagedVoiceId() {
        ChapterNarrationAudioJpaEntity entity = new ChapterNarrationAudioJpaEntity(
                ID.toString(), SEGMENT_ID.toString(), VOICE_ID.toString(), MEDIA_ASSET_ID.toString(),
                2L, NOW, NOW
        );
        when(repository.findBySegmentIdAndManagedVoiceId(SEGMENT_ID.toString(), VOICE_ID.toString()))
                .thenReturn(Optional.of(entity));

        Optional<ChapterNarrationAudio> result = adapter.findBySegmentIdAndManagedVoiceId(SEGMENT_ID, VOICE_ID);

        assertThat(result).isPresent();
        assertThat(result.get().getId()).isEqualTo(ID);
        assertThat(result.get().getGeneratedSynthesisRevision()).isEqualTo(2L);
    }

    @Test
    @DisplayName("Should return empty when segmentId or managedVoiceId is null")
    void shouldReturnEmptyWhenNullParameters() {
        assertThat(adapter.findBySegmentIdAndManagedVoiceId(null, VOICE_ID)).isEmpty();
        assertThat(adapter.findBySegmentIdAndManagedVoiceId(SEGMENT_ID, null)).isEmpty();
    }

    @Test
    @DisplayName("Should find all audio assignments by segmentId")
    void shouldFindBySegmentId() {
        ChapterNarrationAudioJpaEntity entity1 = new ChapterNarrationAudioJpaEntity(
                ID.toString(), SEGMENT_ID.toString(), VOICE_ID.toString(), MEDIA_ASSET_ID.toString(),
                1L, NOW, NOW
        );
        when(repository.findBySegmentId(SEGMENT_ID.toString())).thenReturn(List.of(entity1));

        List<ChapterNarrationAudio> result = adapter.findBySegmentId(SEGMENT_ID);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getId()).isEqualTo(ID);
    }

    @Test
    @DisplayName("Should save audio assignment and map to domain")
    void shouldSaveAndMapToDomain() {
        ChapterNarrationAudio domain = ChapterNarrationAudio.create(
                ID, SEGMENT_ID, VOICE_ID, MEDIA_ASSET_ID, 1L, NOW
        );

        ChapterNarrationAudioJpaEntity savedEntity = new ChapterNarrationAudioJpaEntity(
                ID.toString(), SEGMENT_ID.toString(), VOICE_ID.toString(), MEDIA_ASSET_ID.toString(),
                1L, NOW, NOW
        );
        when(repository.saveAndFlush(any(ChapterNarrationAudioJpaEntity.class))).thenReturn(savedEntity);

        ChapterNarrationAudio saved = adapter.save(domain);

        ArgumentCaptor<ChapterNarrationAudioJpaEntity> captor = ArgumentCaptor.forClass(ChapterNarrationAudioJpaEntity.class);
        verify(repository).saveAndFlush(captor.capture());

        ChapterNarrationAudioJpaEntity captured = captor.getValue();
        assertThat(captured.getId()).isEqualTo(ID.toString());
        assertThat(captured.getSegmentId()).isEqualTo(SEGMENT_ID.toString());
        assertThat(captured.getManagedVoiceId()).isEqualTo(VOICE_ID.toString());
        assertThat(captured.getMediaAssetId()).isEqualTo(MEDIA_ASSET_ID.toString());
        assertThat(captured.getGeneratedSynthesisRevision()).isEqualTo(1L);

        assertThat(saved.getId()).isEqualTo(ID);
    }

    @Test
    @DisplayName("Should reject saving null audio assignment")
    void shouldRejectSaveNull() {
        assertThatThrownBy(() -> adapter.save(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("Should translate uq_novel_chapter_narration_audio_segment_voice to ChapterNarrationAudioAlreadyExistsException")
    void shouldTranslateUniqueConstraintViolation() {
        ChapterNarrationAudio domain = ChapterNarrationAudio.create(
                ID, SEGMENT_ID, VOICE_ID, MEDIA_ASSET_ID, 1L, NOW
        );

        org.hibernate.exception.ConstraintViolationException cve = new org.hibernate.exception.ConstraintViolationException(
                "Duplicate entry", null, "uq_novel_chapter_narration_audio_segment_voice"
        );
        org.springframework.dao.DataIntegrityViolationException dive =
                new org.springframework.dao.DataIntegrityViolationException("Constraint violation", cve);

        when(repository.saveAndFlush(any(ChapterNarrationAudioJpaEntity.class))).thenThrow(dive);

        assertThatThrownBy(() -> adapter.save(domain))
                .isInstanceOf(com.universe.novel.application.exceptions.ChapterNarrationAudioAlreadyExistsException.class);
    }

    @Test
    @DisplayName("Should rethrow unrelated DataIntegrityViolationException without translating")
    void shouldRethrowUnrelatedDataIntegrityViolation() {
        ChapterNarrationAudio domain = ChapterNarrationAudio.create(
                ID, SEGMENT_ID, VOICE_ID, MEDIA_ASSET_ID, 1L, NOW
        );

        org.springframework.dao.DataIntegrityViolationException dive =
                new org.springframework.dao.DataIntegrityViolationException("Unrelated foreign key failure");

        when(repository.saveAndFlush(any(ChapterNarrationAudioJpaEntity.class))).thenThrow(dive);

        assertThatThrownBy(() -> adapter.save(domain))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }
}
