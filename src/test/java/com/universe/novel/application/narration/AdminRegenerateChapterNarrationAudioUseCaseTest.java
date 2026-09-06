package com.universe.novel.application.narration;

import com.universe.novel.application.exceptions.ChapterNarrationSegmentNotFoundException;
import com.universe.novel.application.ports.ChapterNarrationSegmentRepositoryPort;
import com.universe.novel.domain.narration.ChapterNarrationSegment;
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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("AdminRegenerateChapterNarrationAudioUseCase Unit Tests")
class AdminRegenerateChapterNarrationAudioUseCaseTest {

    private static final UUID CHAPTER_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID OTHER_CHAPTER_ID = UUID.fromString("11111111-1111-1111-1111-111111111199");
    private static final UUID SEGMENT_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID VOICE_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final UUID MEDIA_ASSET_1_ID = UUID.fromString("44444444-4444-4444-4444-444444444441");
    private static final UUID MEDIA_ASSET_2_ID = UUID.fromString("44444444-4444-4444-4444-444444444442");

    @Mock
    private ChapterNarrationSegmentRepositoryPort segmentRepositoryPort;

    @Mock
    private RegenerateChapterNarrationAudioUseCase regenerateUseCase;

    private AdminRegenerateChapterNarrationAudioUseCase useCase;

    @BeforeEach
    void setUp() {
        useCase = new AdminRegenerateChapterNarrationAudioUseCase(segmentRepositoryPort, regenerateUseCase);
    }

    @Test
    @DisplayName("Successfully validates chapter ownership and delegates to RegenerateChapterNarrationAudioUseCase")
    void shouldValidateOwnershipAndDelegate() {
        ChapterNarrationSegment segment = ChapterNarrationSegment.create(
                SEGMENT_ID, CHAPTER_ID, 0, "Đoạn văn 0", Instant.now()
        );
        RegenerateChapterNarrationAudioResult expectedResult = new RegenerateChapterNarrationAudioResult(
                UUID.randomUUID(), SEGMENT_ID, VOICE_ID, MEDIA_ASSET_1_ID, MEDIA_ASSET_2_ID, 2L,
                RegenerateNarrationAudioOutcome.REGENERATED
        );

        when(segmentRepositoryPort.findById(SEGMENT_ID)).thenReturn(Optional.of(segment));
        when(regenerateUseCase.execute(SEGMENT_ID, VOICE_ID)).thenReturn(expectedResult);

        RegenerateChapterNarrationAudioResult result = useCase.execute(CHAPTER_ID, SEGMENT_ID, VOICE_ID);

        assertThat(result).isEqualTo(expectedResult);
        verify(regenerateUseCase).execute(SEGMENT_ID, VOICE_ID);
    }

    @Test
    @DisplayName("Rejects regeneration when segment belongs to a different chapter")
    void shouldRejectWhenSegmentBelongsToDifferentChapter() {
        ChapterNarrationSegment segment = ChapterNarrationSegment.create(
                SEGMENT_ID, OTHER_CHAPTER_ID, 0, "Đoạn văn 0", Instant.now()
        );

        when(segmentRepositoryPort.findById(SEGMENT_ID)).thenReturn(Optional.of(segment));

        assertThatThrownBy(() -> useCase.execute(CHAPTER_ID, SEGMENT_ID, VOICE_ID))
                .isInstanceOf(ChapterNarrationSegmentNotFoundException.class)
                .hasMessageContaining(CHAPTER_ID.toString());

        verify(regenerateUseCase, never()).execute(SEGMENT_ID, VOICE_ID);
    }

    @Test
    @DisplayName("Rejects regeneration when segment does not exist")
    void shouldRejectWhenSegmentNotFound() {
        when(segmentRepositoryPort.findById(SEGMENT_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> useCase.execute(CHAPTER_ID, SEGMENT_ID, VOICE_ID))
                .isInstanceOf(ChapterNarrationSegmentNotFoundException.class);

        verify(regenerateUseCase, never()).execute(SEGMENT_ID, VOICE_ID);
    }

    @Test
    @DisplayName("Rejects null arguments")
    void shouldRejectNullArguments() {
        assertThatThrownBy(() -> useCase.execute(null, SEGMENT_ID, VOICE_ID))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> useCase.execute(CHAPTER_ID, null, VOICE_ID))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> useCase.execute(CHAPTER_ID, SEGMENT_ID, null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
