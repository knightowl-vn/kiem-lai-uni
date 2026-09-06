package com.universe.novel.infrastructure.persistence.narration;

import com.universe.novel.domain.narration.ChapterNarrationAudioFailure;
import com.universe.novel.domain.narration.NarrationAudioFailureStage;
import com.universe.novel.domain.narration.NarrationAudioOperation;
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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("ChapterNarrationAudioFailurePersistenceAdapter Unit Tests")
class ChapterNarrationAudioFailurePersistenceAdapterTest {

    @Mock
    private SpringDataChapterNarrationAudioFailureJpaRepository repository;

    private ChapterNarrationAudioFailurePersistenceAdapter adapter;

    private static final UUID ID = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final UUID SEGMENT_ID = UUID.fromString("20000000-0000-0000-0000-000000000002");
    private static final UUID VOICE_ID = UUID.fromString("30000000-0000-0000-0000-000000000003");
    private static final Instant NOW = Instant.parse("2026-09-05T12:00:00Z");

    @BeforeEach
    void setUp() {
        adapter = new ChapterNarrationAudioFailurePersistenceAdapter(repository);
    }

    @Test
    @DisplayName("Should find failure record by segmentId and managedVoiceId")
    void shouldFindBySegmentIdAndManagedVoiceId() {
        ChapterNarrationAudioFailureJpaEntity entity = new ChapterNarrationAudioFailureJpaEntity(
                ID.toString(),
                SEGMENT_ID.toString(),
                VOICE_ID.toString(),
                NarrationAudioOperation.INITIAL_GENERATION,
                NarrationAudioFailureStage.TTS_SYNTHESIS,
                1L,
                2,
                "ConnectException",
                "Connection timeout",
                NOW.minusSeconds(60),
                NOW
        );

        when(repository.findBySegmentIdAndManagedVoiceId(SEGMENT_ID.toString(), VOICE_ID.toString()))
                .thenReturn(Optional.of(entity));

        Optional<ChapterNarrationAudioFailure> result = adapter.findBySegmentIdAndManagedVoiceId(SEGMENT_ID, VOICE_ID);

        assertThat(result).isPresent();
        ChapterNarrationAudioFailure failure = result.get();
        assertThat(failure.getId()).isEqualTo(ID);
        assertThat(failure.getSegmentId()).isEqualTo(SEGMENT_ID);
        assertThat(failure.getManagedVoiceId()).isEqualTo(VOICE_ID);
        assertThat(failure.getOperation()).isEqualTo(NarrationAudioOperation.INITIAL_GENERATION);
        assertThat(failure.getStage()).isEqualTo(NarrationAudioFailureStage.TTS_SYNTHESIS);
        assertThat(failure.getAttemptedSynthesisRevision()).isEqualTo(1L);
        assertThat(failure.getFailureCount()).isEqualTo(2);
        assertThat(failure.getErrorType()).isEqualTo("ConnectException");
        assertThat(failure.getErrorMessage()).isEqualTo("Connection timeout");
    }

    @Test
    @DisplayName("Should return empty optional when segmentId or managedVoiceId is null")
    void shouldReturnEmptyWhenNullParameters() {
        assertThat(adapter.findBySegmentIdAndManagedVoiceId(null, VOICE_ID)).isEmpty();
        assertThat(adapter.findBySegmentIdAndManagedVoiceId(SEGMENT_ID, null)).isEmpty();
    }

