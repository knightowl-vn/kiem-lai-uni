package com.universe.novel.application.reader;

import com.universe.novel.application.chapter.render.NovelMarkdownRenderer;
import com.universe.novel.application.narration.NarrationTextSegmenter;
import com.universe.novel.application.narration.NarrationTextSegmentPlan;
import com.universe.novel.application.ports.ChapterNarrationSegmentRepositoryPort;
import com.universe.novel.application.reader.render.ReaderNarrationMarkdownRenderer;
import com.universe.novel.domain.narration.ChapterNarrationSegment;
import com.universe.novel.domain.narration.ChapterNarrationSegmentStatus;
import com.universe.novel.domain.narration.NarrationTextSegment;
import java.time.Instant;
import java.util.Map;
import com.universe.novel.application.exceptions.ChapterNotFoundException;
import com.universe.novel.application.ports.ReaderChapterDetailQueryPort;
import com.universe.novel.application.ports.ReaderChapterDetailQueryPort.ReaderChapterRecord;
import com.universe.novel.contracts.dto.reader.ReaderChapterDetailDTO;
import com.universe.novel.contracts.dto.reader.ReaderChapterNavigationDTO;
import com.universe.novel.contracts.dto.reader.ReaderChapterTocItemDTO;
import com.universe.novel.application.ports.PublicReaderNavigationIndexCachePort;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.times;

@ExtendWith(MockitoExtension.class)
class GetReaderChapterDetailUseCaseTest {
	@Mock
	private NarrationTextSegmenter segmenter;
	@Mock
	private ChapterNarrationSegmentRepositoryPort segments;
	@Mock
	private ReaderNarrationMarkdownRenderer narrationRenderer;
	@Mock
	private PublicReaderNavigationIndexCachePort navigationCachePort;

	private static final UUID CHAPTER_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");

	private static final UUID VOLUME_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");

	@Mock
	private ReaderChapterDetailQueryPort queryPort;

	@Mock
	private NovelMarkdownRenderer markdownRenderer;

	private GetReaderChapterDetailUseCase useCase;

	@BeforeEach
	void setUp() {
		useCase = new GetReaderChapterDetailUseCase(queryPort, markdownRenderer, segmenter, segments,
				new ReaderNarrationBlockMappingResolver(), narrationRenderer, navigationCachePort);
	}

	private ReaderChapterRecord narrationRecord(String markdown) {
		return new ReaderChapterRecord(CHAPTER_ID, VOLUME_ID, 1, "Chapter", "chapter", markdown, "Volume", "volume", 1);
	}

	private ReaderChapterRecord narrationRecord(String markdown, int chapterNumber) {
		return new ReaderChapterRecord(CHAPTER_ID, VOLUME_ID, chapterNumber, "Chapter", "chapter", markdown, "Volume",
				"volume", 1);
	}

	@Test
	void annotatesExactCurrentSequenceUsingOneRawSnapshotWithoutWrites() {
		String markdown = "**Repeated** prose.";
		var segment = ChapterNarrationSegment.create(UUID.randomUUID(), CHAPTER_ID, 0, "Repeated prose.",
				Instant.EPOCH);
		var mapping = Map.of(0, List.of(segment.getId()));
		when(queryPort.findPublishedChapterBySlug("chapter")).thenReturn(Optional.of(narrationRecord(markdown, 1)));
		when(segments.findByChapterIdAndStatus(CHAPTER_ID, ChapterNarrationSegmentStatus.CURRENT))
				.thenReturn(List.of(segment));
		when(segmenter.plan(markdown)).thenReturn(
				List.of(new NarrationTextSegmentPlan(NarrationTextSegment.of(0, "Repeated prose."), List.of(0))));
		when(narrationRenderer.renderToHtml(markdown, mapping))
				.thenReturn("<p data-narration-segment-ids=\"" + segment.getId() + "\">Repeated prose.</p>");
		when(navigationCachePort.getOrLoad(any()))
				.thenReturn(List.of(new ReaderChapterTocItemDTO(1, "Title", "chapter")));

		assertThat(useCase.execute("chapter").contentHtml()).contains("data-narration-segment-ids");

		verify(queryPort).findPublishedChapterBySlug("chapter");
		verify(segmenter).plan(markdown);
		verify(narrationRenderer).renderToHtml(markdown, mapping);
		verify(segments).findByChapterIdAndStatus(CHAPTER_ID, ChapterNarrationSegmentStatus.CURRENT);
		verifyNoMoreInteractions(segments);
		verifyNoInteractions(markdownRenderer);
	}

