package com.universe.novel.application.chapter.revision;

import com.universe.novel.application.exceptions.ChapterNotFoundException;
import com.universe.novel.application.exceptions.ChapterRevisionAlreadyCurrentException;
import com.universe.novel.application.exceptions.ChapterRevisionNotFoundException;
import com.universe.novel.application.ports.ChapterRepositoryPort;
import com.universe.novel.application.ports.ChapterRevisionRepositoryPort;
import com.universe.novel.contracts.dto.ChapterDTO;
import com.universe.novel.domain.Chapter;
import com.universe.novel.domain.ChapterStatus;
import com.universe.novel.domain.Slug;
import com.universe.novel.domain.revision.ChapterRevision;
import com.universe.novel.domain.revision.ChapterRevisionChangeType;
import com.universe.shared.time.ClockPort;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import org.mockito.Mock;
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
class RestoreChapterRevisionUseCaseTest {

    private static final UUID CHAPTER_ID =
            UUID.fromString("11111111-1111-1111-1111-111111111111");

    private static final UUID VOLUME_ID =
            UUID.fromString("22222222-2222-2222-2222-222222222222");

    private static final UUID HISTORICAL_VOLUME_ID =
            UUID.fromString("33333333-3333-3333-3333-333333333333");

    private static final UUID ADMIN_ID =
            UUID.fromString("44444444-4444-4444-4444-444444444444");

    private static final Instant CREATED_AT =
            Instant.parse("2026-08-20T10:00:00Z");

    private static final Instant RESTORED_AT =
            Instant.parse("2026-08-20T12:00:00Z");

    @Mock
    private ChapterRepositoryPort chapterRepositoryPort;

    @Mock
    private ChapterRevisionRepositoryPort chapterRevisionRepositoryPort;

    @Mock
    private ChapterRevisionRecorder chapterRevisionRecorder;

    @Mock
    private ClockPort clockPort;

    private RestoreChapterRevisionUseCase useCase;

    @BeforeEach
    void setUp() {
        useCase = new RestoreChapterRevisionUseCase(
                chapterRepositoryPort,
                chapterRevisionRepositoryPort,
                chapterRevisionRecorder,
                clockPort
        );
    }

    @Test
    @DisplayName("Case A & H: Khôi phục thành công revision cho DRAFT, phục hồi title/summary/content, bảo toàn volumeId/chapterNumber/slug và ghi RESTORE_REVISION với default editSummary")
    void shouldRestoreHistoricalRevisionSuccessfully() {
        Chapter currentChapter = Chapter.createDraft(
                CHAPTER_ID,
                VOLUME_ID,
                5,
                "Tiêu đề hiện tại",
                new Slug("quyen-2-chuong-5"),
                "Tóm tắt hiện tại",
                "Nội dung hiện tại",
                ADMIN_ID,
                CREATED_AT
        );

        ChapterRevision sourceRevision = new ChapterRevision(
                UUID.randomUUID(),
                CHAPTER_ID,
                HISTORICAL_VOLUME_ID, // Historical volume is different
                1L,
                1L,
                2, // Historical chapterNumber is different
                "Tiêu đề lịch sử",
                new Slug("quyen-1-chuong-2"), // Historical slug is different
                "Tóm tắt lịch sử",
                "Nội dung lịch sử",
                ChapterStatus.PUBLISHED, // Historical status is PUBLISHED
                ChapterRevisionChangeType.PUBLISH,
                null,
                ADMIN_ID,
                CREATED_AT.minusSeconds(3600)
        );

        when(chapterRepositoryPort.findById(CHAPTER_ID)).thenReturn(Optional.of(currentChapter));
        when(chapterRevisionRepositoryPort.findByChapterIdAndRevisionNumber(CHAPTER_ID, 1L))
                .thenReturn(Optional.of(sourceRevision));
        when(clockPort.now()).thenReturn(RESTORED_AT);
        when(chapterRepositoryPort.save(currentChapter, 1L)).thenReturn(currentChapter);

        RestoreChapterRevisionCommand command = new RestoreChapterRevisionCommand(
                CHAPTER_ID,
                1L,
                1L,
                ADMIN_ID,
                null
        );

        ChapterDTO result = useCase.execute(command);

        // Restored fields
        assertThat(result.title()).isEqualTo("Tiêu đề lịch sử");
        assertThat(result.summary()).isEqualTo("Tóm tắt lịch sử");
        assertThat(result.content()).isEqualTo("Nội dung lịch sử");

        // Preserved fields
        assertThat(result.id()).isEqualTo(CHAPTER_ID);
        assertThat(result.volumeId()).isEqualTo(VOLUME_ID);
        assertThat(result.chapterNumber()).isEqualTo(5);
        assertThat(result.slug()).isEqualTo("quyen-2-chuong-5");
        assertThat(result.status()).isEqualTo("DRAFT");
        assertThat(result.aggregateVersion()).isEqualTo(2L);
        assertThat(result.contentVersion()).isEqualTo(2L);

        verify(chapterRepositoryPort).save(currentChapter, 1L);
        verify(chapterRevisionRecorder).record(
                currentChapter,
                ChapterRevisionChangeType.RESTORE_REVISION,
                ADMIN_ID,
                "Khôi phục từ phiên bản #1"
        );
    }

