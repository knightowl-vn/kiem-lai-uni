package com.universe.novel.application.chapter.revision;

import com.universe.novel.application.chapter.render.NovelMarkdownRenderer;
import com.universe.novel.application.exceptions.ChapterNotFoundException;
import com.universe.novel.application.exceptions.ChapterRevisionNotFoundException;
import com.universe.novel.application.ports.ChapterRepositoryPort;
import com.universe.novel.application.ports.ChapterRevisionQueryPort;
import com.universe.novel.contracts.dto.revision.ChapterRevisionDetailDTO;
import com.universe.novel.domain.Chapter;
import com.universe.novel.domain.ChapterStatus;
import com.universe.novel.domain.Slug;
import com.universe.novel.domain.revision.ChapterRevisionChangeType;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GetChapterRevisionDetailUseCaseTest {

    private static final UUID CHAPTER_ID =
            UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID OTHER_CHAPTER_ID =
            UUID.fromString("44444444-4444-4444-4444-444444444444");
    private static final UUID VOLUME_ID =
            UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID ACTOR_ID =
            UUID.fromString("33333333-3333-3333-3333-333333333333");

    @Mock
    private ChapterRepositoryPort chapterRepositoryPort;

    @Mock
    private ChapterRevisionQueryPort chapterRevisionQueryPort;

    @Mock
    private NovelMarkdownRenderer novelMarkdownRenderer;

    private GetChapterRevisionDetailUseCase useCase;

    @BeforeEach
    void setUp() {
        useCase = new GetChapterRevisionDetailUseCase(
                chapterRepositoryPort,
                chapterRevisionQueryPort,
                novelMarkdownRenderer
        );
    }

    @Test
    @DisplayName("Case D & E: Trả về revision detail và render Markdown sang HTML")
    void shouldReturnRevisionDetailWithRenderedHtmlWhenFound() {
        Chapter chapter = createChapter();
        when(chapterRepositoryPort.findById(CHAPTER_ID)).thenReturn(Optional.of(chapter));

        String rawMarkdown = "# Tiêu đề\n\nNội dung chương...";
        String renderedHtml = "<h1>Tiêu đề</h1>\n<p>Nội dung chương...</p>";

        ChapterRevisionDetailDTO rawDetail = new ChapterRevisionDetailDTO(
                UUID.randomUUID(),
                CHAPTER_ID,
                VOLUME_ID,
                2L,
                1L,
                1,
                "Chương Một",
                new Slug("chuong-mot"),
                "Tóm tắt",
                rawMarkdown,
                null,
                ChapterStatus.DRAFT,
                ChapterRevisionChangeType.UPDATE_DRAFT,
                "Chỉnh sửa nội dung",
                ACTOR_ID,
                Instant.now()
        );

        when(chapterRevisionQueryPort.getRevisionDetail(CHAPTER_ID, 2L))
                .thenReturn(Optional.of(rawDetail));
        when(novelMarkdownRenderer.renderToHtml(rawMarkdown))
                .thenReturn(renderedHtml);

        ChapterRevisionDetailDTO result = useCase.execute(CHAPTER_ID, 2L);

        assertThat(result).isNotNull();
        assertThat(result.chapterId()).isEqualTo(CHAPTER_ID);
        assertThat(result.revisionNumber()).isEqualTo(2L);
        assertThat(result.content()).isEqualTo(rawMarkdown);
        assertThat(result.contentHtml()).isEqualTo(renderedHtml);
        assertThat(result.changeType()).isEqualTo(ChapterRevisionChangeType.UPDATE_DRAFT);

        verify(chapterRepositoryPort).findById(CHAPTER_ID);
        verify(chapterRevisionQueryPort).getRevisionDetail(CHAPTER_ID, 2L);
        verify(novelMarkdownRenderer).renderToHtml(rawMarkdown);
    }

    @Test
    @DisplayName("Case F: Ném ChapterRevisionNotFoundException khi revision không tồn tại")
    void shouldThrowChapterRevisionNotFoundExceptionWhenRevisionMissing() {
        Chapter chapter = createChapter();
        when(chapterRepositoryPort.findById(CHAPTER_ID)).thenReturn(Optional.of(chapter));
        when(chapterRevisionQueryPort.getRevisionDetail(CHAPTER_ID, 99L))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> useCase.execute(CHAPTER_ID, 99L))
                .isInstanceOf(ChapterRevisionNotFoundException.class)
                .hasMessage("Không tìm thấy phiên bản 99 của chương: " + CHAPTER_ID);

        verify(chapterRepositoryPort).findById(CHAPTER_ID);
        verify(chapterRevisionQueryPort).getRevisionDetail(CHAPTER_ID, 99L);
        verify(novelMarkdownRenderer, never()).renderToHtml(anyString());
    }

    @Test
    @DisplayName("Case G: Revision thuộc về Chapter khác không thể truy cập qua chapterId yêu cầu")
    void shouldNotResolveRevisionBelongingToAnotherChapter() {
        Chapter chapter = createChapter();
        when(chapterRepositoryPort.findById(CHAPTER_ID)).thenReturn(Optional.of(chapter));
        when(chapterRevisionQueryPort.getRevisionDetail(CHAPTER_ID, 5L))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> useCase.execute(CHAPTER_ID, 5L))
                .isInstanceOf(ChapterRevisionNotFoundException.class);

        verify(chapterRevisionQueryPort).getRevisionDetail(CHAPTER_ID, 5L);
        verify(chapterRevisionQueryPort, never()).getRevisionDetail(OTHER_CHAPTER_ID, 5L);
        verify(novelMarkdownRenderer, never()).renderToHtml(anyString());
    }

    @Test
    @DisplayName("Case H: Ném ChapterNotFoundException khi Chapter không tồn tại và không gọi query port")
    void shouldThrowChapterNotFoundExceptionWhenChapterMissing() {
        when(chapterRepositoryPort.findById(CHAPTER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> useCase.execute(CHAPTER_ID, 1L))
                .isInstanceOf(ChapterNotFoundException.class)
                .hasMessage("Không tìm thấy chương: " + CHAPTER_ID);

        verify(chapterRepositoryPort).findById(CHAPTER_ID);
        verify(chapterRevisionQueryPort, never()).getRevisionDetail(any(UUID.class), anyLong());
        verify(novelMarkdownRenderer, never()).renderToHtml(anyString());
    }

    @Test
    @DisplayName("Ném IllegalArgumentException khi revisionNumber < 1")
    void shouldThrowIllegalArgumentExceptionWhenRevisionNumberIsInvalid() {
        assertThatThrownBy(() -> useCase.execute(CHAPTER_ID, 0L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Revision number phải lớn hơn hoặc bằng 1.");

        assertThatThrownBy(() -> useCase.execute(CHAPTER_ID, -1L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Revision number phải lớn hơn hoặc bằng 1.");

        verify(chapterRepositoryPort, never()).findById(any(UUID.class));
        verify(chapterRevisionQueryPort, never()).getRevisionDetail(any(UUID.class), anyLong());
        verify(novelMarkdownRenderer, never()).renderToHtml(anyString());
    }

    @Test
    @DisplayName("Ném NullPointerException khi chapterId là null")
    void shouldThrowNullPointerExceptionWhenChapterIdIsNull() {
        assertThatThrownBy(() -> useCase.execute(null, 1L))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("Chapter ID không được để trống.");

        verify(chapterRepositoryPort, never()).findById(any(UUID.class));
        verify(chapterRevisionQueryPort, never()).getRevisionDetail(any(UUID.class), anyLong());
        verify(novelMarkdownRenderer, never()).renderToHtml(anyString());
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