	@Test
	void mismatchedCurrentSequenceRendersOrdinaryHtmlWithoutWrites() {
		String markdown = "New prose.";
		when(queryPort.findPublishedChapterBySlug("chapter")).thenReturn(Optional.of(narrationRecord(markdown, 1)));
		when(segments.findByChapterIdAndStatus(CHAPTER_ID, ChapterNarrationSegmentStatus.CURRENT)).thenReturn(
				List.of(ChapterNarrationSegment.create(UUID.randomUUID(), CHAPTER_ID, 0, "Old prose.", Instant.EPOCH)));
		when(segmenter.plan(markdown))
				.thenReturn(List.of(new NarrationTextSegmentPlan(NarrationTextSegment.of(0, markdown), List.of(0))));
		when(markdownRenderer.renderToHtml(markdown)).thenReturn("<p>New prose.</p>");
		when(navigationCachePort.getOrLoad(any()))
				.thenReturn(List.of(new ReaderChapterTocItemDTO(1, "Title", "chapter")));

		assertThat(useCase.execute("chapter").contentHtml()).isEqualTo("<p>New prose.</p>");
		verifyNoInteractions(narrationRenderer);
		verify(segments).findByChapterIdAndStatus(CHAPTER_ID, ChapterNarrationSegmentStatus.CURRENT);
		verifyNoMoreInteractions(segments);
	}

	@Test
	void annotationFailureDoesNotPreventProseRendering() {
		String markdown = "Prose.";
		when(queryPort.findPublishedChapterBySlug("chapter")).thenReturn(Optional.of(narrationRecord(markdown, 1)));
		when(segmenter.plan(markdown)).thenThrow(new IllegalStateException("Mapping unavailable"));
		when(markdownRenderer.renderToHtml(markdown)).thenReturn("<p>Prose.</p>");
		when(navigationCachePort.getOrLoad(any()))
				.thenReturn(List.of(new ReaderChapterTocItemDTO(1, "Title", "chapter")));

		assertThat(useCase.execute("chapter").contentHtml()).isEqualTo("<p>Prose.</p>");
		verifyNoInteractions(narrationRenderer);
	}

	@Test
	void readerUseCaseRemainsReadOnly() {
		assertThat(GetReaderChapterDetailUseCase.class
				.getAnnotation(org.springframework.transaction.annotation.Transactional.class).readOnly()).isTrue();
	}

	@Test
	@DisplayName("Lấy chi tiết Chapter PUBLISHED thành công với đầy đủ nội dung HTML, navigation và Table of Contents")
	void shouldReturnChapterDetailSuccessfullyWithToc() {
		String slug = "chuong-2-can-duyen";
		String rawMarkdown = "## Nội dung chương 2\n\nTrần Bình An đứng bên bờ suối.";
		String renderedHtml = "<h2>Nội dung chương 2</h2>\n<p>Trần Bình An đứng bên bờ suối.</p>";

		ReaderChapterRecord record = new ReaderChapterRecord(CHAPTER_ID, VOLUME_ID, 2, "Căn Duyên", slug, rawMarkdown,
				"Quyển Một - Lung Trung Tước", "quyen-1-lung-trung-tuoc", 1);

		List<ReaderChapterTocItemDTO> toc = List.of(new ReaderChapterTocItemDTO(1, "Khởi Đầu", "chuong-1-khoi-dau"),
				new ReaderChapterTocItemDTO(2, "Căn Duyên", "chuong-2-can-duyen"),
				new ReaderChapterTocItemDTO(5, "Họa Phúc", "chuong-5-hoa-phuc"));

		when(queryPort.findPublishedChapterBySlug("chuong-2-can-duyen")).thenReturn(Optional.of(record));
		when(markdownRenderer.renderToHtml(rawMarkdown)).thenReturn(renderedHtml);
		when(navigationCachePort.getOrLoad(any())).thenReturn(toc);

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

		assertThat(result.previousChapter().chapterNumber()).isEqualTo(1);
		assertThat(result.previousChapter().slug()).isEqualTo("chuong-1-khoi-dau");
		assertThat(result.nextChapter().chapterNumber()).isEqualTo(5);
		assertThat(result.nextChapter().slug()).isEqualTo("chuong-5-hoa-phuc");

		assertThat(result.tableOfContents()).isNotNull();
		assertThat(result.tableOfContents()).hasSize(3);
		assertThat(result.tableOfContents()).containsExactlyElementsOf(toc);

		verify(queryPort).findPublishedChapterBySlug("chuong-2-can-duyen");
		verify(markdownRenderer).renderToHtml(rawMarkdown);
	}

