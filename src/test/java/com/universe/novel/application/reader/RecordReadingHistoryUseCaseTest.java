package com.universe.novel.application.reader;

import com.universe.novel.application.exceptions.ChapterNotFoundException;
import com.universe.novel.application.exceptions.DuplicateReadingHistoryException;
import com.universe.novel.application.ports.ReaderChapterAccessQueryPort;
import com.universe.novel.application.ports.ReaderChapterAccessQueryPort.ReadableChapterReference;
import com.universe.novel.application.ports.ReadingHistoryRepositoryPort;
import com.universe.novel.domain.reader.UserChapterReadingHistory;
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
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RecordReadingHistoryUseCaseTest {

    private static final UUID USER_ID =
            UUID.fromString("11111111-1111-1111-1111-111111111111");

    private static final UUID CHAPTER_ID =
            UUID.fromString("22222222-2222-2222-2222-222222222222");

    private static final UUID GENERATED_ID =
            UUID.fromString("33333333-3333-3333-3333-333333333333");

    private static final Instant T0 =
            Instant.parse("2026-08-26T08:00:00Z");

    private static final Instant T1 =
            Instant.parse("2026-08-26T08:30:00Z");

    @Mock
    private ReaderChapterAccessQueryPort readerChapterAccessQueryPort;

    @Mock
    private ReadingHistoryRepositoryPort readingHistoryRepositoryPort;

    @Mock
    private IdGeneratorPort idGeneratorPort;

    @Mock
    private ClockPort clockPort;

    private RecordReadingHistoryUseCase useCase;

    @BeforeEach
    void setUp() {
        RecordReadingHistoryAttemptExecutor attemptExecutor = new RecordReadingHistoryAttemptExecutor(
                readerChapterAccessQueryPort,
                readingHistoryRepositoryPort,
                idGeneratorPort,
                clockPort
        );
        useCase = new RecordReadingHistoryUseCase(attemptExecutor);
    }

    @Nested
    @DisplayName("1. Initial History Creation & Retention Pruning")
    class InitialHistoryTests {

        @Test
        @DisplayName("Tạo mới bản ghi lịch sử khi chưa từng đọc chương và kích hoạt prune retention 50")
        void shouldCreateInitialHistoryWhenAbsentAndTriggerPrune() {
            when(readerChapterAccessQueryPort.findPublishedById(CHAPTER_ID))
                    .thenReturn(Optional.of(new ReadableChapterReference(CHAPTER_ID, 1)));
            when(clockPort.now()).thenReturn(T0);
            when(readingHistoryRepositoryPort.findByUserIdAndChapterId(USER_ID, CHAPTER_ID))
                    .thenReturn(Optional.empty());
            when(idGeneratorPort.generate()).thenReturn(GENERATED_ID);

            useCase.execute(new RecordReadingHistoryCommand(USER_ID, CHAPTER_ID));

            ArgumentCaptor<UserChapterReadingHistory> captor =
                    ArgumentCaptor.forClass(UserChapterReadingHistory.class);
            verify(readingHistoryRepositoryPort).save(captor.capture());
            verify(readingHistoryRepositoryPort).pruneOldestEntriesExceedingLimit(USER_ID, 50);

            UserChapterReadingHistory saved = captor.getValue();
            assertThat(saved.getId()).isEqualTo(GENERATED_ID);
            assertThat(saved.getUserId()).isEqualTo(USER_ID);
            assertThat(saved.getChapterId()).isEqualTo(CHAPTER_ID);
            assertThat(saved.getFirstReadAt()).isEqualTo(T0);
            assertThat(saved.getLastReadAt()).isEqualTo(T0);
        }
    }

    @Nested
    @DisplayName("2. Existing History Update")
    class ExistingHistoryTests {

        @Test
        @DisplayName("Cập nhật lastReadAt và bảo toàn firstReadAt khi đọc lại chương, không kích hoạt pruning")
        void shouldUpdateLastReadAtWhenAlreadyPresent() {
            UserChapterReadingHistory existing = UserChapterReadingHistory.createInitial(
                    GENERATED_ID,
                    USER_ID,
                    CHAPTER_ID,
                    T0
            );

            when(readerChapterAccessQueryPort.findPublishedById(CHAPTER_ID))
                    .thenReturn(Optional.of(new ReadableChapterReference(CHAPTER_ID, 1)));
            when(clockPort.now()).thenReturn(T1);
            when(readingHistoryRepositoryPort.findByUserIdAndChapterId(USER_ID, CHAPTER_ID))
                    .thenReturn(Optional.of(existing));

            useCase.execute(new RecordReadingHistoryCommand(USER_ID, CHAPTER_ID));

            verify(idGeneratorPort, never()).generate();
            verify(readingHistoryRepositoryPort).save(existing);
            verify(readingHistoryRepositoryPort, never()).pruneOldestEntriesExceedingLimit(any(), eq(50));
            assertThat(existing.getFirstReadAt()).isEqualTo(T0);
            assertThat(existing.getLastReadAt()).isEqualTo(T1);
        }
    }

    @Nested
    @DisplayName("3. Chapter Readability & Validation")
    class ChapterReadabilityTests {

        @Test
        @DisplayName("Ném ChapterNotFoundException khi chương không tồn tại hoặc chưa được publish")
        void shouldThrowChapterNotFoundExceptionWhenChapterNotPublished() {
            when(readerChapterAccessQueryPort.findPublishedById(CHAPTER_ID))
                    .thenReturn(Optional.empty());

            assertThatThrownBy(() -> useCase.execute(new RecordReadingHistoryCommand(USER_ID, CHAPTER_ID)))
                    .isInstanceOf(ChapterNotFoundException.class);

            verify(readingHistoryRepositoryPort, never()).findByUserIdAndChapterId(any(), any());
            verify(readingHistoryRepositoryPort, never()).save(any());
        }
    }

    @Nested
    @DisplayName("4. Concurrency Retry")
    class ConcurrencyRetryTests {

        @Test
        @DisplayName("Thử lại trong transaction độc lập mới khi gặp DuplicateReadingHistoryException và thành công")
        void shouldRetryOnDuplicateExceptionAndSucceed() {
            RecordReadingHistoryAttemptExecutor mockExecutor =
                    org.mockito.Mockito.mock(RecordReadingHistoryAttemptExecutor.class);
            RecordReadingHistoryUseCase retryUseCase = new RecordReadingHistoryUseCase(mockExecutor);

            doThrow(new DuplicateReadingHistoryException(USER_ID, CHAPTER_ID))
                    .doNothing()
                    .when(mockExecutor).executeAttempt(USER_ID, CHAPTER_ID);

            retryUseCase.execute(new RecordReadingHistoryCommand(USER_ID, CHAPTER_ID));

            verify(mockExecutor, times(2)).executeAttempt(USER_ID, CHAPTER_ID);
        }

        @Test
        @DisplayName("Thử lại trong transaction độc lập mới khi gặp transient lock/deadlock exception (CannotAcquireLockException) và thành công")
        void shouldRetryOnCannotAcquireLockExceptionAndSucceed() {
            RecordReadingHistoryAttemptExecutor mockExecutor =
                    org.mockito.Mockito.mock(RecordReadingHistoryAttemptExecutor.class);
            RecordReadingHistoryUseCase retryUseCase = new RecordReadingHistoryUseCase(mockExecutor);

            doThrow(new CannotAcquireLockException("Deadlock found"))
                    .doNothing()
                    .when(mockExecutor).executeAttempt(USER_ID, CHAPTER_ID);

            retryUseCase.execute(new RecordReadingHistoryCommand(USER_ID, CHAPTER_ID));

            verify(mockExecutor, times(2)).executeAttempt(USER_ID, CHAPTER_ID);
        }

        @Test
        @DisplayName("Ném exception lên caller nếu cả 3 lần thử đều thất bại do tranh chấp")
        void shouldSurfaceExceptionWhenMaxAttemptsExhausted() {
            RecordReadingHistoryAttemptExecutor mockExecutor =
                    org.mockito.Mockito.mock(RecordReadingHistoryAttemptExecutor.class);
            RecordReadingHistoryUseCase retryUseCase = new RecordReadingHistoryUseCase(mockExecutor);

            doThrow(new CannotAcquireLockException("Deadlock found"))
                    .doThrow(new CannotAcquireLockException("Deadlock found"))
                    .doThrow(new CannotAcquireLockException("Deadlock found"))
                    .when(mockExecutor).executeAttempt(USER_ID, CHAPTER_ID);

            assertThatThrownBy(() -> retryUseCase.execute(new RecordReadingHistoryCommand(USER_ID, CHAPTER_ID)))
                    .isInstanceOf(CannotAcquireLockException.class);

            verify(mockExecutor, times(3)).executeAttempt(USER_ID, CHAPTER_ID);
        }

        @Test
        @DisplayName("Không thử lại đối với các ngoại lệ toàn vẹn dữ liệu không phải tranh chấp khoá hay duplicate")
        void shouldNotRetryUnrelatedPersistenceException() {
            RecordReadingHistoryAttemptExecutor mockExecutor =
                    org.mockito.Mockito.mock(RecordReadingHistoryAttemptExecutor.class);
            RecordReadingHistoryUseCase retryUseCase = new RecordReadingHistoryUseCase(mockExecutor);

            doThrow(new DataIntegrityViolationException("Connection lost"))
                    .when(mockExecutor).executeAttempt(USER_ID, CHAPTER_ID);

            assertThatThrownBy(() -> retryUseCase.execute(new RecordReadingHistoryCommand(USER_ID, CHAPTER_ID)))
                    .isInstanceOf(DataIntegrityViolationException.class);

            verify(mockExecutor, times(1)).executeAttempt(USER_ID, CHAPTER_ID);
        }

        @Test
        @DisplayName("Lần thử lại tải bản ghi đã được commit bởi request cạnh tranh và cập nhật lastReadAt")
        void shouldReloadAndSaveCommittedRecordOnRetry() {
            ReadableChapterReference ref = new ReadableChapterReference(CHAPTER_ID, 1);
            when(readerChapterAccessQueryPort.findPublishedById(CHAPTER_ID)).thenReturn(Optional.of(ref));
            when(clockPort.now()).thenReturn(T0, T1);

            UserChapterReadingHistory concurrentCommittedRecord = UserChapterReadingHistory.createInitial(
                    GENERATED_ID, USER_ID, CHAPTER_ID, T0
            );

            // Attempt 1 finds empty, tries save and throws DuplicateReadingHistoryException
            // Attempt 2 (retry) finds concurrentCommittedRecord, updates lastReadAt to T1 and saves
            when(readingHistoryRepositoryPort.findByUserIdAndChapterId(USER_ID, CHAPTER_ID))
                    .thenReturn(Optional.empty()) // Attempt 1
                    .thenReturn(Optional.of(concurrentCommittedRecord)); // Attempt 2

            when(idGeneratorPort.generate()).thenReturn(GENERATED_ID);
            when(readingHistoryRepositoryPort.save(any(UserChapterReadingHistory.class)))
                    .thenThrow(new DuplicateReadingHistoryException(USER_ID, CHAPTER_ID)) // Attempt 1 fails
                    .thenReturn(concurrentCommittedRecord); // Attempt 2 succeeds

            useCase.execute(new RecordReadingHistoryCommand(USER_ID, CHAPTER_ID));

            assertThat(concurrentCommittedRecord.getFirstReadAt()).isEqualTo(T0);
            assertThat(concurrentCommittedRecord.getLastReadAt()).isEqualTo(T1);
            verify(readingHistoryRepositoryPort, times(2)).save(any());
        }
    }

    @Nested
    @DisplayName("5. Command Validation")
    class CommandValidationTests {

        @Test
        @DisplayName("Từ chối command là null")
        void shouldRejectNullCommand() {
            assertThatThrownBy(() -> useCase.execute(null))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("RecordReadingHistoryCommand không được để trống.");

            verifyNoInteractions(readerChapterAccessQueryPort);
            verifyNoInteractions(readingHistoryRepositoryPort);
        }

        @Test
        @DisplayName("Từ chối userId hoặc chapterId là null trong RecordReadingHistoryCommand")
        void shouldRejectNullFieldsInCommand() {
            assertThatThrownBy(() -> new RecordReadingHistoryCommand(null, CHAPTER_ID))
                    .isInstanceOf(NullPointerException.class);

            assertThatThrownBy(() -> new RecordReadingHistoryCommand(USER_ID, null))
                    .isInstanceOf(NullPointerException.class);
        }
    }
}
