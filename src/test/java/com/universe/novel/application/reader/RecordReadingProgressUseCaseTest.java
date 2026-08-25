package com.universe.novel.application.reader;

import com.universe.novel.application.exceptions.ChapterNotFoundException;
import com.universe.novel.application.exceptions.ReadingProgressConcurrencyException;
import com.universe.novel.application.ports.ReaderChapterAccessQueryPort;
import com.universe.novel.application.ports.ReaderChapterAccessQueryPort.ReadableChapterReference;
import com.universe.novel.application.ports.ReadingProgressRepositoryPort;
import com.universe.novel.domain.reader.UserReadingProgress;
import com.universe.shared.id.IdGeneratorPort;
import com.universe.shared.time.ClockPort;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RecordReadingProgressUseCaseTest {

    private static final UUID USER_ID =
            UUID.fromString("11111111-1111-1111-1111-111111111111");

    private static final UUID PROGRESS_ID =
            UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");

    private static final UUID CHAPTER_1_ID =
            UUID.fromString("22222222-2222-2222-2222-222222222222");

    private static final UUID CHAPTER_3_ID =
            UUID.fromString("33333333-3333-3333-3333-333333333333");

    private static final UUID CHAPTER_5_ID =
            UUID.fromString("55555555-5555-5555-5555-555555555555");

    private static final UUID CHAPTER_10_ID =
            UUID.fromString("10101010-1010-1010-1010-101010101010");

    private static final UUID CHAPTER_50_ID =
            UUID.fromString("50505050-5050-5050-5050-505050505050");

    private static final UUID CHAPTER_100_ID =
            UUID.fromString("99999999-9999-9999-9999-999999999999");

    private static final UUID CHAPTER_800_ID =
            UUID.fromString("88888888-8888-8888-8888-888888888888");

    private static final Instant T1 =
            Instant.parse("2026-08-25T08:00:00Z");

    private static final Instant T2 =
            Instant.parse("2026-08-25T08:30:00Z");

    @Mock
    private ReaderChapterAccessQueryPort readerChapterAccessQueryPort;

    @Mock
    private ReadingProgressRepositoryPort readingProgressRepositoryPort;

    @Mock
    private IdGeneratorPort idGeneratorPort;

    @Mock
    private ClockPort clockPort;

    private RecordReadingProgressAttemptExecutor attemptExecutor;

    private RecordReadingProgressUseCase useCase;

    @BeforeEach
    void setUp() {
        attemptExecutor = new RecordReadingProgressAttemptExecutor(
                readerChapterAccessQueryPort,
                readingProgressRepositoryPort,
                idGeneratorPort,
                clockPort
        );
        useCase = new RecordReadingProgressUseCase(attemptExecutor);
    }

    @Nested
    @DisplayName("1. Initial Progress Creation")
    class InitialProgressTests {

        @Test
        @DisplayName("First published Chapter creates initial progress when no progress exists")
        void shouldCreateInitialProgressForFirstPublishedChapter() {
            ReadableChapterReference ref1 = new ReadableChapterReference(CHAPTER_1_ID, 1);
            when(readerChapterAccessQueryPort.findPublishedById(CHAPTER_1_ID)).thenReturn(Optional.of(ref1));
            when(readingProgressRepositoryPort.findByUserId(USER_ID)).thenReturn(Optional.empty());
            when(idGeneratorPort.generate()).thenReturn(PROGRESS_ID);
            when(clockPort.now()).thenReturn(T1);

            useCase.execute(new RecordReadingProgressCommand(USER_ID, CHAPTER_1_ID));

            ArgumentCaptor<UserReadingProgress> captor = ArgumentCaptor.forClass(UserReadingProgress.class);
            verify(readingProgressRepositoryPort).save(captor.capture());

            UserReadingProgress saved = captor.getValue();
            assertThat(saved.getId()).isEqualTo(PROGRESS_ID);
            assertThat(saved.getUserId()).isEqualTo(USER_ID);
            assertThat(saved.getLastOpenedChapterId()).isEqualTo(CHAPTER_1_ID);
            assertThat(saved.getHighestReachedChapterNumber()).isEqualTo(1);
            assertThat(saved.getCreatedAt()).isEqualTo(T1);
            assertThat(saved.getUpdatedAt()).isEqualTo(T1);
        }
    }

    @Nested
    @DisplayName("2. Existing Progress Mutation Semantics")
    class ExistingProgressTests {

        @Test
        @DisplayName("Later Chapter increases highestReachedChapterNumber and updates lastOpenedChapterId")
        void shouldIncreaseHighestWhenReadingLaterChapter() {
            UserReadingProgress initialProgress = UserReadingProgress.rehydrate(
                    PROGRESS_ID, USER_ID, CHAPTER_1_ID, 1, T1, T1
            );
            ReadableChapterReference ref10 = new ReadableChapterReference(CHAPTER_10_ID, 10);

            when(readerChapterAccessQueryPort.findPublishedById(CHAPTER_10_ID)).thenReturn(Optional.of(ref10));
            when(readingProgressRepositoryPort.findByUserId(USER_ID)).thenReturn(Optional.of(initialProgress));
            when(clockPort.now()).thenReturn(T2);

            useCase.execute(new RecordReadingProgressCommand(USER_ID, CHAPTER_10_ID));

            ArgumentCaptor<UserReadingProgress> captor = ArgumentCaptor.forClass(UserReadingProgress.class);
            verify(readingProgressRepositoryPort).save(captor.capture());

            UserReadingProgress saved = captor.getValue();
            assertThat(saved.getLastOpenedChapterId()).isEqualTo(CHAPTER_10_ID);
            assertThat(saved.getHighestReachedChapterNumber()).isEqualTo(10);
            assertThat(saved.getUpdatedAt()).isEqualTo(T2);
        }

        @Test
        @DisplayName("Older Chapter changes last opened but preserves highest reached")
        void shouldChangeLastOpenedAndPreserveHighestWhenReadingOlderChapter() {
            UserReadingProgress initialProgress = UserReadingProgress.rehydrate(
                    PROGRESS_ID, USER_ID, CHAPTER_10_ID, 10, T1, T1
            );
            ReadableChapterReference ref3 = new ReadableChapterReference(CHAPTER_3_ID, 3);

            when(readerChapterAccessQueryPort.findPublishedById(CHAPTER_3_ID)).thenReturn(Optional.of(ref3));
            when(readingProgressRepositoryPort.findByUserId(USER_ID)).thenReturn(Optional.of(initialProgress));
            when(clockPort.now()).thenReturn(T2);

            useCase.execute(new RecordReadingProgressCommand(USER_ID, CHAPTER_3_ID));

            ArgumentCaptor<UserReadingProgress> captor = ArgumentCaptor.forClass(UserReadingProgress.class);
            verify(readingProgressRepositoryPort).save(captor.capture());

            UserReadingProgress saved = captor.getValue();
            assertThat(saved.getLastOpenedChapterId()).isEqualTo(CHAPTER_3_ID);
            assertThat(saved.getHighestReachedChapterNumber()).isEqualTo(10);
            assertThat(saved.getUpdatedAt()).isEqualTo(T2);
        }

        @Test
        @DisplayName("Same last Chapter causes no save call and returns cleanly")
        void shouldNotCallSaveWhenReadingSameLastChapter() {
            UserReadingProgress initialProgress = UserReadingProgress.rehydrate(
                    PROGRESS_ID, USER_ID, CHAPTER_5_ID, 10, T1, T1
            );
            ReadableChapterReference ref5 = new ReadableChapterReference(CHAPTER_5_ID, 5);

            when(readerChapterAccessQueryPort.findPublishedById(CHAPTER_5_ID)).thenReturn(Optional.of(ref5));
            when(readingProgressRepositoryPort.findByUserId(USER_ID)).thenReturn(Optional.of(initialProgress));
            when(clockPort.now()).thenReturn(T2);

            useCase.execute(new RecordReadingProgressCommand(USER_ID, CHAPTER_5_ID));

            verify(readingProgressRepositoryPort, never()).save(any());
        }

        @Test
        @DisplayName("Direct jump (e.g. Chapter 800) updates highest directly")
        void shouldUpdateHighestDirectlyWhenJumpingAhead() {
            UserReadingProgress initialProgress = UserReadingProgress.rehydrate(
                    PROGRESS_ID, USER_ID, CHAPTER_1_ID, 1, T1, T1
            );
            ReadableChapterReference ref800 = new ReadableChapterReference(CHAPTER_800_ID, 800);

            when(readerChapterAccessQueryPort.findPublishedById(CHAPTER_800_ID)).thenReturn(Optional.of(ref800));
            when(readingProgressRepositoryPort.findByUserId(USER_ID)).thenReturn(Optional.of(initialProgress));
            when(clockPort.now()).thenReturn(T2);

            useCase.execute(new RecordReadingProgressCommand(USER_ID, CHAPTER_800_ID));

            ArgumentCaptor<UserReadingProgress> captor = ArgumentCaptor.forClass(UserReadingProgress.class);
            verify(readingProgressRepositoryPort).save(captor.capture());

            UserReadingProgress saved = captor.getValue();
            assertThat(saved.getLastOpenedChapterId()).isEqualTo(CHAPTER_800_ID);
            assertThat(saved.getHighestReachedChapterNumber()).isEqualTo(800);
            assertThat(saved.getUpdatedAt()).isEqualTo(T2);
        }
    }

    @Nested
    @DisplayName("3. Chapter Readability & Validation")
    class ChapterReadabilityTests {

        @Test
        @DisplayName("Throws ChapterNotFoundException when Chapter ID does not exist or is not PUBLISHED")
        void shouldThrowChapterNotFoundExceptionWhenChapterNotFoundOrNotPublished() {
            UUID unknownOrDraftChapterId = UUID.randomUUID();
            when(readerChapterAccessQueryPort.findPublishedById(unknownOrDraftChapterId)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> useCase.execute(new RecordReadingProgressCommand(USER_ID, unknownOrDraftChapterId)))
                    .isInstanceOf(ChapterNotFoundException.class);

            verify(readingProgressRepositoryPort, never()).findByUserId(any());
            verify(readingProgressRepositoryPort, never()).save(any());
        }
    }

    @Nested
    @DisplayName("4. Concurrency Retry & Monotonicity")
    class ConcurrencyAndRetryTests {

        @Test
        @DisplayName("One concurrency failure retries exactly once and succeeds")
        void shouldRetryExactlyOnceOnConcurrencyExceptionAndSucceed() {
            RecordReadingProgressAttemptExecutor mockExecutor =
                    org.mockito.Mockito.mock(RecordReadingProgressAttemptExecutor.class);
            RecordReadingProgressUseCase retryUseCase = new RecordReadingProgressUseCase(mockExecutor);

            doThrow(new ReadingProgressConcurrencyException(USER_ID, new RuntimeException("Race conflict")))
                    .doNothing()
                    .when(mockExecutor).executeAttempt(USER_ID, CHAPTER_1_ID);

            retryUseCase.execute(new RecordReadingProgressCommand(USER_ID, CHAPTER_1_ID));

            verify(mockExecutor, times(2)).executeAttempt(USER_ID, CHAPTER_1_ID);
        }

        @Test
        @DisplayName("Second concurrency failure is surfaced to the caller")
        void shouldSurfaceExceptionWhenSecondAttemptAlsoFailsWithConcurrencyException() {
            RecordReadingProgressAttemptExecutor mockExecutor =
                    org.mockito.Mockito.mock(RecordReadingProgressAttemptExecutor.class);
            RecordReadingProgressUseCase retryUseCase = new RecordReadingProgressUseCase(mockExecutor);

            doThrow(new ReadingProgressConcurrencyException(USER_ID, new RuntimeException("Conflict 1")))
                    .doThrow(new ReadingProgressConcurrencyException(USER_ID, new RuntimeException("Conflict 2")))
                    .when(mockExecutor).executeAttempt(USER_ID, CHAPTER_1_ID);

            assertThatThrownBy(() -> retryUseCase.execute(new RecordReadingProgressCommand(USER_ID, CHAPTER_1_ID)))
                    .isInstanceOf(ReadingProgressConcurrencyException.class);

            verify(mockExecutor, times(2)).executeAttempt(USER_ID, CHAPTER_1_ID);
        }

        @Test
        @DisplayName("Unrelated persistence/data-integrity failure is not misclassified as retryable conflict")
        void shouldNotRetryWhenExceptionIsNotConcurrencyException() {
            RecordReadingProgressAttemptExecutor mockExecutor =
                    org.mockito.Mockito.mock(RecordReadingProgressAttemptExecutor.class);
            RecordReadingProgressUseCase retryUseCase = new RecordReadingProgressUseCase(mockExecutor);

            doThrow(new DataIntegrityViolationException("FK failure"))
                    .when(mockExecutor).executeAttempt(USER_ID, CHAPTER_1_ID);

            assertThatThrownBy(() -> retryUseCase.execute(new RecordReadingProgressCommand(USER_ID, CHAPTER_1_ID)))
                    .isInstanceOf(DataIntegrityViolationException.class);

            verify(mockExecutor, times(1)).executeAttempt(USER_ID, CHAPTER_1_ID);
        }

        @Test
        @DisplayName("Retry reloads newest progress and preserves monotonic highest under concurrent update")
        void shouldPreserveMonotonicityUnderRetryWhenConcurrentUpdateCommittedBeforeRetry() {
            // Scenario:
            // Initial: highest = 20
            // Request B wants to open Chapter 50.
            // Attempt 1: loads initial progress (highest=20). When saving, concurrency exception occurs because Request A committed Chapter 100 (highest=100).
            // Attempt 2 (Retry): reloads fresh progress (highest=100, lastOpened=100) and applies recordChapterAccess(50).
            // Final result: lastOpened=50, highest=100 (highest never decreases to 50).

            ReadableChapterReference ref50 = new ReadableChapterReference(CHAPTER_50_ID, 50);
            when(readerChapterAccessQueryPort.findPublishedById(CHAPTER_50_ID)).thenReturn(Optional.of(ref50));
            when(clockPort.now()).thenReturn(T1, T2);

            UserReadingProgress progressObservedInAttempt1 = UserReadingProgress.rehydrate(
                    PROGRESS_ID, USER_ID, CHAPTER_1_ID, 20, T1, T1
            );
            UserReadingProgress progressCommittedByConcurrentTx = UserReadingProgress.rehydrate(
                    PROGRESS_ID, USER_ID, CHAPTER_100_ID, 100, T1, T2
            );

            when(readingProgressRepositoryPort.findByUserId(USER_ID))
                    .thenReturn(Optional.of(progressObservedInAttempt1))  // Attempt 1 sees stale
                    .thenReturn(Optional.of(progressCommittedByConcurrentTx)); // Attempt 2 sees winner tx

            // Attempt 1 save throws concurrency exception, Attempt 2 save succeeds
            when(readingProgressRepositoryPort.save(progressObservedInAttempt1))
                    .thenThrow(new ReadingProgressConcurrencyException(USER_ID, new RuntimeException("Version mismatch")));
            when(readingProgressRepositoryPort.save(progressCommittedByConcurrentTx))
                    .thenReturn(progressCommittedByConcurrentTx);

            useCase.execute(new RecordReadingProgressCommand(USER_ID, CHAPTER_50_ID));

            // Verify Attempt 2 reloaded latest progress and applied chapter 50 correctly
            assertThat(progressCommittedByConcurrentTx.getLastOpenedChapterId()).isEqualTo(CHAPTER_50_ID);
            assertThat(progressCommittedByConcurrentTx.getHighestReachedChapterNumber()).isEqualTo(100);
            assertThat(progressCommittedByConcurrentTx.getUpdatedAt()).isEqualTo(T2);

            verify(readingProgressRepositoryPort, times(2)).findByUserId(USER_ID);
            verify(readingProgressRepositoryPort).save(progressObservedInAttempt1);
            verify(readingProgressRepositoryPort).save(progressCommittedByConcurrentTx);
        }
    }

    @Nested
    @DisplayName("5. Command Validation")
    class CommandValidationTests {

        @Test
        @DisplayName("Rejects null command")
        void shouldRejectNullCommand() {
            assertThatThrownBy(() -> useCase.execute(null))
                    .isInstanceOf(NullPointerException.class);

            verifyNoInteractions(readerChapterAccessQueryPort);
            verifyNoInteractions(readingProgressRepositoryPort);
        }

        @Test
        @DisplayName("Rejects null userId or null chapterId in RecordReadingProgressCommand")
        void shouldRejectNullFieldsInCommand() {
            assertThatThrownBy(() -> new RecordReadingProgressCommand(null, CHAPTER_1_ID))
                    .isInstanceOf(NullPointerException.class);

            assertThatThrownBy(() -> new RecordReadingProgressCommand(USER_ID, null))
                    .isInstanceOf(NullPointerException.class);
        }
    }
}