    @Test
    @DisplayName("Case I: Khôi phục thành công với custom editSummary")
    void shouldRestoreWithCustomEditSummary() {
        Chapter currentChapter = Chapter.createDraft(
                CHAPTER_ID,
                VOLUME_ID,
                1,
                "Tiêu đề hiện tại",
                new Slug("quyen-1-chuong-1"),
                "Tóm tắt hiện tại",
                "Nội dung hiện tại",
                ADMIN_ID,
                CREATED_AT
        );

        ChapterRevision sourceRevision = new ChapterRevision(
                UUID.randomUUID(),
                CHAPTER_ID,
                VOLUME_ID,
                1L,
                1L,
                1,
                "Tiêu đề cũ",
                new Slug("quyen-1-chuong-1"),
                "Tóm tắt cũ",
                "Nội dung cũ",
                ChapterStatus.DRAFT,
                ChapterRevisionChangeType.CREATE_DRAFT,
                null,
                ADMIN_ID,
                CREATED_AT
        );

        when(chapterRepositoryPort.findById(CHAPTER_ID)).thenReturn(Optional.of(currentChapter));
        when(chapterRevisionRepositoryPort.findByChapterIdAndRevisionNumber(CHAPTER_ID, 1L))
                .thenReturn(Optional.of(sourceRevision));
        when(clockPort.now()).thenReturn(RESTORED_AT);
        when(chapterRepositoryPort.save(currentChapter, 1L)).thenReturn(currentChapter);

        RestoreChapterRevisionCommand command = new RestoreChapterRevisionCommand(
                CHAPTER_ID,
                1L,
                1L,
                ADMIN_ID,
                "Hoàn tác thay đổi nháp do nhầm lẫn"
        );

        useCase.execute(command);

        verify(chapterRevisionRecorder).record(
                currentChapter,
                ChapterRevisionChangeType.RESTORE_REVISION,
                ADMIN_ID,
                "Hoàn tác thay đổi nháp do nhầm lẫn"
        );
    }

    @Test
    @DisplayName("Từ chối khi custom editSummary vượt quá 500 ký tự trước khi mutate và persist Chapter")
    void shouldRejectWhenCustomEditSummaryExceedsLimitBeforeMutationOrSave() {
        String overlyLongSummary = "A".repeat(501);

        RestoreChapterRevisionCommand command = new RestoreChapterRevisionCommand(
                CHAPTER_ID,
                1L,
                1L,
                ADMIN_ID,
                overlyLongSummary
        );

        assertThatThrownBy(() -> useCase.execute(command))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Mô tả chỉnh sửa không được vượt quá 500 ký tự.");

        verify(chapterRepositoryPort, never()).findById(any());
        verify(chapterRepositoryPort, never()).save(any(), anyLong());
        verify(chapterRevisionRecorder, never()).record(any(), any(), any(), any());
    }

    @Test
    @DisplayName("Case B: Ném ChapterRevisionNotFoundException khi source revision không tồn tại")
    void shouldThrowWhenSourceRevisionNotFound() {
        Chapter currentChapter = Chapter.createDraft(
                CHAPTER_ID,
                VOLUME_ID,
                1,
                "Tiêu đề",
                new Slug("chuong-1"),
                "Tóm tắt",
                "Nội dung",
                ADMIN_ID,
                CREATED_AT
        );

        when(chapterRepositoryPort.findById(CHAPTER_ID)).thenReturn(Optional.of(currentChapter));
        when(chapterRevisionRepositoryPort.findByChapterIdAndRevisionNumber(CHAPTER_ID, 99L))
                .thenReturn(Optional.empty());

        RestoreChapterRevisionCommand command = new RestoreChapterRevisionCommand(
                CHAPTER_ID,
                99L,
                1L,
                ADMIN_ID
        );

        assertThatThrownBy(() -> useCase.execute(command))
                .isInstanceOf(ChapterRevisionNotFoundException.class)
                .hasMessage("Không tìm thấy phiên bản 99 của chương: " + CHAPTER_ID);

        verify(chapterRepositoryPort, never()).save(any(Chapter.class), anyLong());
        verify(chapterRevisionRecorder, never()).record(any(), any(), any(), any());
    }