	@Test
	@DisplayName("Chương đầu tiên không có previousChapter (null)")
	void shouldHandleFirstChapterWithoutPrevious() {
		String slug = "chuong-1-khoi-dau";
		String rawMarkdown = "Chương mở đầu.";

		ReaderChapterRecord record = new ReaderChapterRecord(CHAPTER_ID, VOLUME_ID, 1, "Khởi Đầu", slug, rawMarkdown,
				"Quyển Một", "quyen-1", 1);

		List<ReaderChapterTocItemDTO> toc = List.of(new ReaderChapterTocItemDTO(1, "Khởi Đầu", "chuong-1-khoi-dau"),
				new ReaderChapterTocItemDTO(2, "Căn Duyên", "chuong-2-can-duyen"));

		when(queryPort.findPublishedChapterBySlug("chuong-1-khoi-dau")).thenReturn(Optional.of(record));
		when(markdownRenderer.renderToHtml(rawMarkdown)).thenReturn("<p>Chương mở đầu.</p>");
		when(navigationCachePort.getOrLoad(any())).thenReturn(toc);

		ReaderChapterDetailDTO result = useCase.execute(slug);

		assertThat(result.previousChapter()).isNull();
		assertThat(result.nextChapter().chapterNumber()).isEqualTo(2);
		assertThat(result.tableOfContents()).hasSize(2);
	}

	@Test
	@DisplayName("Chương mới nhất không có nextChapter (null)")
	void shouldHandleLatestChapterWithoutNext() {
		String slug = "chuong-100-ket-thuc";
		String rawMarkdown = "Chương cuối cùng hiện tại.";

		ReaderChapterRecord record = new ReaderChapterRecord(CHAPTER_ID, VOLUME_ID, 100, "Kết Thúc", slug, rawMarkdown,
				"Quyển Hai", "quyen-2", 2);

		List<ReaderChapterTocItemDTO> toc = List.of(new ReaderChapterTocItemDTO(99, "Áp Chót", "chuong-99-ap-chot"),
				new ReaderChapterTocItemDTO(100, "Kết Thúc", "chuong-100-ket-thuc"));

		when(queryPort.findPublishedChapterBySlug("chuong-100-ket-thuc")).thenReturn(Optional.of(record));
		when(markdownRenderer.renderToHtml(rawMarkdown)).thenReturn("<p>Chương cuối cùng hiện tại.</p>");
		when(navigationCachePort.getOrLoad(any())).thenReturn(toc);

		ReaderChapterDetailDTO result = useCase.execute(slug);

		assertThat(result.previousChapter().chapterNumber()).isEqualTo(99);
		assertThat(result.nextChapter()).isNull();
		assertThat(result.tableOfContents()).hasSize(2);
	}

	@Test
	void missingCurrentChapterInCacheTriggersExactlyOneReload() {
		String slug = "chuong-100-ket-thuc";
		ReaderChapterRecord record = new ReaderChapterRecord(CHAPTER_ID, VOLUME_ID, 100, "Kết Thúc", slug, "text",
				"Quyển Hai", "quyen-2", 2);

		List<ReaderChapterTocItemDTO> staleToc = List
				.of(new ReaderChapterTocItemDTO(99, "Áp Chót", "chuong-99-ap-chot"));

		List<ReaderChapterTocItemDTO> freshToc = List.of(
				new ReaderChapterTocItemDTO(99, "Áp Chót", "chuong-99-ap-chot"),
				new ReaderChapterTocItemDTO(100, "Kết Thúc", "chuong-100-ket-thuc"));

		when(queryPort.findPublishedChapterBySlug(slug)).thenReturn(Optional.of(record));
		when(markdownRenderer.renderToHtml(any())).thenReturn("text");

		when(navigationCachePort.getOrLoad(any())).thenReturn(staleToc).thenReturn(freshToc);

		ReaderChapterDetailDTO result = useCase.execute(slug);

		org.mockito.InOrder inOrder = org.mockito.Mockito.inOrder(navigationCachePort);
		inOrder.verify(navigationCachePort).getOrLoad(any());
		inOrder.verify(navigationCachePort).invalidate();
		inOrder.verify(navigationCachePort).getOrLoad(any());

		assertThat(result.previousChapter().chapterNumber()).isEqualTo(99);
		assertThat(result.nextChapter()).isNull();
	}

	@Test
	@DisplayName("Ném ChapterNotFoundException khi không tìm thấy Chapter hoặc Chapter/Volume chưa PUBLISHED")
	void shouldThrowChapterNotFoundExceptionWhenChapterNotPublishedOrNotFound() {
		when(queryPort.findPublishedChapterBySlug("chuong-chua-xuat-ban")).thenReturn(Optional.empty());

		assertThatThrownBy(() -> useCase.execute("chuong-chua-xuat-ban")).isInstanceOf(ChapterNotFoundException.class);

		verifyNoInteractions(markdownRenderer);
	}

	@Test
	@DisplayName("Ném ChapterNotFoundException khi slug là null hoặc rỗng")
	void shouldThrowChapterNotFoundExceptionWhenSlugIsNullOrBlank() {
		assertThatThrownBy(() -> useCase.execute(null)).isInstanceOf(ChapterNotFoundException.class);

		assertThatThrownBy(() -> useCase.execute("   ")).isInstanceOf(ChapterNotFoundException.class);

		verifyNoInteractions(queryPort);
		verifyNoInteractions(markdownRenderer);
	}
}
