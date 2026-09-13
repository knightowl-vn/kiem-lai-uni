package com.universe.novel.application.narration;

import com.universe.novel.application.ports.ChapterNarrationAudioRepositoryPort;
import com.universe.novel.domain.narration.ChapterNarrationAudio;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("GetChapterNarrationAudioAssignmentUseCase Unit Tests")
class GetChapterNarrationAudioAssignmentUseCaseTest {

    @Mock
    private ChapterNarrationAudioRepositoryPort audioRepositoryPort;

    private GetChapterNarrationAudioAssignmentUseCase useCase;

    private static final UUID SEGMENT_ID = UUID.fromString("20000000-0000-0000-0000-000000000002");
    private static final UUID VOICE_ID = UUID.fromString("30000000-0000-0000-0000-000000000003");
    private static final UUID MEDIA_ASSET_ID = UUID.fromString("40000000-0000-0000-0000-000000000004");
    private static final UUID AUDIO_ID = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final Instant NOW = Instant.parse("2026-09-05T12:00:00Z");

    @BeforeEach
    void setUp() {
        useCase = new GetChapterNarrationAudioAssignmentUseCase(audioRepositoryPort);
    }

    @Test
    @DisplayName("Should return empty when no audio assignment exists for segment and voice")
    void shouldReturnEmptyWhenNoAudioAssignmentExists() {
        when(audioRepositoryPort.findBySegmentIdAndManagedVoiceId(SEGMENT_ID, VOICE_ID))
                .thenReturn(Optional.empty());

        Optional<ChapterNarrationAudioAssignmentDTO> result = useCase.execute(SEGMENT_ID, VOICE_ID, 1L);

        assertThat(result).isEmpty();
        verify(audioRepositoryPort).findBySegmentIdAndManagedVoiceId(SEGMENT_ID, VOICE_ID);
    }

    @Test
    @DisplayName("Should return compatible DTO when revision matches current synthesis revision")
    void shouldReturnCompatibleDtoWhenRevisionsMatch() {
        ChapterNarrationAudio audio = ChapterNarrationAudio.create(
                AUDIO_ID, SEGMENT_ID, VOICE_ID, MEDIA_ASSET_ID, 2L, NOW
        );
        when(audioRepositoryPort.findBySegmentIdAndManagedVoiceId(SEGMENT_ID, VOICE_ID))
                .thenReturn(Optional.of(audio));

        Optional<ChapterNarrationAudioAssignmentDTO> result = useCase.execute(SEGMENT_ID, VOICE_ID, 2L);

        assertThat(result).isPresent();
        ChapterNarrationAudioAssignmentDTO dto = result.get();
        assertThat(dto.id()).isEqualTo(AUDIO_ID);
        assertThat(dto.segmentId()).isEqualTo(SEGMENT_ID);
        assertThat(dto.managedVoiceId()).isEqualTo(VOICE_ID);
        assertThat(dto.mediaAssetId()).isEqualTo(MEDIA_ASSET_ID);
        assertThat(dto.generatedSynthesisRevision()).isEqualTo(2L);
        assertThat(dto.compatible()).isTrue();
        assertThat(dto.createdAt()).isEqualTo(NOW);
        assertThat(dto.updatedAt()).isEqualTo(NOW);
    }

    @Test
    @DisplayName("Should return incompatible DTO when revision differs (stale cache)")
    void shouldReturnIncompatibleDtoWhenRevisionsDiffer() {
        ChapterNarrationAudio audio = ChapterNarrationAudio.create(
                AUDIO_ID, SEGMENT_ID, VOICE_ID, MEDIA_ASSET_ID, 1L, NOW
        );
        when(audioRepositoryPort.findBySegmentIdAndManagedVoiceId(SEGMENT_ID, VOICE_ID))
                .thenReturn(Optional.of(audio));

        // Voice revision is now 3
        Optional<ChapterNarrationAudioAssignmentDTO> result = useCase.execute(
                new GetChapterNarrationAudioAssignmentQuery(SEGMENT_ID, VOICE_ID, 3L)
        );

        assertThat(result).isPresent();
        ChapterNarrationAudioAssignmentDTO dto = result.get();
        assertThat(dto.id()).isEqualTo(AUDIO_ID);
        assertThat(dto.generatedSynthesisRevision()).isEqualTo(1L);
        assertThat(dto.compatible()).isFalse();
    }

    @Test
    @DisplayName("Should reject null arguments or invalid revision")
    void shouldRejectInvalidArguments() {
        assertThatThrownBy(() -> useCase.execute(null, VOICE_ID, 1L))
                .isInstanceOf(NullPointerException.class);

        assertThatThrownBy(() -> useCase.execute(SEGMENT_ID, null, 1L))
                .isInstanceOf(NullPointerException.class);

        assertThatThrownBy(() -> useCase.execute(SEGMENT_ID, VOICE_ID, 0L))
                .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> useCase.execute((GetChapterNarrationAudioAssignmentQuery) null))
                .isInstanceOf(NullPointerException.class);
    }
}
