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
import java.util.Collections;
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
    @DisplayName("Should find audio assignment by ID and map version")
    void shouldFindById() {
        ChapterNarrationAudioJpaEntity entity = new ChapterNarrationAudioJpaEntity(
                ID.toString(), SEGMENT_ID.toString(), VOICE_ID.toString(), MEDIA_ASSET_ID.toString(),
                1L, 3L, NOW, NOW
        );
        when(repository.findById(ID.toString())).thenReturn(Optional.of(entity));

        Optional<ChapterNarrationAudio> result = adapter.findById(ID);

        assertThat(result).isPresent();
        assertThat(result.get().getId()).isEqualTo(ID);
        assertThat(result.get().getSegmentId()).isEqualTo(SEGMENT_ID);
        assertThat(result.get().getManagedVoiceId()).isEqualTo(VOICE_ID);
        assertThat(result.get().getMediaAssetId()).isEqualTo(MEDIA_ASSET_ID);
        assertThat(result.get().getGeneratedSynthesisRevision()).isEqualTo(1L);
        assertThat(result.get().getVersion()).isEqualTo(3L);
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
                2L, 0L, NOW, NOW
        );
        when(repository.findBySegmentIdAndManagedVoiceId(SEGMENT_ID.toString(), VOICE_ID.toString()))
                .thenReturn(Optional.of(entity));

        Optional<ChapterNarrationAudio> result = adapter.findBySegmentIdAndManagedVoiceId(SEGMENT_ID, VOICE_ID);

        assertThat(result).isPresent();
        assertThat(result.get().getId()).isEqualTo(ID);
        assertThat(result.get().getGeneratedSynthesisRevision()).isEqualTo(2L);
        assertThat(result.get().getVersion()).isEqualTo(0L);
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
                1L, 0L, NOW, NOW
        );
        when(repository.findBySegmentId(SEGMENT_ID.toString())).thenReturn(List.of(entity1));

        List<ChapterNarrationAudio> result = adapter.findBySegmentId(SEGMENT_ID);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getId()).isEqualTo(ID);
        assertThat(result.get(0).getVersion()).isEqualTo(0L);
    }

    @Test
    @DisplayName("Should find audio assignments by batch segmentIds and managedVoiceId")
    void shouldFindBySegmentIdInAndManagedVoiceId() {
        ChapterNarrationAudioJpaEntity entity1 = new ChapterNarrationAudioJpaEntity(
                ID.toString(), SEGMENT_ID.toString(), VOICE_ID.toString(), MEDIA_ASSET_ID.toString(),
                1L, 1L, NOW, NOW
        );
        when(repository.findBySegmentIdInAndManagedVoiceId(List.of(SEGMENT_ID.toString()), VOICE_ID.toString()))
                .thenReturn(List.of(entity1));

        List<ChapterNarrationAudio> result = adapter.findBySegmentIdInAndManagedVoiceId(List.of(SEGMENT_ID), VOICE_ID);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getId()).isEqualTo(ID);
        assertThat(result.get(0).getSegmentId()).isEqualTo(SEGMENT_ID);
        assertThat(result.get(0).getManagedVoiceId()).isEqualTo(VOICE_ID);
        assertThat(result.get(0).getVersion()).isEqualTo(1L);
    }

    @Test
    @DisplayName("Should return empty list when batch find receives empty or null arguments")
    void shouldReturnEmptyWhenBatchFindReceivesEmptyOrNullArgs() {
        assertThat(adapter.findBySegmentIdInAndManagedVoiceId(null, VOICE_ID)).isEmpty();
        assertThat(adapter.findBySegmentIdInAndManagedVoiceId(List.of(), VOICE_ID)).isEmpty();
        assertThat(adapter.findBySegmentIdInAndManagedVoiceId(List.of(SEGMENT_ID), null)).isEmpty();
    }

    @Test
    @DisplayName("Should find audio assignments across all voices by batch segmentIds")
    void shouldFindBySegmentIdIn() {
        ChapterNarrationAudioJpaEntity entity1 = new ChapterNarrationAudioJpaEntity(
                ID.toString(), SEGMENT_ID.toString(), VOICE_ID.toString(), MEDIA_ASSET_ID.toString(),
                1L, 1L, NOW, NOW
        );
        when(repository.findBySegmentIdIn(List.of(SEGMENT_ID.toString())))
                .thenReturn(List.of(entity1));

        List<ChapterNarrationAudio> result = adapter.findBySegmentIdIn(List.of(SEGMENT_ID));

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getId()).isEqualTo(ID);
        assertThat(result.get(0).getSegmentId()).isEqualTo(SEGMENT_ID);
        assertThat(result.get(0).getManagedVoiceId()).isEqualTo(VOICE_ID);
    }

    @Test
    @DisplayName("Should return empty list when findBySegmentIdIn receives empty or null arguments")
    void shouldReturnEmptyWhenFindBySegmentIdInReceivesEmptyOrNullArgs() {
        assertThat(adapter.findBySegmentIdIn(null)).isEmpty();
        assertThat(adapter.findBySegmentIdIn(List.of())).isEmpty();
        assertThat(adapter.findBySegmentIdIn(Collections.singletonList(null))).isEmpty();
    }

    @Test
    @DisplayName("Should save new audio assignment and map null version to entity")
    void shouldSaveAndMapToDomain() {
        ChapterNarrationAudio domain = ChapterNarrationAudio.create(
                ID, SEGMENT_ID, VOICE_ID, MEDIA_ASSET_ID, 1L, NOW
        );
        assertThat(domain.getVersion()).isNull();

        ChapterNarrationAudioJpaEntity savedEntity = new ChapterNarrationAudioJpaEntity(
                ID.toString(), SEGMENT_ID.toString(), VOICE_ID.toString(), MEDIA_ASSET_ID.toString(),
                1L, 0L, NOW, NOW
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
        assertThat(captured.getEncodedContributionSamples()).isNull();
        assertThat(captured.getEncodedSampleRateHz()).isNull();
        assertThat(captured.getVersion()).isNull();

        assertThat(saved.getId()).isEqualTo(ID);
        assertThat(saved.getEncodedContributionSamples()).isNull();
        assertThat(saved.getEncodedSampleRateHz()).isNull();
        assertThat(saved.getVersion()).isEqualTo(0L);
    }

    @Test
    @DisplayName("Should save timed audio assignment and map timing metadata to and from entity")
    void shouldSaveAndMapTimingToAndFromDomain() {
        ChapterNarrationAudio domain = ChapterNarrationAudio.create(
                ID, SEGMENT_ID, VOICE_ID, MEDIA_ASSET_ID, 1L, 51840L, 48000, NOW
        );

        ChapterNarrationAudioJpaEntity savedEntity = new ChapterNarrationAudioJpaEntity(
                ID.toString(), SEGMENT_ID.toString(), VOICE_ID.toString(), MEDIA_ASSET_ID.toString(),
                1L, 51840L, 48000, 0L, NOW, NOW
        );
        when(repository.saveAndFlush(any(ChapterNarrationAudioJpaEntity.class))).thenReturn(savedEntity);

        ChapterNarrationAudio saved = adapter.save(domain);

        ArgumentCaptor<ChapterNarrationAudioJpaEntity> captor = ArgumentCaptor.forClass(ChapterNarrationAudioJpaEntity.class);
        verify(repository).saveAndFlush(captor.capture());

        ChapterNarrationAudioJpaEntity captured = captor.getValue();
        assertThat(captured.getEncodedContributionSamples()).isEqualTo(51840L);
        assertThat(captured.getEncodedSampleRateHz()).isEqualTo(48000);

        assertThat(saved.getEncodedContributionSamples()).isEqualTo(51840L);
        assertThat(saved.getEncodedSampleRateHz()).isEqualTo(48000);
    }

    @Test
    @DisplayName("Should save existing rehydrated audio assignment and preserve version on entity")
    void shouldSaveExistingAudioAssignmentPreservingVersion() {
        ChapterNarrationAudio domain = ChapterNarrationAudio.rehydrate(
                ID, SEGMENT_ID, VOICE_ID, MEDIA_ASSET_ID, 1L, 2L, NOW, NOW
        );
        assertThat(domain.getVersion()).isEqualTo(2L);

        ChapterNarrationAudioJpaEntity savedEntity = new ChapterNarrationAudioJpaEntity(
                ID.toString(), SEGMENT_ID.toString(), VOICE_ID.toString(), MEDIA_ASSET_ID.toString(),
                1L, 3L, NOW, NOW
        );
        when(repository.saveAndFlush(any(ChapterNarrationAudioJpaEntity.class))).thenReturn(savedEntity);

        ChapterNarrationAudio saved = adapter.save(domain);

        ArgumentCaptor<ChapterNarrationAudioJpaEntity> captor = ArgumentCaptor.forClass(ChapterNarrationAudioJpaEntity.class);
        verify(repository).saveAndFlush(captor.capture());

        ChapterNarrationAudioJpaEntity captured = captor.getValue();
        assertThat(captured.getVersion()).isEqualTo(2L);
        assertThat(saved.getVersion()).isEqualTo(3L);
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

    @Test
    @DisplayName("Should delete audio assignment by ID")
    void shouldDeleteById() {
        adapter.deleteById(ID);

        verify(repository).deleteById(ID.toString());
        verify(repository).flush();
    }

    @Test
    @DisplayName("Should safely handle deleteById when ID is null")
    void shouldHandleDeleteByIdNull() {
        adapter.deleteById(null);

        verify(repository, org.mockito.Mockito.never()).deleteById(any());
    }

    @Test
    @DisplayName("Should check if media asset is referenced excluding given audio ID")
    void shouldCheckExistsOtherReferenceToMediaAsset() {
        when(repository.existsByMediaAssetIdAndIdNot(MEDIA_ASSET_ID.toString(), ID.toString())).thenReturn(true);

        boolean exists = adapter.existsOtherReferenceToMediaAsset(MEDIA_ASSET_ID, ID);

        assertThat(exists).isTrue();
        verify(repository).existsByMediaAssetIdAndIdNot(MEDIA_ASSET_ID.toString(), ID.toString());
    }

    @Test
    @DisplayName("Should return false when mediaAssetId is null in existsOtherReferenceToMediaAsset")
    void shouldReturnFalseWhenMediaAssetIdNull() {
        assertThat(adapter.existsOtherReferenceToMediaAsset(null, ID)).isFalse();
    }
}

