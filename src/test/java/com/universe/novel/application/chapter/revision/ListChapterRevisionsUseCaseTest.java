package com.universe.novel.application.chapter.revision;

import com.universe.novel.application.exceptions.ChapterNotFoundException;
import com.universe.novel.application.ports.ChapterRepositoryPort;
import com.universe.novel.application.ports.ChapterRevisionQueryPort;
import com.universe.novel.contracts.dto.revision.ChapterRevisionListItemDTO;
import com.universe.novel.contracts.dto.revision.ChapterRevisionListPageDTO;
import com.universe.novel.domain.Chapter;
import com.universe.novel.domain.Slug;
import com.universe.novel.domain.revision.ChapterRevisionChangeType;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ListChapterRevisionsUseCaseTest {

    private static final UUID CHAPTER_ID =
            UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID VOLUME_ID =
            UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID ACTOR_ID =
            UUID.fromString("33333333-3333-3333-3333-333333333333");

    @Mock
    private ChapterRepositoryPort chapterRepositoryPort;

    @Mock
    private ChapterRevisionQueryPort chapterRevisionQueryPort;

    private ListChapterRevisionsUseCase useCase;

    @BeforeEach
    void setUp() {
        useCase = new ListChapterRevisionsUseCase(
                chapterRepositoryPort,
                chapterRevisionQueryPort
        );
    }

    @Test
    @DisplayName("Case A: Trả về danh sách revision khi Chapter tồn tại")
    void shouldReturnRevisionListPageWhenChapterExists() {
        Chapter chapter = createChapter();
        when(chapterRepositoryPort.findById(CHAPTER_ID)).thenReturn(Optional.of(chapter));

        ChapterRevisionListItemDTO item = new ChapterRevisionListItemDTO(
                UUID.randomUUID(),
                CHAPTER_ID,
                1L,
                1L,
                1,
                "Chương Một",
                com.universe.novel.domain.ChapterStatus.DRAFT,
                ChapterRevisionChangeType.CREATE_DRAFT,
                null,
                ACTOR_ID,
                Instant.now()
        );

        ChapterRevisionListPageDTO expectedPage = new ChapterRevisionListPageDTO(
                List.of(item),
                1,
                20,
                1L,
                1,
                false,
                false
        );

        when(chapterRevisionQueryPort.listRevisions(CHAPTER_ID, 1, 20))
                .thenReturn(expectedPage);

        ChapterRevisionListPageDTO result = useCase.execute(CHAPTER_ID, 1, 20);

        assertThat(result).isNotNull();
        assertThat(result.page()).isEqualTo(1);
        assertThat(result.size()).isEqualTo(20);
        assertThat(result.totalItems()).isEqualTo(1L);
        assertThat(result.items()).hasSize(1);
        assertThat(result.items().get(0).revisionNumber()).isEqualTo(1L);

        verify(chapterRepositoryPort).findById(CHAPTER_ID);
        verify(chapterRevisionQueryPort).listRevisions(CHAPTER_ID, 1, 20);
    }

    @Test
    @DisplayName("Case B: Ném ChapterNotFoundException khi Chapter không tồn tại và không gọi query port")
    void shouldThrowChapterNotFoundExceptionWhenChapterMissing() {
        when(chapterRepositoryPort.findById(CHAPTER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> useCase.execute(CHAPTER_ID, 1, 20))
                .isInstanceOf(ChapterNotFoundException.class)
                .hasMessage("Không tìm thấy chương: " + CHAPTER_ID);

        verify(chapterRepositoryPort).findById(CHAPTER_ID);
        verify(chapterRevisionQueryPort, never()).listRevisions(any(UUID.class), anyInt(), anyInt());
    }

    @Test
    @DisplayName("Case C: Kiểm tra tính hợp lệ của phân trang (page < 1, size < 1, size > 100)")
    void shouldValidatePaginationParameters() {
        assertThatThrownBy(() -> useCase.execute(CHAPTER_ID, 0, 20))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Số trang phải lớn hơn hoặc bằng 1.");

        assertThatThrownBy(() -> useCase.execute(CHAPTER_ID, 1, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Kích thước trang phải từ 1 đến 100.");

        assertThatThrownBy(() -> useCase.execute(CHAPTER_ID, 1, 101))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Kích thước trang phải từ 1 đến 100.");

        verify(chapterRepositoryPort, never()).findById(any(UUID.class));
        verify(chapterRevisionQueryPort, never()).listRevisions(any(UUID.class), anyInt(), anyInt());
    }

    @Test
    @DisplayName("Ném NullPointerException khi chapterId là null")
    void shouldThrowNullPointerExceptionWhenChapterIdIsNull() {
        assertThatThrownBy(() -> useCase.execute(null, 1, 20))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("Chapter ID không được để trống.");

        verify(chapterRepositoryPort, never()).findById(any(UUID.class));
        verify(chapterRevisionQueryPort, never()).listRevisions(any(UUID.class), anyInt(), anyInt());
    }

    private Chapter createChapter() {
        return Chapter.createDraft(
                CHAPTER_ID,
                VOLUME_ID,
                1,
                "Chương Một",
                new Slug("chuong-mot"),
                "Tóm tắt",
                "Nội dung",
                ACTOR_ID,
                Instant.now()
        );
    }
}
