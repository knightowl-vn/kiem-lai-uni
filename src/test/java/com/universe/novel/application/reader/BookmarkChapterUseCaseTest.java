package com.universe.novel.application.reader;

import com.universe.novel.application.exceptions.BookmarkLimitExceededException;
import com.universe.novel.application.exceptions.ChapterNotFoundException;
import com.universe.novel.application.exceptions.DuplicateChapterBookmarkException;
import com.universe.novel.application.ports.ChapterBookmarkRepositoryPort;
import com.universe.novel.application.ports.ReaderChapterAccessQueryPort;
import com.universe.novel.application.ports.ReaderChapterAccessQueryPort.ReadableChapterReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataIntegrityViolationException;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BookmarkChapterUseCaseTest {

    private static final UUID USER_ID =
            UUID.fromString("11111111-1111-1111-1111-111111111111");

    private static final UUID CHAPTER_ID =
            UUID.fromString("22222222-2222-2222-2222-222222222222");

    @Mock
    private ReaderChapterAccessQueryPort readerChapterAccessQueryPort;

    @Mock
    private ChapterBookmarkRepositoryPort chapterBookmarkRepositoryPort;

    @Mock
    private BookmarkChapterAttemptExecutor attemptExecutor;

    private BookmarkChapterUseCase useCase;

    @BeforeEach
    void setUp() {
        useCase = new BookmarkChapterUseCase(
                readerChapterAccessQueryPort,
                chapterBookmarkRepositoryPort,
                attemptExecutor
        );
    }

    @Test
    @DisplayName("1. Readable Chapter: Đánh dấu chương công khai thành công qua attemptExecutor")
    void shouldCreateBookmarkWhenChapterIsPubliclyReadable() {
        when(readerChapterAccessQueryPort.findPublishedById(CHAPTER_ID))
                .thenReturn(Optional.of(new ReadableChapterReference(CHAPTER_ID, 1)));
        when(chapterBookmarkRepositoryPort.existsByUserIdAndChapterId(USER_ID, CHAPTER_ID))
                .thenReturn(false);

        BookmarkChapterCommand command = new BookmarkChapterCommand(USER_ID, CHAPTER_ID);
        useCase.execute(command);

        verify(attemptExecutor).executeAttempt(USER_ID, CHAPTER_ID);
    }

    @Test
    @DisplayName("2. Existing Bookmark (Fast-Path): Thành công idempotent ngay tại fast-path mà không gọi attemptExecutor")
    void shouldSucceedIdempotentlyWhenBookmarkAlreadyExists() {
        when(readerChapterAccessQueryPort.findPublishedById(CHAPTER_ID))
                .thenReturn(Optional.of(new ReadableChapterReference(CHAPTER_ID, 1)));
        when(chapterBookmarkRepositoryPort.existsByUserIdAndChapterId(USER_ID, CHAPTER_ID))
                .thenReturn(true);

        BookmarkChapterCommand command = new BookmarkChapterCommand(USER_ID, CHAPTER_ID);
        useCase.execute(command);

        verify(attemptExecutor, never()).executeAttempt(any(), any());
    }

    @Test
    @DisplayName("3. Concurrent Duplicate: Bắt DuplicateChapterBookmarkException từ attemptExecutor và xử lý như idempotent success")
    void shouldHandleConcurrentDuplicateBookmarkAsIdempotentSuccess() {
        when(readerChapterAccessQueryPort.findPublishedById(CHAPTER_ID))
                .thenReturn(Optional.of(new ReadableChapterReference(CHAPTER_ID, 1)));
        when(chapterBookmarkRepositoryPort.existsByUserIdAndChapterId(USER_ID, CHAPTER_ID))
                .thenReturn(false);
        doThrow(new DuplicateChapterBookmarkException(USER_ID, CHAPTER_ID))
                .when(attemptExecutor).executeAttempt(USER_ID, CHAPTER_ID);

        BookmarkChapterCommand command = new BookmarkChapterCommand(USER_ID, CHAPTER_ID);
        useCase.execute(command);

        verify(attemptExecutor, times(1)).executeAttempt(USER_ID, CHAPTER_ID);
    }

    @Test
    @DisplayName("4. Deadlock / Lock Retry: Thử lại thành công trong transaction mới khi attemptExecutor gặp transient ConcurrencyFailureException")
    void shouldRetryOnTransientConcurrencyFailureExceptionAndSucceed() {
        when(readerChapterAccessQueryPort.findPublishedById(CHAPTER_ID))
                .thenReturn(Optional.of(new ReadableChapterReference(CHAPTER_ID, 1)));
        when(chapterBookmarkRepositoryPort.existsByUserIdAndChapterId(USER_ID, CHAPTER_ID))
                .thenReturn(false);
        doThrow(new CannotAcquireLockException("Deadlock found"))
                .doNothing()
                .when(attemptExecutor).executeAttempt(USER_ID, CHAPTER_ID);

        BookmarkChapterCommand command = new BookmarkChapterCommand(USER_ID, CHAPTER_ID);
        useCase.execute(command);

        verify(attemptExecutor, times(2)).executeAttempt(USER_ID, CHAPTER_ID);
    }

    @Test
    @DisplayName("5. Max Attempts Exhausted: Ném ConcurrencyFailureException lên caller nếu cả 3 lần thử đều thất bại do tranh chấp")
    void shouldSurfaceExceptionWhenMaxAttemptsExhausted() {
        when(readerChapterAccessQueryPort.findPublishedById(CHAPTER_ID))
                .thenReturn(Optional.of(new ReadableChapterReference(CHAPTER_ID, 1)));
        when(chapterBookmarkRepositoryPort.existsByUserIdAndChapterId(USER_ID, CHAPTER_ID))
                .thenReturn(false);
        doThrow(new CannotAcquireLockException("Deadlock found"))
                .doThrow(new CannotAcquireLockException("Deadlock found"))
                .doThrow(new CannotAcquireLockException("Deadlock found"))
                .when(attemptExecutor).executeAttempt(USER_ID, CHAPTER_ID);

        BookmarkChapterCommand command = new BookmarkChapterCommand(USER_ID, CHAPTER_ID);

        assertThatThrownBy(() -> useCase.execute(command))
                .isInstanceOf(CannotAcquireLockException.class);

        verify(attemptExecutor, times(3)).executeAttempt(USER_ID, CHAPTER_ID);
    }

    @Test
    @DisplayName("6. Bookmark Limit Exceeded: Lan truyền trực tiếp BookmarkLimitExceededException mà không thử lại")
    void shouldPropagateBookmarkLimitExceededExceptionWithoutRetry() {
        when(readerChapterAccessQueryPort.findPublishedById(CHAPTER_ID))
                .thenReturn(Optional.of(new ReadableChapterReference(CHAPTER_ID, 1)));
        when(chapterBookmarkRepositoryPort.existsByUserIdAndChapterId(USER_ID, CHAPTER_ID))
                .thenReturn(false);
        doThrow(new BookmarkLimitExceededException(USER_ID, 100))
                .when(attemptExecutor).executeAttempt(USER_ID, CHAPTER_ID);

        BookmarkChapterCommand command = new BookmarkChapterCommand(USER_ID, CHAPTER_ID);

        assertThatThrownBy(() -> useCase.execute(command))
                .isInstanceOf(BookmarkLimitExceededException.class)
                .hasMessageContaining(USER_ID.toString())
                .hasMessageContaining("100");

        verify(attemptExecutor, times(1)).executeAttempt(USER_ID, CHAPTER_ID);
    }

    @Test
    @DisplayName("7. Unrelated Exception: Lan truyền lỗi không liên quan mà không thử lại")
    void shouldPropagateUnrelatedPersistenceException() {
        when(readerChapterAccessQueryPort.findPublishedById(CHAPTER_ID))
                .thenReturn(Optional.of(new ReadableChapterReference(CHAPTER_ID, 1)));
        when(chapterBookmarkRepositoryPort.existsByUserIdAndChapterId(USER_ID, CHAPTER_ID))
                .thenReturn(false);
        doThrow(new DataIntegrityViolationException("Database disk full"))
                .when(attemptExecutor).executeAttempt(USER_ID, CHAPTER_ID);

        BookmarkChapterCommand command = new BookmarkChapterCommand(USER_ID, CHAPTER_ID);

        assertThatThrownBy(() -> useCase.execute(command))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessage("Database disk full");

        verify(attemptExecutor, times(1)).executeAttempt(USER_ID, CHAPTER_ID);
    }

    @Test
    @DisplayName("8. Non-Public / Missing Chapter: Ném ChapterNotFoundException theo ngữ nghĩa public khi chương không công khai")
    void shouldRejectBookmarkWhenChapterDoesNotExistOrNotPubliclyReadable() {
        when(readerChapterAccessQueryPort.findPublishedById(CHAPTER_ID))
                .thenReturn(Optional.empty());

        BookmarkChapterCommand command = new BookmarkChapterCommand(USER_ID, CHAPTER_ID);

        assertThatThrownBy(() -> useCase.execute(command))
                .isInstanceOf(ChapterNotFoundException.class);

        verify(chapterBookmarkRepositoryPort, never()).existsByUserIdAndChapterId(any(), any());
        verifyNoInteractions(attemptExecutor);
    }

    @Test
    @DisplayName("9. Validation: Từ chối command null")
    void shouldRejectNullCommand() {
        assertThatThrownBy(() -> useCase.execute(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("BookmarkChapterCommand không được để trống.");
    }
}
