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
@DisplayName("AdminGenerateChapterNarrationAudioUseCase Unit Tests")
class AdminGenerateChapterNarrationAudioUseCaseTest {

    private static final UUID CHAPTER_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID OTHER_CHAPTER_ID = UUID.fromString("11111111-1111-1111-1111-111111111199");
    private static final UUID SEGMENT_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID VOICE_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final UUID MEDIA_ASSET_ID = UUID.fromString("44444444-4444-4444-4444-444444444444");

    @Mock
    private ChapterNarrationSegmentRepositoryPort segmentRepositoryPort;

    @Mock
    private GenerateChapterNarrationAudioUseCase generateUseCase;

    private AdminGenerateChapterNarrationAudioUseCase useCase;

    @BeforeEach
    void setUp() {
        useCase = new AdminGenerateChapterNarrationAudioUseCase(segmentRepositoryPort, generateUseCase);
    }

    @Test
    @DisplayName("Successfully validates chapter ownership and delegates to GenerateChapterNarrationAudioUseCase")
    void shouldValidateOwnershipAndDelegate() {
        ChapterNarrationSegment segment = ChapterNarrationSegment.create(
                SEGMENT_ID, CHAPTER_ID, 0, "Đoạn văn 0", Instant.now()
        );
        GenerateChapterNarrationAudioResult expectedResult = new GenerateChapterNarrationAudioResult(
                UUID.randomUUID(), SEGMENT_ID, VOICE_ID, MEDIA_ASSET_ID, 1L,
                NarrationAudioGenerationOutcome.GENERATED
        );

        when(segmentRepositoryPort.findById(SEGMENT_ID)).thenReturn(Optional.of(segment));
        when(generateUseCase.execute(SEGMENT_ID, VOICE_ID)).thenReturn(expectedResult);

        GenerateChapterNarrationAudioResult result = useCase.execute(CHAPTER_ID, SEGMENT_ID, VOICE_ID);

        assertThat(result).isEqualTo(expectedResult);
        verify(generateUseCase).execute(SEGMENT_ID, VOICE_ID);
    }

    @Test
    @DisplayName("Rejects generation when segment belongs to a different chapter")
    void shouldRejectWhenSegmentBelongsToDifferentChapter() {
        ChapterNarrationSegment segment = ChapterNarrationSegment.create(
                SEGMENT_ID, OTHER_CHAPTER_ID, 0, "Đoạn văn 0", Instant.now()
        );

        when(segmentRepositoryPort.findById(SEGMENT_ID)).thenReturn(Optional.of(segment));

        assertThatThrownBy(() -> useCase.execute(CHAPTER_ID, SEGMENT_ID, VOICE_ID))
                .isInstanceOf(ChapterNarrationSegmentNotFoundException.class)
                .hasMessageContaining(CHAPTER_ID.toString());

        verify(generateUseCase, never()).execute(SEGMENT_ID, VOICE_ID);
    }

    @Test
    @DisplayName("Rejects generation when segment does not exist")
    void shouldRejectWhenSegmentNotFound() {
        when(segmentRepositoryPort.findById(SEGMENT_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> useCase.execute(CHAPTER_ID, SEGMENT_ID, VOICE_ID))
                .isInstanceOf(ChapterNarrationSegmentNotFoundException.class);

        verify(generateUseCase, never()).execute(SEGMENT_ID, VOICE_ID);
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
