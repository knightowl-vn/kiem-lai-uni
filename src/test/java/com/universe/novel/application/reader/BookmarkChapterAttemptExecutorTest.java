package com.universe.novel.application.reader;

import com.universe.novel.application.exceptions.BookmarkLimitExceededException;
import com.universe.novel.application.exceptions.DuplicateChapterBookmarkException;
import com.universe.novel.application.ports.ChapterBookmarkRepositoryPort;
import com.universe.novel.domain.reader.UserChapterBookmark;
import com.universe.shared.id.IdGeneratorPort;
import com.universe.shared.time.ClockPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BookmarkChapterAttemptExecutorTest {

    private static final UUID USER_ID =
            UUID.fromString("11111111-1111-1111-1111-111111111111");

    private static final UUID CHAPTER_ID =
            UUID.fromString("22222222-2222-2222-2222-222222222222");

    private static final UUID BOOKMARK_ID =
            UUID.fromString("33333333-3333-3333-3333-333333333333");

    private static final Instant NOW =
            Instant.parse("2026-08-25T10:30:00Z");

    @Mock
    private ChapterBookmarkRepositoryPort chapterBookmarkRepositoryPort;

    @Mock
    private IdGeneratorPort idGeneratorPort;

    @Mock
    private ClockPort clockPort;

    private BookmarkChapterAttemptExecutor attemptExecutor;

    @BeforeEach
    void setUp() {
        attemptExecutor = new BookmarkChapterAttemptExecutor(
                chapterBookmarkRepositoryPort,
                idGeneratorPort,
                clockPort
        );
    }

    @Test
    @DisplayName("Tạo và lưu bookmark mới khi count < 100 và chưa được bookmark")
    void shouldSaveBookmarkWhenCountIsUnder100() {
        when(chapterBookmarkRepositoryPort.countByUserIdForUpdate(USER_ID)).thenReturn(99L);
        when(chapterBookmarkRepositoryPort.existsByUserIdAndChapterIdForUpdate(USER_ID, CHAPTER_ID)).thenReturn(false);
        when(idGeneratorPort.generate()).thenReturn(BOOKMARK_ID);
        when(clockPort.now()).thenReturn(NOW);

        attemptExecutor.executeAttempt(USER_ID, CHAPTER_ID);

        ArgumentCaptor<UserChapterBookmark> captor = ArgumentCaptor.forClass(UserChapterBookmark.class);
        verify(chapterBookmarkRepositoryPort).save(captor.capture());

        UserChapterBookmark saved = captor.getValue();
        assertThat(saved.getId()).isEqualTo(BOOKMARK_ID);
        assertThat(saved.getUserId()).isEqualTo(USER_ID);
        assertThat(saved.getChapterId()).isEqualTo(CHAPTER_ID);
        assertThat(saved.getCreatedAt()).isEqualTo(NOW);
    }

    @Test
    @DisplayName("Thành công idempotent ngay cả khi count = 100 nếu chapter đã được bookmark (post-lock check)")
    void shouldReturnIdempotentlyWhenAlreadyBookmarkedUnderLock() {
        when(chapterBookmarkRepositoryPort.countByUserIdForUpdate(USER_ID)).thenReturn(100L);
        when(chapterBookmarkRepositoryPort.existsByUserIdAndChapterIdForUpdate(USER_ID, CHAPTER_ID)).thenReturn(true);

        attemptExecutor.executeAttempt(USER_ID, CHAPTER_ID);

        verify(idGeneratorPort, never()).generate();
        verify(clockPort, never()).now();
        verify(chapterBookmarkRepositoryPort, never()).save(any());
    }

    @Test
    @DisplayName("Ném BookmarkLimitExceededException khi count >= 100 và chapter chưa được bookmark")
    void shouldThrowBookmarkLimitExceededExceptionWhenLimitReached() {
        when(chapterBookmarkRepositoryPort.countByUserIdForUpdate(USER_ID)).thenReturn(100L);
        when(chapterBookmarkRepositoryPort.existsByUserIdAndChapterIdForUpdate(USER_ID, CHAPTER_ID)).thenReturn(false);

        assertThatThrownBy(() -> attemptExecutor.executeAttempt(USER_ID, CHAPTER_ID))
                .isInstanceOf(BookmarkLimitExceededException.class)
                .hasMessageContaining(USER_ID.toString())
                .hasMessageContaining("100");

        verify(chapterBookmarkRepositoryPort, never()).save(any());
    }

    @Test
    @DisplayName("Để DuplicateChapterBookmarkException lan truyền ra ngoài nhằm rollback transaction sạch sẽ")
    void shouldPropagateDuplicateChapterBookmarkException() {
        when(chapterBookmarkRepositoryPort.countByUserIdForUpdate(USER_ID)).thenReturn(50L);
        when(chapterBookmarkRepositoryPort.existsByUserIdAndChapterIdForUpdate(USER_ID, CHAPTER_ID)).thenReturn(false);
        when(idGeneratorPort.generate()).thenReturn(BOOKMARK_ID);
        when(clockPort.now()).thenReturn(NOW);
        doThrow(new DuplicateChapterBookmarkException(USER_ID, CHAPTER_ID))
                .when(chapterBookmarkRepositoryPort).save(any());

        assertThatThrownBy(() -> attemptExecutor.executeAttempt(USER_ID, CHAPTER_ID))
                .isInstanceOf(DuplicateChapterBookmarkException.class);
    }

    @Test
    @DisplayName("Từ chối tham số null")
    void shouldRejectNullArguments() {
        assertThatThrownBy(() -> attemptExecutor.executeAttempt(null, CHAPTER_ID))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> attemptExecutor.executeAttempt(USER_ID, null))
                .isInstanceOf(NullPointerException.class);
    }
}