    @Test
    @DisplayName("Should find failure records by batch segmentIds and managedVoiceId")
    void shouldFindBySegmentIdInAndManagedVoiceId() {
        ChapterNarrationAudioFailureJpaEntity entity = new ChapterNarrationAudioFailureJpaEntity(
                ID.toString(),
                SEGMENT_ID.toString(),
                VOICE_ID.toString(),
                NarrationAudioOperation.INITIAL_GENERATION,
                NarrationAudioFailureStage.TTS_SYNTHESIS,
                1L,
                1,
                "ConnectException",
                "Narration TTS synthesis failed.",
                NOW,
                NOW
        );

        when(repository.findBySegmentIdInAndManagedVoiceId(List.of(SEGMENT_ID.toString()), VOICE_ID.toString()))
                .thenReturn(List.of(entity));

        List<ChapterNarrationAudioFailure> result = adapter.findBySegmentIdInAndManagedVoiceId(List.of(SEGMENT_ID), VOICE_ID);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getId()).isEqualTo(ID);
        assertThat(result.get(0).getSegmentId()).isEqualTo(SEGMENT_ID);
        assertThat(result.get(0).getManagedVoiceId()).isEqualTo(VOICE_ID);
    }

    @Test
    @DisplayName("Should return empty list when batch find receives empty or null arguments")
    void shouldReturnEmptyWhenBatchFindReceivesEmptyOrNullArgs() {
        assertThat(adapter.findBySegmentIdInAndManagedVoiceId(null, VOICE_ID)).isEmpty();
        assertThat(adapter.findBySegmentIdInAndManagedVoiceId(List.of(), VOICE_ID)).isEmpty();
        assertThat(adapter.findBySegmentIdInAndManagedVoiceId(List.of(SEGMENT_ID), null)).isEmpty();
    }

    @Test
    @DisplayName("Should save failure record and map correctly to JPA entity")
    void shouldSaveFailureRecord() {
        ChapterNarrationAudioFailure domain = ChapterNarrationAudioFailure.create(
                ID,
                SEGMENT_ID,
                VOICE_ID,
                NarrationAudioOperation.REGENERATION,
                NarrationAudioFailureStage.MEDIA_UPLOAD,
                3L,
                "TimeoutException",
                NOW
        );

        ChapterNarrationAudioFailureJpaEntity savedEntity = new ChapterNarrationAudioFailureJpaEntity(
                ID.toString(),
                SEGMENT_ID.toString(),
                VOICE_ID.toString(),
                NarrationAudioOperation.REGENERATION,
                NarrationAudioFailureStage.MEDIA_UPLOAD,
                3L,
                1,
                "TimeoutException",
                "Narration audio media upload failed.",
                NOW,
                NOW
        );

        when(repository.saveAndFlush(any(ChapterNarrationAudioFailureJpaEntity.class))).thenReturn(savedEntity);

        ChapterNarrationAudioFailure saved = adapter.save(domain);

        ArgumentCaptor<ChapterNarrationAudioFailureJpaEntity> captor =
                ArgumentCaptor.forClass(ChapterNarrationAudioFailureJpaEntity.class);
        verify(repository).saveAndFlush(captor.capture());

        ChapterNarrationAudioFailureJpaEntity captured = captor.getValue();
        assertThat(captured.getId()).isEqualTo(ID.toString());
        assertThat(captured.getSegmentId()).isEqualTo(SEGMENT_ID.toString());
        assertThat(captured.getManagedVoiceId()).isEqualTo(VOICE_ID.toString());
        assertThat(captured.getOperation()).isEqualTo(NarrationAudioOperation.REGENERATION);
        assertThat(captured.getStage()).isEqualTo(NarrationAudioFailureStage.MEDIA_UPLOAD);
        assertThat(captured.getAttemptedSynthesisRevision()).isEqualTo(3L);
        assertThat(captured.getFailureCount()).isEqualTo(1);
        assertThat(captured.getErrorType()).isEqualTo("TimeoutException");
        assertThat(captured.getErrorMessage()).isEqualTo("Narration audio media upload failed.");

        assertThat(saved.getId()).isEqualTo(ID);
    }

    @Test
    @DisplayName("Should reject saving null failure record")
    void shouldRejectSaveNull() {
        assertThatThrownBy(() -> adapter.save(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("Should delete failure record by segmentId and managedVoiceId")
    void shouldDeleteBySegmentIdAndManagedVoiceId() {
        adapter.deleteBySegmentIdAndManagedVoiceId(SEGMENT_ID, VOICE_ID);

        verify(repository).deleteBySegmentIdAndManagedVoiceId(SEGMENT_ID.toString(), VOICE_ID.toString());
    }

    @Test
    @DisplayName("Should safely ignore delete when segmentId or managedVoiceId is null")
    void shouldIgnoreDeleteWhenNull() {
        adapter.deleteBySegmentIdAndManagedVoiceId(null, VOICE_ID);
        adapter.deleteBySegmentIdAndManagedVoiceId(SEGMENT_ID, null);

        verify(repository, never()).deleteBySegmentIdAndManagedVoiceId(any(), any());
    }
}
