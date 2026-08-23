package com.universe.novel.application.reader;

import com.universe.novel.application.chapter.render.NovelMarkdownRenderer;
import com.universe.novel.application.exceptions.ChapterNotFoundException;
import com.universe.novel.application.ports.ReaderChapterDetailQueryPort;
import com.universe.novel.application.ports.ReaderChapterDetailQueryPort.ReaderChapterRecord;
import com.universe.novel.contracts.dto.reader.ReaderChapterDetailDTO;
import com.universe.novel.contracts.dto.reader.ReaderChapterNavigationDTO;
import com.universe.novel.contracts.dto.reader.ReaderChapterTocItemDTO;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GetReaderChapterDetailUseCaseTest {

    private static final UUID CHAPTER_ID =
            UUID.fromString("11111111-1111-1111-1111-111111111111");

    private static final UUID VOLUME_ID =
            UUID.fromString("22222222-2222-2222-2222-222222222222");

    @Mock
    private ReaderChapterDetailQueryPort
            queryPort;

    @Mock
    private NovelMarkdownRenderer
            markdownRenderer;

    private GetReaderChapterDetailUseCase
            useCase;

    @BeforeEach
    void setUp() {
        useCase = new GetReaderChapterDetailUseCase(
                queryPort,
                markdownRenderer
        );
    }

    @Test
    @DisplayName("Lấy chi tiết Chapter PUBLISHED thành công với đầy đủ nội dung HTML, navigation và Table of Contents")
    void shouldReturnChapterDetailSuccessfullyWithToc() {
        String slug = "chuong-2-can-duyen";
        String rawMarkdown = "## Nội dung chương 2\n\nTrần Bình An đứng bên bờ suối.";
        String renderedHtml = "<h2>Nội dung chương 2</h2>\n<p>Trần Bình An đứng bên bờ suối.</p>";

        ReaderChapterRecord record = new ReaderChapterRecord(
                CHAPTER_ID,
                VOLUME_ID,
                2,
                "Căn Duyên",
                slug,
                rawMarkdown,
                "Quyển Một - Lung Trung Tước",
                "quyen-1-lung-trung-tuoc",
                1
        );

        ReaderChapterNavigationDTO prev = new ReaderChapterNavigationDTO(
                1,
                "Khởi Đầu",
                "chuong-1-khoi-dau"
        );

        ReaderChapterNavigationDTO next = new ReaderChapterNavigationDTO(
                5,
                "Họa Phúc",
                "chuong-5-hoa-phuc"
        );

        List<ReaderChapterTocItemDTO> toc = List.of(
                new ReaderChapterTocItemDTO(1, "Khởi Đầu", "chuong-1-khoi-dau"),
                new ReaderChapterTocItemDTO(2, "Căn Duyên", "chuong-2-can-duyen"),
                new ReaderChapterTocItemDTO(5, "Họa Phúc", "chuong-5-hoa-phuc")
        );

        when(queryPort.findPublishedChapterBySlug("chuong-2-can-duyen"))
                .thenReturn(Optional.of(record));
        when(markdownRenderer.renderToHtml(rawMarkdown))
                .thenReturn(renderedHtml);
        when(queryPort.findPreviousPublishedChapter(2))
                .thenReturn(Optional.of(prev));
        when(queryPort.findNextPublishedChapter(2))
                .thenReturn(Optional.of(next));
        when(queryPort.findAllPublishedChaptersForToc())
                .thenReturn(toc);

        ReaderChapterDetailDTO result = useCase.execute(slug);

        assertThat(result).isNotNull();
        assertThat(result.id()).isEqualTo(CHAPTER_ID);
        assertThat(result.chapterNumber()).isEqualTo(2);
        assertThat(result.title()).isEqualTo("Căn Duyên");
        assertThat(result.slug()).isEqualTo(slug);
        assertThat(result.contentHtml()).isEqualTo(renderedHtml);

        assertThat(result.volume()).isNotNull();
        assertThat(result.volume().id()).isEqualTo(VOLUME_ID);
        assertThat(result.volume().title()).isEqualTo("Quyển Một - Lung Trung Tước");
        assertThat(result.volume().slug()).isEqualTo("quyen-1-lung-trung-tuoc");
        assertThat(result.volume().sortOrder()).isEqualTo(1);

        assertThat(result.previousChapter()).isEqualTo(prev);
        assertThat(result.nextChapter()).isEqualTo(next);

        assertThat(result.tableOfContents()).isNotNull();
        assertThat(result.tableOfContents()).hasSize(3);
        assertThat(result.tableOfContents()).containsExactlyElementsOf(toc);

        verify(queryPort).findPublishedChapterBySlug("chuong-2-can-duyen");
        verify(markdownRenderer).renderToHtml(rawMarkdown);
        verify(queryPort).findPreviousPublishedChapter(2);
        verify(queryPort).findNextPublishedChapter(2);
        verify(queryPort).findAllPublishedChaptersForToc();
    }

    @Test
    @DisplayName("Chương đầu tiên không có previousChapter (null)")
    void shouldHandleFirstChapterWithoutPrevious() {
        String slug = "chuong-1-khoi-dau";
        String rawMarkdown = "Chương mở đầu.";

        ReaderChapterRecord record = new ReaderChapterRecord(
                CHAPTER_ID,
                VOLUME_ID,
                1,
                "Khởi Đầu",
                slug,
                rawMarkdown,
                "Quyển Một",
                "quyen-1",
                1
        );

        ReaderChapterNavigationDTO next = new ReaderChapterNavigationDTO(
                2,
                "Căn Duyên",
                "chuong-2-can-duyen"
        );

        when(queryPort.findPublishedChapterBySlug("chuong-1-khoi-dau"))
                .thenReturn(Optional.of(record));
        when(markdownRenderer.renderToHtml(rawMarkdown))
                .thenReturn("<p>Chương mở đầu.</p>");
        when(queryPort.findPreviousPublishedChapter(1))
                .thenReturn(Optional.empty());
        when(queryPort.findNextPublishedChapter(1))
                .thenReturn(Optional.of(next));
        when(queryPort.findAllPublishedChaptersForToc())
                .thenReturn(List.of(new ReaderChapterTocItemDTO(1, "Khởi Đầu", "chuong-1-khoi-dau")));

        ReaderChapterDetailDTO result = useCase.execute(slug);

        assertThat(result.previousChapter()).isNull();
        assertThat(result.nextChapter()).isEqualTo(next);
        assertThat(result.tableOfContents()).hasSize(1);
    }

    @Test
    @DisplayName("Chương mới nhất không có nextChapter (null)")
    void shouldHandleLatestChapterWithoutNext() {
        String slug = "chuong-100-ket-thuc";
        String rawMarkdown = "Chương cuối cùng hiện tại.";

        ReaderChapterRecord record = new ReaderChapterRecord(
                CHAPTER_ID,
                VOLUME_ID,
                100,
                "Kết Thúc",
                slug,
                rawMarkdown,
                "Quyển Hai",
                "quyen-2",
                2
        );

        ReaderChapterNavigationDTO prev = new ReaderChapterNavigationDTO(
                99,
                "Áp Chót",
                "chuong-99-ap-chot"
        );

        when(queryPort.findPublishedChapterBySlug("chuong-100-ket-thuc"))
                .thenReturn(Optional.of(record));
        when(markdownRenderer.renderToHtml(rawMarkdown))
                .thenReturn("<p>Chương cuối cùng hiện tại.</p>");
        when(queryPort.findPreviousPublishedChapter(100))
                .thenReturn(Optional.of(prev));
        when(queryPort.findNextPublishedChapter(100))
                .thenReturn(Optional.empty());
        when(queryPort.findAllPublishedChaptersForToc())
                .thenReturn(List.of(
                        new ReaderChapterTocItemDTO(99, "Áp Chót", "chuong-99-ap-chot"),
                        new ReaderChapterTocItemDTO(100, "Kết Thúc", "chuong-100-ket-thuc")
                ));

        ReaderChapterDetailDTO result = useCase.execute(slug);

        assertThat(result.previousChapter()).isEqualTo(prev);
        assertThat(result.nextChapter()).isNull();
        assertThat(result.tableOfContents()).hasSize(2);
    }

    @Test
    @DisplayName("Ném ChapterNotFoundException khi không tìm thấy Chapter hoặc Chapter/Volume chưa PUBLISHED")
    void shouldThrowChapterNotFoundExceptionWhenChapterNotPublishedOrNotFound() {
        when(queryPort.findPublishedChapterBySlug("chuong-chua-xuat-ban"))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> useCase.execute("chuong-chua-xuat-ban"))
                .isInstanceOf(ChapterNotFoundException.class);

        verifyNoInteractions(markdownRenderer);
    }

    @Test
    @DisplayName("Ném ChapterNotFoundException khi slug là null hoặc rỗng")
    void shouldThrowChapterNotFoundExceptionWhenSlugIsNullOrBlank() {
        assertThatThrownBy(() -> useCase.execute(null))
                .isInstanceOf(ChapterNotFoundException.class);

        assertThatThrownBy(() -> useCase.execute("   "))
                .isInstanceOf(ChapterNotFoundException.class);

        verifyNoInteractions(queryPort);
        verifyNoInteractions(markdownRenderer);
    }
}
