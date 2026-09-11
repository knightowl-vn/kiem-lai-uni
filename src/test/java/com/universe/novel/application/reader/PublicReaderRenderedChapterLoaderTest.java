package com.universe.novel.application.reader;

import com.universe.novel.application.chapter.render.NovelMarkdownRenderer;
import com.universe.novel.application.exceptions.ChapterNotFoundException;
import com.universe.novel.application.narration.NarrationTextSegmentPlan;
import com.universe.novel.application.narration.NarrationTextSegmenter;
import com.universe.novel.application.ports.ChapterNarrationSegmentRepositoryPort;
import com.universe.novel.application.ports.PublicReaderRenderedChapterLoadResult;
import com.universe.novel.application.ports.ReaderChapterDetailQueryPort;
import com.universe.novel.application.ports.ReaderChapterDetailQueryPort.ReaderChapterRecord;
import com.universe.novel.application.reader.render.ReaderNarrationMarkdownRenderer;
import com.universe.novel.domain.narration.ChapterNarrationSegment;
import com.universe.novel.domain.narration.ChapterNarrationSegmentStatus;
import com.universe.novel.domain.narration.NarrationTextSegment;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PublicReaderRenderedChapterLoaderTest {

	@Mock
	private ReaderChapterDetailQueryPort queryPort;
	@Mock
	private NovelMarkdownRenderer novelMarkdownRenderer;
	@Mock
	private NarrationTextSegmenter narrationTextSegmenter;
	@Mock
	private ChapterNarrationSegmentRepositoryPort segmentRepository;
	@Mock
	private ReaderNarrationMarkdownRenderer narrationRenderer;

	private PublicReaderRenderedChapterLoader loader;

	private static final UUID CHAPTER_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
	private static final UUID VOLUME_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");

	@BeforeEach
	void setUp() {
		loader = new PublicReaderRenderedChapterLoader(queryPort, novelMarkdownRenderer, narrationTextSegmenter,
				segmentRepository, new ReaderNarrationBlockMappingResolver(), narrationRenderer);
	}

	private ReaderChapterRecord record(String rawContent) {
		return new ReaderChapterRecord(CHAPTER_ID, VOLUME_ID, 1, "Title", "slug", rawContent, "VolTitle", "vol-slug",
				1);
	}

	@Test
	void isTransactionalReadOnly() {
		try {
			var method = PublicReaderRenderedChapterLoader.class.getMethod("load", String.class);
			Transactional tx = method.getAnnotation(Transactional.class);
			assertThat(tx).isNotNull();
			assertThat(tx.readOnly()).isTrue();
		} catch (NoSuchMethodException e) {
			throw new RuntimeException(e);
		}
	}

	@Test
	void throwsWhenChapterNotFound() {
		when(queryPort.findPublishedChapterBySlug("not-found")).thenReturn(Optional.empty());
		assertThatThrownBy(() -> loader.load("not-found")).isInstanceOf(ChapterNotFoundException.class);
	}

	@Test
	void narrationAwareRendersAndIsCacheable() {
		String markdown = "Prose.";
		when(queryPort.findPublishedChapterBySlug("slug")).thenReturn(Optional.of(record(markdown)));

		ChapterNarrationSegment segment = ChapterNarrationSegment.create(UUID.randomUUID(), CHAPTER_ID, 0, markdown,
				Instant.EPOCH);
		when(segmentRepository.findByChapterIdAndStatus(CHAPTER_ID, ChapterNarrationSegmentStatus.CURRENT))
				.thenReturn(List.of(segment));

		when(narrationTextSegmenter.plan(markdown))
				.thenReturn(List.of(new NarrationTextSegmentPlan(NarrationTextSegment.of(0, markdown), List.of(0))));

		when(narrationRenderer.renderToHtml(eq(markdown), any())).thenReturn("<p>Rendered Narration</p>");

		PublicReaderRenderedChapterLoadResult result = loader.load("slug");

		assertThat(result.cacheable()).isTrue();
		assertThat(result.snapshot().contentHtml()).isEqualTo("<p>Rendered Narration</p>");
	}

	@Test
	void legitimateNoMappingIsCacheable() {
		String markdown = "Prose.";
		when(queryPort.findPublishedChapterBySlug("slug")).thenReturn(Optional.of(record(markdown)));

		// No matching segments
		when(segmentRepository.findByChapterIdAndStatus(CHAPTER_ID, ChapterNarrationSegmentStatus.CURRENT))
				.thenReturn(List.of());

		when(narrationTextSegmenter.plan(markdown))
				.thenReturn(List.of(new NarrationTextSegmentPlan(NarrationTextSegment.of(0, markdown), List.of(0))));

		when(novelMarkdownRenderer.renderToHtml(markdown)).thenReturn("<p>Rendered Prose</p>");

		PublicReaderRenderedChapterLoadResult result = loader.load("slug");

		assertThat(result.cacheable()).isTrue();
		assertThat(result.snapshot().contentHtml()).isEqualTo("<p>Rendered Prose</p>");
	}

	@Test
	void runtimeExceptionFallbackIsNotCacheable() {
		String markdown = "Prose.";
		when(queryPort.findPublishedChapterBySlug("slug")).thenReturn(Optional.of(record(markdown)));

		when(segmentRepository.findByChapterIdAndStatus(CHAPTER_ID, ChapterNarrationSegmentStatus.CURRENT))
				.thenThrow(new RuntimeException("DB Failure"));

		when(novelMarkdownRenderer.renderToHtml(markdown)).thenReturn("<p>Fallback Prose</p>");

		PublicReaderRenderedChapterLoadResult result = loader.load("slug");

		assertThat(result.cacheable()).isFalse(); // MUST be false
		assertThat(result.snapshot().contentHtml()).isEqualTo("<p>Fallback Prose</p>");
	}
}