    @Test
    @DisplayName("Case C: Từ chối khôi phục khi Chapter ở trạng thái PUBLISHED")
    void shouldRejectRestoreWhenChapterIsPublished() {
        Chapter chapter = Chapter.createDraft(
                CHAPTER_ID,
                VOLUME_ID,
                1,
                "Tiêu đề",
                new Slug("chuong-1"),
                "Tóm tắt",
                "Nội dung",
                ADMIN_ID,
                CREATED_AT
        );
        chapter.publish(ADMIN_ID, CREATED_AT.plusSeconds(60));

        when(chapterRepositoryPort.findById(CHAPTER_ID)).thenReturn(Optional.of(chapter));

        RestoreChapterRevisionCommand command = new RestoreChapterRevisionCommand(
                CHAPTER_ID,
                1L,
                2L,
                ADMIN_ID
        );

        assertThatThrownBy(() -> useCase.execute(command))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Chỉ chương ở trạng thái DRAFT mới được khôi phục phiên bản lịch sử.");

        verify(chapterRevisionRepositoryPort, never())
                .findByChapterIdAndRevisionNumber(any(UUID.class), anyLong());
        verify(chapterRepositoryPort, never()).save(any(Chapter.class), anyLong());
        verify(chapterRevisionRecorder, never()).record(any(), any(), any(), any());
    }

    @Test
    @DisplayName("Case D: Từ chối khôi phục khi Chapter ở trạng thái ARCHIVED")
    void shouldRejectRestoreWhenChapterIsArchived() {
        Chapter chapter = Chapter.createDraft(
                CHAPTER_ID,
                VOLUME_ID,
                1,
                "Tiêu đề",
                new Slug("chuong-1"),
                "Tóm tắt",
                "Nội dung",
                ADMIN_ID,
                CREATED_AT
        );
        chapter.archive(ADMIN_ID, CREATED_AT.plusSeconds(60));

        when(chapterRepositoryPort.findById(CHAPTER_ID)).thenReturn(Optional.of(chapter));

        RestoreChapterRevisionCommand command = new RestoreChapterRevisionCommand(
                CHAPTER_ID,
                1L,
                2L,
                ADMIN_ID
        );

        assertThatThrownBy(() -> useCase.execute(command))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Chỉ chương ở trạng thái DRAFT mới được khôi phục phiên bản lịch sử.");

        verify(chapterRevisionRepositoryPort, never())
                .findByChapterIdAndRevisionNumber(any(UUID.class), anyLong());
        verify(chapterRepositoryPort, never()).save(any(Chapter.class), anyLong());
        verify(chapterRevisionRecorder, never()).record(any(), any(), any(), any());
    }

    @Test
    @DisplayName("Case E: Ném ChapterRevisionAlreadyCurrentException khi title, summary, content đều trùng khớp (no-op restore)")
    void shouldRejectNoOpRestoreWhenAllEditorialFieldsMatch() {
        Chapter currentChapter = Chapter.createDraft(
                CHAPTER_ID,
                VOLUME_ID,
                1,
                "Tiêu đề gốc",
                new Slug("chuong-1"),
                "Tóm tắt gốc",
                "Nội dung gốc",
                ADMIN_ID,
                CREATED_AT
        );

        ChapterRevision identicalRevision = new ChapterRevision(
                UUID.randomUUID(),
                CHAPTER_ID,
                VOLUME_ID,
                1L,
                1L,
                1,
                "Tiêu đề gốc",
                new Slug("chuong-1"),
                "Tóm tắt gốc",
                "Nội dung gốc",
                ChapterStatus.DRAFT,
                ChapterRevisionChangeType.CREATE_DRAFT,
                null,
                ADMIN_ID,
                CREATED_AT
        );

        when(chapterRepositoryPort.findById(CHAPTER_ID)).thenReturn(Optional.of(currentChapter));
        when(chapterRevisionRepositoryPort.findByChapterIdAndRevisionNumber(CHAPTER_ID, 1L))
                .thenReturn(Optional.of(identicalRevision));

        RestoreChapterRevisionCommand command = new RestoreChapterRevisionCommand(
                CHAPTER_ID,
                1L,
                1L,
                ADMIN_ID
        );

        assertThatThrownBy(() -> useCase.execute(command))
                .isInstanceOf(ChapterRevisionAlreadyCurrentException.class)
                .hasMessageContaining("đã hoàn toàn trùng khớp");

        verify(chapterRepositoryPort, never()).save(any(Chapter.class), anyLong());
        verify(chapterRevisionRecorder, never()).record(any(), any(), any(), any());
    }

