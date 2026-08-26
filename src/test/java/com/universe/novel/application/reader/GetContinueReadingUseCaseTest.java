package com.universe.novel.application.reader;

import com.universe.novel.application.ports.ReaderContinueReadingQueryPort;
import com.universe.novel.application.ports.ReaderContinueReadingQueryPort.ReadableChapterDestination;
import com.universe.novel.application.ports.ReadingProgressRepositoryPort;
import com.universe.novel.contracts.dto.reader.ReaderContinueReadingDTO;
import com.universe.novel.domain.reader.UserReadingProgress;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GetContinueReadingUseCaseTest {

    private static final UUID USER_ID =
            UUID.fromString("11111111-1111-1111-1111-111111111111");

    private static final UUID PROGRESS_ID =
            UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");

    private static final UUID CHAPTER_1_ID =
            UUID.fromString("10000000-0000-0000-0000-000000000001");

    private static final UUID CHAPTER_3_ID =
            UUID.fromString("10000000-0000-0000-0000-000000000003");

    private static final UUID CHAPTER_10_ID =
            UUID.fromString("10000000-0000-0000-0000-000000000010");

    private static final UUID CHAPTER_30_ID =
            UUID.fromString("10000000-0000-0000-0000-000000000030");

    private static final UUID CHAPTER_32_ID =
            UUID.fromString("10000000-0000-0000-0000-000000000032");

    private static final UUID CHAPTER_35_ID =
            UUID.fromString("10000000-0000-0000-0000-000000000035");

    private static final Instant T1 =
            Instant.parse("2026-08-25T08:00:00Z");

    @Mock
    private ReadingProgressRepositoryPort readingProgressRepositoryPort;

    @Mock
    private ReaderContinueReadingQueryPort continueReadingQueryPort;

    private GetContinueReadingUseCase useCase;

    @BeforeEach
    void setUp() {
        useCase = new GetContinueReadingUseCase(
                readingProgressRepositoryPort,
                continueReadingQueryPort
        );
    }

    @Nested
    @DisplayName("1. No Progress or Null Input")
    class NoProgressTests {

        @Test
        @DisplayName("Returns Optional.empty() when userId is null")
        void shouldReturnEmptyWhenUserIdIsNull() {
            Optional<ReaderContinueReadingDTO> result = useCase.execute(null);

            assertThat(result).isEmpty();
            verifyNoInteractions(readingProgressRepositoryPort);
            verifyNoInteractions(continueReadingQueryPort);
        }

        @Test
        @DisplayName("Returns Optional.empty() when user has no reading progress recorded")
        void shouldReturnEmptyWhenUserHasNoProgress() {
            when(readingProgressRepositoryPort.findByUserId(USER_ID))
                    .thenReturn(Optional.empty());

            Optional<ReaderContinueReadingDTO> result = useCase.execute(USER_ID);

            assertThat(result).isEmpty();
            verify(readingProgressRepositoryPort).findByUserId(USER_ID);
            verifyNoInteractions(continueReadingQueryPort);
        }
    }

    @Nested
    @DisplayName("2. Direct Readable Last-Opened Chapter")
    class DirectReadableTests {

        @Test
        @DisplayName("Returns direct last-opened Chapter when it is publicly readable")
        void shouldReturnDirectChapterWhenLastOpenedIsReadable() {
            UserReadingProgress progress = UserReadingProgress.rehydrate(
                    PROGRESS_ID, USER_ID, CHAPTER_10_ID, 20, T1, T1
            );
            ReadableChapterDestination directDest = new ReadableChapterDestination(
                    CHAPTER_10_ID, 10, "Chương 10: Khởi Đầu", "chuong-10-khoi-dau"
            );

            when(readingProgressRepositoryPort.findByUserId(USER_ID))
                    .thenReturn(Optional.of(progress));
            when(continueReadingQueryPort.findPublishedChapterById(CHAPTER_10_ID))
                    .thenReturn(Optional.of(directDest));

            Optional<ReaderContinueReadingDTO> result = useCase.execute(USER_ID);

            assertThat(result).isPresent();
            ReaderContinueReadingDTO dto = result.get();
            assertThat(dto.chapterId()).isEqualTo(CHAPTER_10_ID);
            assertThat(dto.chapterNumber()).isEqualTo(10);
            assertThat(dto.title()).isEqualTo("Chương 10: Khởi Đầu");
            assertThat(dto.slug()).isEqualTo("chuong-10-khoi-dau");
            assertThat(dto.highestReachedChapterNumber()).isEqualTo(20);

            verify(continueReadingQueryPort).findPublishedChapterById(CHAPTER_10_ID);
            verify(continueReadingQueryPort, never()).findChapterNumberById(any());
            verify(continueReadingQueryPort, never()).findPreviousPublishedChapter(anyInt());
            verify(continueReadingQueryPort, never()).findNextPublishedChapter(anyInt());
        }
    }

    @Nested
    @DisplayName("3. Unavailable Last-Opened Chapter Fallback")
    class FallbackTests {

        @Test
        @DisplayName("Falls back to nearest previous readable Chapter when last-opened Chapter is unavailable (draft/archived/unpublished volume)")
        void shouldFallbackToNearestPreviousReadableChapter() {
            // Scenario: lastOpened = 32 (unpublished/unavailable), highestReached = 500
            // Previous available chapter = 30
            UserReadingProgress progress = UserReadingProgress.rehydrate(
                    PROGRESS_ID, USER_ID, CHAPTER_32_ID, 500, T1, T1
            );
            ReadableChapterDestination prevDest = new ReadableChapterDestination(
                    CHAPTER_30_ID, 30, "Chương 30: Chuyển Biến", "chuong-30-chuyen-bien"
            );

            when(readingProgressRepositoryPort.findByUserId(USER_ID))
                    .thenReturn(Optional.of(progress));
            when(continueReadingQueryPort.findPublishedChapterById(CHAPTER_32_ID))
                    .thenReturn(Optional.empty());
            when(continueReadingQueryPort.findChapterNumberById(CHAPTER_32_ID))
                    .thenReturn(Optional.of(32));
            when(continueReadingQueryPort.findPreviousPublishedChapter(32))
                    .thenReturn(Optional.of(prevDest));

            Optional<ReaderContinueReadingDTO> result = useCase.execute(USER_ID);

            assertThat(result).isPresent();
            ReaderContinueReadingDTO dto = result.get();
            assertThat(dto.chapterId()).isEqualTo(CHAPTER_30_ID);
            assertThat(dto.chapterNumber()).isEqualTo(30);
            assertThat(dto.title()).isEqualTo("Chương 30: Chuyển Biến");
            assertThat(dto.slug()).isEqualTo("chuong-30-chuyen-bien");
            // Destination was chosen strictly near lastOpened (30), NOT from highestReached (500)
            assertThat(dto.highestReachedChapterNumber()).isEqualTo(500);

            verify(continueReadingQueryPort).findPublishedChapterById(CHAPTER_32_ID);
            verify(continueReadingQueryPort).findChapterNumberById(CHAPTER_32_ID);
            verify(continueReadingQueryPort).findPreviousPublishedChapter(32);
            verify(continueReadingQueryPort, never()).findNextPublishedChapter(anyInt());
        }

        @Test
        @DisplayName("Falls back to nearest next readable Chapter when last-opened Chapter is unavailable and no previous readable Chapter exists")
        void shouldFallbackToNearestNextReadableChapterWhenNoPreviousExists() {
            // Scenario: lastOpened = 1 (draft/archived), highestReached = 10
            // No previous readable chapter (< 1)
            // Next available chapter = 3
            UserReadingProgress progress = UserReadingProgress.rehydrate(
                    PROGRESS_ID, USER_ID, CHAPTER_1_ID, 10, T1, T1
            );
            ReadableChapterDestination nextDest = new ReadableChapterDestination(
                    CHAPTER_3_ID, 3, "Chương 3: Khám Phá", "chuong-3-kham-pha"
            );

            when(readingProgressRepositoryPort.findByUserId(USER_ID))
                    .thenReturn(Optional.of(progress));
            when(continueReadingQueryPort.findPublishedChapterById(CHAPTER_1_ID))
                    .thenReturn(Optional.empty());
            when(continueReadingQueryPort.findChapterNumberById(CHAPTER_1_ID))
                    .thenReturn(Optional.of(1));
            when(continueReadingQueryPort.findPreviousPublishedChapter(1))
                    .thenReturn(Optional.empty());
            when(continueReadingQueryPort.findNextPublishedChapter(1))
                    .thenReturn(Optional.of(nextDest));

            Optional<ReaderContinueReadingDTO> result = useCase.execute(USER_ID);

            assertThat(result).isPresent();
            ReaderContinueReadingDTO dto = result.get();
            assertThat(dto.chapterId()).isEqualTo(CHAPTER_3_ID);
            assertThat(dto.chapterNumber()).isEqualTo(3);
            assertThat(dto.title()).isEqualTo("Chương 3: Khám Phá");
            assertThat(dto.slug()).isEqualTo("chuong-3-kham-pha");
            assertThat(dto.highestReachedChapterNumber()).isEqualTo(10);

            verify(continueReadingQueryPort).findPublishedChapterById(CHAPTER_1_ID);
            verify(continueReadingQueryPort).findChapterNumberById(CHAPTER_1_ID);
            verify(continueReadingQueryPort).findPreviousPublishedChapter(1);
            verify(continueReadingQueryPort).findNextPublishedChapter(1);
        }

        @Test
        @DisplayName("Returns Optional.empty() when last-opened Chapter is unavailable and NO readable Chapter exists in novel")
        void shouldReturnEmptyWhenNoReadableChapterExistsInNovel() {
            UserReadingProgress progress = UserReadingProgress.rehydrate(
                    PROGRESS_ID, USER_ID, CHAPTER_32_ID, 32, T1, T1
            );

            when(readingProgressRepositoryPort.findByUserId(USER_ID))
                    .thenReturn(Optional.of(progress));
            when(continueReadingQueryPort.findPublishedChapterById(CHAPTER_32_ID))
                    .thenReturn(Optional.empty());
            when(continueReadingQueryPort.findChapterNumberById(CHAPTER_32_ID))
                    .thenReturn(Optional.of(32));
            when(continueReadingQueryPort.findPreviousPublishedChapter(32))
                    .thenReturn(Optional.empty());
            when(continueReadingQueryPort.findNextPublishedChapter(32))
                    .thenReturn(Optional.empty());

            Optional<ReaderContinueReadingDTO> result = useCase.execute(USER_ID);

            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("Proves highestReachedChapterNumber is display-only: falls back to nearest Chapter 31 even when Chapter 500 is published")
        void shouldFallbackToNearestPreviousChapterAndNotUseHighestReachedChapter() {
            // Scenario:
            // - Reading Progress: lastOpened = 32, highestReached = 500
            // - Chapter 32 is no longer publicly readable
            // - Chapter 31 is publicly readable
            // - Chapter 500 is also publicly readable
            // Expected:
            // - Continue Reading destination = Chapter 31
            // - returned highestReachedChapterNumber = 500
            // - Chapter 500 must NOT be used as the resume destination

            UUID chapter31Id = UUID.fromString("10000000-0000-0000-0000-000000000031");
            UUID chapter500Id = UUID.fromString("10000000-0000-0000-0000-000000000500");

            UserReadingProgress progress = UserReadingProgress.rehydrate(
                    PROGRESS_ID, USER_ID, CHAPTER_32_ID, 500, T1, T1
            );
            ReadableChapterDestination chapter31Dest = new ReadableChapterDestination(
                    chapter31Id, 31, "Chương 31: Bước Đệm", "chuong-31-buoc-dem"
            );

            when(readingProgressRepositoryPort.findByUserId(USER_ID))
                    .thenReturn(Optional.of(progress));
            when(continueReadingQueryPort.findPublishedChapterById(CHAPTER_32_ID))
                    .thenReturn(Optional.empty());
            when(continueReadingQueryPort.findChapterNumberById(CHAPTER_32_ID))
                    .thenReturn(Optional.of(32));
            when(continueReadingQueryPort.findPreviousPublishedChapter(32))
                    .thenReturn(Optional.of(chapter31Dest));

            Optional<ReaderContinueReadingDTO> result = useCase.execute(USER_ID);

            assertThat(result).isPresent();
            ReaderContinueReadingDTO dto = result.get();

            // Destination is Chapter 31, NOT Chapter 500
            assertThat(dto.chapterId()).isEqualTo(chapter31Id);
            assertThat(dto.chapterNumber()).isEqualTo(31);
            assertThat(dto.title()).isEqualTo("Chương 31: Bước Đệm");
            assertThat(dto.slug()).isEqualTo("chuong-31-buoc-dem");

            // highestReachedChapterNumber is preserved purely for display
            assertThat(dto.highestReachedChapterNumber()).isEqualTo(500);

            // Verify Chapter 500 was never queried as a destination
            verify(continueReadingQueryPort, never()).findPublishedChapterById(chapter500Id);
            verify(continueReadingQueryPort, never()).findPreviousPublishedChapter(500);
            verify(continueReadingQueryPort, never()).findNextPublishedChapter(500);
            verify(continueReadingQueryPort).findPreviousPublishedChapter(32);
        }

        @Test
        @DisplayName("Returns Optional.empty() when structural chapter number cannot be resolved (row deleted)")
        void shouldReturnEmptyWhenStructuralNumberNotFound() {
            UserReadingProgress progress = UserReadingProgress.rehydrate(
                    PROGRESS_ID, USER_ID, CHAPTER_32_ID, 32, T1, T1
            );

            when(readingProgressRepositoryPort.findByUserId(USER_ID))
                    .thenReturn(Optional.of(progress));
            when(continueReadingQueryPort.findPublishedChapterById(CHAPTER_32_ID))
                    .thenReturn(Optional.empty());
            when(continueReadingQueryPort.findChapterNumberById(CHAPTER_32_ID))
                    .thenReturn(Optional.empty());

            Optional<ReaderContinueReadingDTO> result = useCase.execute(USER_ID);

            assertThat(result).isEmpty();
            verify(continueReadingQueryPort, never()).findPreviousPublishedChapter(anyInt());
            verify(continueReadingQueryPort, never()).findNextPublishedChapter(anyInt());
        }
    }
}
