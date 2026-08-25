package com.universe.novel.application.reader;

import com.universe.novel.application.ports.ChapterBookmarkRepositoryPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UnbookmarkChapterUseCaseTest {

    private static final UUID USER_ID =
            UUID.fromString("11111111-1111-1111-1111-111111111111");

    private static final UUID CHAPTER_ID =
            UUID.fromString("22222222-2222-2222-2222-222222222222");

    @Mock
    private ChapterBookmarkRepositoryPort chapterBookmarkRepositoryPort;

    private UnbookmarkChapterUseCase useCase;

    @BeforeEach
    void setUp() {
        useCase = new UnbookmarkChapterUseCase(chapterBookmarkRepositoryPort);
    }

    @Test
    @DisplayName("1. Existing Bookmark: Xóa dấu trang thành công theo đúng userId và chapterId")
    void shouldDeleteBookmarkSuccessfullyWhenExists() {
        when(chapterBookmarkRepositoryPort.deleteByUserIdAndChapterId(USER_ID, CHAPTER_ID))
                .thenReturn(1);

        UnbookmarkChapterCommand command = new UnbookmarkChapterCommand(USER_ID, CHAPTER_ID);
        useCase.execute(command);

        verify(chapterBookmarkRepositoryPort).deleteByUserIdAndChapterId(USER_ID, CHAPTER_ID);
    }

    @Test
    @DisplayName("2. Absent Bookmark: Hoàn thành thành công idempotent ngay cả khi bookmark không tồn tại")
    void shouldSucceedWhenBookmarkAlreadyAbsent() {
        when(chapterBookmarkRepositoryPort.deleteByUserIdAndChapterId(USER_ID, CHAPTER_ID))
                .thenReturn(0);

        UnbookmarkChapterCommand command = new UnbookmarkChapterCommand(USER_ID, CHAPTER_ID);
        useCase.execute(command);

        verify(chapterBookmarkRepositoryPort).deleteByUserIdAndChapterId(USER_ID, CHAPTER_ID);
    }

    @Test
    @DisplayName("3. Stale / Non-public Chapter: Cho phép xóa bookmark mà không cần kiểm tra tính công khai của chương")
    void shouldDeleteStaleBookmarkWithoutCheckingChapterReadability() {
        // UnbookmarkUseCase intentionally does not interact with ReaderChapterAccessQueryPort
        UnbookmarkChapterCommand command = new UnbookmarkChapterCommand(USER_ID, CHAPTER_ID);
        useCase.execute(command);

        verify(chapterBookmarkRepositoryPort).deleteByUserIdAndChapterId(USER_ID, CHAPTER_ID);
    }

    @Test
    @DisplayName("4. Validation: Từ chối command null")
    void shouldRejectNullCommand() {
        assertThatThrownBy(() -> useCase.execute(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("UnbookmarkChapterCommand không được để trống.");
    }
}
