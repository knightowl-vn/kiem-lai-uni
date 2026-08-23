package com.universe.novel.application.chapter;

import com.universe.novel.application.exceptions.ChapterCannotBeDeletedException;
import com.universe.novel.application.exceptions.ChapterNotFoundException;
import com.universe.novel.application.ports.ChapterRepositoryPort;
import com.universe.novel.application.ports.ChapterRevisionRepositoryPort;
import com.universe.novel.domain.Chapter;
import com.universe.novel.domain.ChapterStatus;
import com.universe.novel.domain.Slug;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.ConcurrentModificationException;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DeleteDraftChapterUseCaseTest {

    private static final UUID CHAPTER_ID =
            UUID.fromString(
                    "11111111-1111-1111-1111-111111111111"
            );

    private static final UUID VOLUME_ID =
            UUID.fromString(
                    "22222222-2222-2222-2222-222222222222"
            );

    private static final UUID ADMIN_ID =
            UUID.fromString(
                    "33333333-3333-3333-3333-333333333333"
            );

    private static final UUID OTHER_ADMIN_ID =
            UUID.fromString(
                    "44444444-4444-4444-4444-444444444444"
            );

    private static final Instant CREATED_AT =
            Instant.parse(
                    "2026-08-18T02:00:00Z"
            );

    @Mock
    private ChapterRepositoryPort
            chapterRepositoryPort;

    @Mock
    private ChapterRevisionRepositoryPort
            chapterRevisionRepositoryPort;

    private DeleteDraftChapterUseCase
            useCase;

    @BeforeEach
    void setUp() {
        useCase =
                new DeleteDraftChapterUseCase(
                        chapterRepositoryPort,
                        chapterRevisionRepositoryPort
                );
    }

    @Test
    @DisplayName(
            "Case A: Xóa Chapter DRAFT thuần post-V25 thành công khi canSafelyHardDelete trả về true"
    )
    void shouldDeleteDraftChapterWhenSafe() {
        Chapter chapter = createDraftChapter();

        when(chapterRepositoryPort.findById(CHAPTER_ID)).thenReturn(Optional.of(chapter));
        when(chapterRevisionRepositoryPort.canSafelyHardDelete(CHAPTER_ID)).thenReturn(true);

        useCase.execute(
                new DeleteDraftChapterCommand(
                        CHAPTER_ID,
                        OTHER_ADMIN_ID
                )
        );

        InOrder inOrder = Mockito.inOrder(chapterRevisionRepositoryPort, chapterRepositoryPort);
        inOrder.verify(chapterRevisionRepositoryPort).deleteAllByChapterId(CHAPTER_ID);
        inOrder.verify(chapterRepositoryPort).delete(chapter, 1L);
    }

    @Test
    @DisplayName(
            "Case B: Từ chối xóa Chapter có lịch sử BASELINE (canSafelyHardDelete = false)"
    )
    void shouldRejectDeleteWhenChapterHasBaselineHistory() {
        Chapter chapter = createDraftChapter();

        when(chapterRepositoryPort.findById(CHAPTER_ID)).thenReturn(Optional.of(chapter));
        when(chapterRevisionRepositoryPort.canSafelyHardDelete(CHAPTER_ID)).thenReturn(false);

        assertThatThrownBy(() -> useCase.execute(command()))
                .isInstanceOf(ChapterCannotBeDeletedException.class)
                .hasMessageContaining("không thể xóa vĩnh viễn");

        verify(chapterRevisionRepositoryPort, never()).deleteAllByChapterId(any(UUID.class));
        verify(chapterRepositoryPort, never()).delete(any(Chapter.class), anyLong());
    }

    @Test
    @DisplayName(
            "Case C: Từ chối xóa Chapter từng có lịch sử xuất bản (canSafelyHardDelete = false)"
    )
    void shouldRejectDeleteWhenChapterHasPublishHistory() {
        Chapter chapter = createDraftChapter();

        when(chapterRepositoryPort.findById(CHAPTER_ID)).thenReturn(Optional.of(chapter));
        when(chapterRevisionRepositoryPort.canSafelyHardDelete(CHAPTER_ID)).thenReturn(false);

        assertThatThrownBy(() -> useCase.execute(command()))
                .isInstanceOf(ChapterCannotBeDeletedException.class);

        verify(chapterRevisionRepositoryPort, never()).deleteAllByChapterId(any(UUID.class));
        verify(chapterRepositoryPort, never()).delete(any(Chapter.class), anyLong());
    }

    @Test
    @DisplayName(
            "Case D: Từ chối xóa Chapter khi không có lịch sử revision để chứng minh (canSafelyHardDelete = false)"
    )
    void shouldRejectDeleteWhenRevisionHistoryIsEmptyOrUnprovable() {
        Chapter chapter = createDraftChapter();

        when(chapterRepositoryPort.findById(CHAPTER_ID)).thenReturn(Optional.of(chapter));
        when(chapterRevisionRepositoryPort.canSafelyHardDelete(CHAPTER_ID)).thenReturn(false);

        assertThatThrownBy(() -> useCase.execute(command()))
                .isInstanceOf(ChapterCannotBeDeletedException.class);

        verify(chapterRevisionRepositoryPort, never()).deleteAllByChapterId(any(UUID.class));
        verify(chapterRepositoryPort, never()).delete(any(Chapter.class), anyLong());
    }

    @Test
    @DisplayName(
            "Case E: Từ chối xóa Chapter PUBLISHED và không gọi revision port"
    )
    void shouldRejectPublishedChapterBeforeRevisionCheck() {
        Chapter chapter = createDraftChapter();
        chapter.publish(ADMIN_ID, CREATED_AT.plusSeconds(60));

        when(chapterRepositoryPort.findById(CHAPTER_ID)).thenReturn(Optional.of(chapter));

        assertThatThrownBy(() -> useCase.execute(command()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Chỉ chương ở trạng thái DRAFT mới được xóa.");

        assertThat(chapter.getStatus()).isEqualTo(ChapterStatus.PUBLISHED);

        verify(chapterRevisionRepositoryPort, never()).canSafelyHardDelete(any(UUID.class));
        verify(chapterRevisionRepositoryPort, never()).deleteAllByChapterId(any(UUID.class));
        verify(chapterRepositoryPort, never()).delete(any(Chapter.class), anyLong());
    }

    @Test
    @DisplayName(
            "Case E (archived): Từ chối xóa Chapter ARCHIVED và không gọi revision port"
    )
    void shouldRejectArchivedChapterBeforeRevisionCheck() {
        Chapter chapter = createDraftChapter();
        chapter.archive(ADMIN_ID, CREATED_AT.plusSeconds(60));

        when(chapterRepositoryPort.findById(CHAPTER_ID)).thenReturn(Optional.of(chapter));

        assertThatThrownBy(() -> useCase.execute(command()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Chỉ chương ở trạng thái DRAFT mới được xóa.");

        verify(chapterRevisionRepositoryPort, never()).canSafelyHardDelete(any(UUID.class));
        verify(chapterRevisionRepositoryPort, never()).deleteAllByChapterId(any(UUID.class));
        verify(chapterRepositoryPort, never()).delete(any(Chapter.class), anyLong());
    }

    @Test
    @DisplayName(
            "Case F: Lỗi xung đột đồng thời hoặc lỗi DB khi xóa Chapter sau khi xóa revision được ném ra ngoài"
    )
    void shouldPropagateExceptionWhenChapterDeleteFailsAfterRevisionCleanup() {
        Chapter chapter = createDraftChapter();

        when(chapterRepositoryPort.findById(CHAPTER_ID)).thenReturn(Optional.of(chapter));
        when(chapterRevisionRepositoryPort.canSafelyHardDelete(CHAPTER_ID)).thenReturn(true);
        Mockito.doThrow(new ConcurrentModificationException("Optimistic lock conflict"))
                .when(chapterRepositoryPort).delete(chapter, 1L);

        assertThatThrownBy(() -> useCase.execute(command()))
                .isInstanceOf(ConcurrentModificationException.class)
                .hasMessage("Optimistic lock conflict");

        verify(chapterRevisionRepositoryPort).deleteAllByChapterId(CHAPTER_ID);
        verify(chapterRepositoryPort).delete(chapter, 1L);
    }

    @Test
    @DisplayName(
            "Case G: Lỗi khi xóa revision snapshot ngăn chặn việc gọi xóa Chapter"
    )
    void shouldNotCallChapterDeleteWhenRevisionCleanupFails() {
        Chapter chapter = createDraftChapter();

        when(chapterRepositoryPort.findById(CHAPTER_ID)).thenReturn(Optional.of(chapter));
        when(chapterRevisionRepositoryPort.canSafelyHardDelete(CHAPTER_ID)).thenReturn(true);
        Mockito.doThrow(new RuntimeException("DB revision cleanup error"))
                .when(chapterRevisionRepositoryPort).deleteAllByChapterId(CHAPTER_ID);

        assertThatThrownBy(() -> useCase.execute(command()))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("DB revision cleanup error");

        verify(chapterRevisionRepositoryPort).deleteAllByChapterId(CHAPTER_ID);
        verify(chapterRepositoryPort, never()).delete(any(Chapter.class), anyLong());
    }

    @Test
    @DisplayName(
            "Từ chối xóa Chapter không tồn tại"
    )
    void shouldRejectMissingChapter() {
        when(chapterRepositoryPort.findById(CHAPTER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> useCase.execute(command()))
                .isInstanceOf(ChapterNotFoundException.class)
                .hasMessage("Không tìm thấy chương: " + CHAPTER_ID);

        verify(chapterRevisionRepositoryPort, never()).canSafelyHardDelete(any(UUID.class));
        verify(chapterRevisionRepositoryPort, never()).deleteAllByChapterId(any(UUID.class));
        verify(chapterRepositoryPort, never()).delete(any(Chapter.class), anyLong());
    }

    @Test
    @DisplayName(
            "Từ chối DeleteDraftChapterCommand null"
    )
    void shouldRejectNullCommand() {
        assertThatThrownBy(() -> useCase.execute(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("Delete draft chapter command không được để trống.");

        verify(chapterRepositoryPort, never()).findById(any(UUID.class));
        verify(chapterRepositoryPort, never()).delete(any(Chapter.class), anyLong());
    }

    @Test
    @DisplayName(
            "Từ chối Actor ID null"
    )
    void shouldRejectNullActorId() {
        assertThatThrownBy(() -> useCase.execute(new DeleteDraftChapterCommand(CHAPTER_ID, null)))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("Actor ID không được để trống.");

        verify(chapterRepositoryPort, never()).findById(any(UUID.class));
        verify(chapterRepositoryPort, never()).delete(any(Chapter.class), anyLong());
    }

    private DeleteDraftChapterCommand command() {
        return new DeleteDraftChapterCommand(
                CHAPTER_ID,
                OTHER_ADMIN_ID
        );
    }

    private Chapter createDraftChapter() {
        return Chapter.createDraft(
                CHAPTER_ID,
                VOLUME_ID,
                1,
                "Chương Một",
                new Slug("quyen-1-chuong-1"),
                "Tóm tắt.",
                "Nội dung chương.",
                ADMIN_ID,
                CREATED_AT
        );
    }
}
