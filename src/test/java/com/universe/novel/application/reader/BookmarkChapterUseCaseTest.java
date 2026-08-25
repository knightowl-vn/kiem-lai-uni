package com.universe.novel.application.reader;

import com.universe.novel.application.exceptions.ChapterNotFoundException;
import com.universe.novel.application.exceptions.DuplicateChapterBookmarkException;
import com.universe.novel.application.ports.ChapterBookmarkRepositoryPort;
import com.universe.novel.application.ports.ReaderChapterAccessQueryPort;
import com.universe.novel.application.ports.ReaderChapterAccessQueryPort.ReadableChapterReference;
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
import org.springframework.dao.DataIntegrityViolationException;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BookmarkChapterUseCaseTest {

    private static final UUID USER_ID =
            UUID.fromString("11111111-1111-1111-1111-111111111111");

    private static final UUID CHAPTER_ID =
            UUID.fromString("22222222-2222-2222-2222-222222222222");

    private static final UUID BOOKMARK_ID =
            UUID.fromString("33333333-3333-3333-3333-333333333333");

    private static final Instant NOW =
            Instant.parse("2026-08-25T10:30:00Z");

    @Mock
    private ReaderChapterAccessQueryPort readerChapterAccessQueryPort;

    @Mock
    private ChapterBookmarkRepositoryPort chapterBookmarkRepositoryPort;

    @Mock
    private IdGeneratorPort idGeneratorPort;

    @Mock
    private ClockPort clockPort;

    private BookmarkChapterUseCase useCase;

    @BeforeEach
    void setUp() {
        useCase = new BookmarkChapterUseCase(
                readerChapterAccessQueryPort,
                chapterBookmarkRepositoryPort,
                idGeneratorPort,
                clockPort
        );
    }

    @Test
    @DisplayName("1. Readable Chapter: Đánh dấu chương công khai thành công với ID và thời gian chính xác")
    void shouldCreateBookmarkWhenChapterIsPubliclyReadable() {
        when(readerChapterAccessQueryPort.findPublishedById(CHAPTER_ID))
                .thenReturn(Optional.of(new ReadableChapterReference(CHAPTER_ID, 1)));
        when(chapterBookmarkRepositoryPort.existsByUserIdAndChapterId(USER_ID, CHAPTER_ID))
                .thenReturn(false);
        when(idGeneratorPort.generate())
                .thenReturn(BOOKMARK_ID);
        when(clockPort.now())
                .thenReturn(NOW);

        BookmarkChapterCommand command = new BookmarkChapterCommand(USER_ID, CHAPTER_ID);
        useCase.execute(command);

        ArgumentCaptor<UserChapterBookmark> captor = ArgumentCaptor.forClass(UserChapterBookmark.class);
        verify(chapterBookmarkRepositoryPort).save(captor.capture());

        UserChapterBookmark saved = captor.getValue();
        assertThat(saved.getId()).isEqualTo(BOOKMARK_ID);
        assertThat(saved.getUserId()).isEqualTo(USER_ID);
        assertThat(saved.getChapterId()).isEqualTo(CHAPTER_ID);
        assertThat(saved.getCreatedAt()).isEqualTo(NOW);
    }

    @Test
    @DisplayName("2. Existing Bookmark: Thành công idempotent mà không lưu duplicate khi bookmark đã tồn tại trước đó")
    void shouldSucceedIdempotentlyWhenBookmarkAlreadyExists() {
        when(readerChapterAccessQueryPort.findPublishedById(CHAPTER_ID))
                .thenReturn(Optional.of(new ReadableChapterReference(CHAPTER_ID, 1)));
        when(chapterBookmarkRepositoryPort.existsByUserIdAndChapterId(USER_ID, CHAPTER_ID))
                .thenReturn(true);

        BookmarkChapterCommand command = new BookmarkChapterCommand(USER_ID, CHAPTER_ID);
        useCase.execute(command);

        verify(idGeneratorPort, never()).generate();
        verify(clockPort, never()).now();
        verify(chapterBookmarkRepositoryPort, never()).save(any());
    }

    @Test
    @DisplayName("3. Concurrent Duplicate: Xử lý DuplicateChapterBookmarkException từ database như một hành động idempotent thành công")
    void shouldHandleConcurrentDuplicateBookmarkAsIdempotentSuccess() {
        when(readerChapterAccessQueryPort.findPublishedById(CHAPTER_ID))
                .thenReturn(Optional.of(new ReadableChapterReference(CHAPTER_ID, 1)));
        when(chapterBookmarkRepositoryPort.existsByUserIdAndChapterId(USER_ID, CHAPTER_ID))
                .thenReturn(false);
        when(idGeneratorPort.generate())
                .thenReturn(BOOKMARK_ID);
        when(clockPort.now())
                .thenReturn(NOW);
        doThrow(new DuplicateChapterBookmarkException(USER_ID, CHAPTER_ID))
                .when(chapterBookmarkRepositoryPort).save(any());

        BookmarkChapterCommand command = new BookmarkChapterCommand(USER_ID, CHAPTER_ID);
        useCase.execute(command);

        verify(chapterBookmarkRepositoryPort).save(any());
    }

    @Test
    @DisplayName("4. Unrelated Exception: Lan truyền lỗi cơ sở dữ liệu không liên quan (ví dụ: lỗi toàn vẹn khác)")
    void shouldPropagateUnrelatedPersistenceException() {
        when(readerChapterAccessQueryPort.findPublishedById(CHAPTER_ID))
                .thenReturn(Optional.of(new ReadableChapterReference(CHAPTER_ID, 1)));
        when(chapterBookmarkRepositoryPort.existsByUserIdAndChapterId(USER_ID, CHAPTER_ID))
                .thenReturn(false);
        when(idGeneratorPort.generate())
                .thenReturn(BOOKMARK_ID);
        when(clockPort.now())
                .thenReturn(NOW);
        doThrow(new DataIntegrityViolationException("Database disk full"))
                .when(chapterBookmarkRepositoryPort).save(any());

        BookmarkChapterCommand command = new BookmarkChapterCommand(USER_ID, CHAPTER_ID);

        assertThatThrownBy(() -> useCase.execute(command))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessage("Database disk full");
    }

    @Test
    @DisplayName("5. Non-Public / Missing Chapter: Ném ChapterNotFoundException theo ngữ nghĩa public khi chương không công khai")
    void shouldRejectBookmarkWhenChapterDoesNotExistOrNotPubliclyReadable() {
        when(readerChapterAccessQueryPort.findPublishedById(CHAPTER_ID))
                .thenReturn(Optional.empty());

        BookmarkChapterCommand command = new BookmarkChapterCommand(USER_ID, CHAPTER_ID);

        assertThatThrownBy(() -> useCase.execute(command))
                .isInstanceOf(ChapterNotFoundException.class);

        verify(chapterBookmarkRepositoryPort, never()).existsByUserIdAndChapterId(any(), any());
        verify(chapterBookmarkRepositoryPort, never()).save(any());
    }

    @Test
    @DisplayName("6. Validation: Từ chối command null")
    void shouldRejectNullCommand() {
        assertThatThrownBy(() -> useCase.execute(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("BookmarkChapterCommand không được để trống.");
    }
}