    @Test
    @DisplayName("Case F: Lỗi optimistic concurrency hoặc save lỗi không ghi nhận RESTORE_REVISION")
    void shouldPropagateConcurrencyExceptionWithoutRecordingRevision() {
        Chapter currentChapter = Chapter.createDraft(
                CHAPTER_ID,
                VOLUME_ID,
                1,
                "Tiêu đề hiện tại",
                new Slug("chuong-1"),
                "Tóm tắt hiện tại",
                "Nội dung hiện tại",
                ADMIN_ID,
                CREATED_AT
        );

        ChapterRevision sourceRevision = new ChapterRevision(
                UUID.randomUUID(),
                CHAPTER_ID,
                VOLUME_ID,
                1L,
                1L,
                1,
                "Tiêu đề cũ",
                new Slug("chuong-1"),
                "Tóm tắt cũ",
                "Nội dung cũ",
                ChapterStatus.DRAFT,
                ChapterRevisionChangeType.CREATE_DRAFT,
                null,
                ADMIN_ID,
                CREATED_AT
        );

        when(chapterRepositoryPort.findById(CHAPTER_ID)).thenReturn(Optional.of(currentChapter));
        when(chapterRevisionRepositoryPort.findByChapterIdAndRevisionNumber(CHAPTER_ID, 1L))
                .thenReturn(Optional.of(sourceRevision));
        when(clockPort.now()).thenReturn(RESTORED_AT);
        when(chapterRepositoryPort.save(currentChapter, 1L))
                .thenThrow(new ConcurrentModificationException("Optimistic lock error"));

        RestoreChapterRevisionCommand command = new RestoreChapterRevisionCommand(
                CHAPTER_ID,
                1L,
                1L,
                ADMIN_ID
        );

        assertThatThrownBy(() -> useCase.execute(command))
                .isInstanceOf(ConcurrentModificationException.class)
                .hasMessage("Optimistic lock error");

        verify(chapterRevisionRecorder, never()).record(any(), any(), any(), any());
    }

    @Test
    @DisplayName("Case G: Revision thuộc Chapter khác không thể khôi phục")
    void shouldRejectRevisionBelongingToAnotherChapter() {
        Chapter currentChapter = Chapter.createDraft(
                CHAPTER_ID,
                VOLUME_ID,
                1,
                "Tiêu đề",
                new Slug("chuong-1"),
                "Tóm tắt",
                "Nội dung",
                ADMIN_ID,
                CREATED_AT
        );

        when(chapterRepositoryPort.findById(CHAPTER_ID)).thenReturn(Optional.of(currentChapter));
        when(chapterRevisionRepositoryPort.findByChapterIdAndRevisionNumber(CHAPTER_ID, 5L))
                .thenReturn(Optional.empty());

        RestoreChapterRevisionCommand command = new RestoreChapterRevisionCommand(
                CHAPTER_ID,
                5L,
                1L,
                ADMIN_ID
        );

        assertThatThrownBy(() -> useCase.execute(command))
                .isInstanceOf(ChapterRevisionNotFoundException.class);

        verify(chapterRepositoryPort, never()).save(any(Chapter.class), anyLong());
        verify(chapterRevisionRecorder, never()).record(any(), any(), any(), any());
    }

    @Test
    @DisplayName("Ném ChapterNotFoundException khi Chapter không tồn tại")
    void shouldThrowWhenChapterNotFound() {
        when(chapterRepositoryPort.findById(CHAPTER_ID)).thenReturn(Optional.empty());

        RestoreChapterRevisionCommand command = new RestoreChapterRevisionCommand(
                CHAPTER_ID,
                1L,
                1L,
                ADMIN_ID
        );

        assertThatThrownBy(() -> useCase.execute(command))
                .isInstanceOf(ChapterNotFoundException.class);

        verify(chapterRevisionRepositoryPort, never())
                .findByChapterIdAndRevisionNumber(any(UUID.class), anyLong());
    }

    @Test
    @DisplayName("Kiểm tra validation input")
    void shouldValidateCommandInputs() {
        assertThatThrownBy(() -> useCase.execute(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("Restore chapter revision command không được để trống.");

        assertThatThrownBy(() -> useCase.execute(new RestoreChapterRevisionCommand(null, 1L, 1L, ADMIN_ID)))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("Chapter ID không được để trống.");

        assertThatThrownBy(() -> useCase.execute(new RestoreChapterRevisionCommand(CHAPTER_ID, 0L, 1L, ADMIN_ID)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Source revision number phải lớn hơn hoặc bằng 1.");

        assertThatThrownBy(() -> useCase.execute(new RestoreChapterRevisionCommand(CHAPTER_ID, 1L, 1L, null)))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("Actor ID không được để trống.");
    }
}
