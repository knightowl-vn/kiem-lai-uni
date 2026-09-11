package com.universe.novel.application.reader;

import com.universe.novel.application.exceptions.ChapterNotFoundException;
import com.universe.novel.application.ports.PublicReaderNavigationIndexCachePort;
import com.universe.novel.application.ports.PublicReaderRenderedChapterCachePort;
import com.universe.novel.contracts.dto.reader.ReaderChapterDetailDTO;
import com.universe.novel.contracts.dto.reader.ReaderChapterRenderedSnapshotDTO;
import com.universe.novel.contracts.dto.reader.ReaderChapterTocItemDTO;
import com.universe.novel.contracts.dto.reader.ReaderVolumeSummaryDTO;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class GetReaderChapterDetailUseCaseTest {

	@Mock
	private PublicReaderRenderedChapterCachePort renderedChapterCachePort;
	@Mock
	private PublicReaderRenderedChapterLoader renderedChapterLoader;
	@Mock
	private PublicReaderNavigationIndexCachePort navigationCachePort;
	@Mock
	private PublicReaderNavigationIndexLoader navigationIndexLoader;

	private GetReaderChapterDetailUseCase useCase;

	private static final UUID CHAPTER_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
	private static final UUID VOLUME_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");

	@BeforeEach
	void setUp() {
		useCase = new GetReaderChapterDetailUseCase(
				renderedChapterCachePort,
				renderedChapterLoader,
				navigationCachePort,
				navigationIndexLoader
		);
	}

	@Test
	@DisplayName("Outer Reader use case is no longer transactional")
	void readerUseCaseIsNoLongerTransactional() {
		assertThat(GetReaderChapterDetailUseCase.class.getAnnotation(Transactional.class)).isNull();
	}

	@Test
	@DisplayName("Composes B5 snapshot + B4 navigation and derives previous/next")
	void shouldComposeB5AndB4Caches() {
		String slug = "chuong-2";
		ReaderVolumeSummaryDTO volume = new ReaderVolumeSummaryDTO(VOLUME_ID, "V1", "v-1", 1);
		ReaderChapterRenderedSnapshotDTO snapshot = new ReaderChapterRenderedSnapshotDTO(
				CHAPTER_ID, 2, "C2", slug, "<p>Text</p>", volume
		);

		List<ReaderChapterTocItemDTO> toc = List.of(
				new ReaderChapterTocItemDTO(1, "C1", "chuong-1"),
				new ReaderChapterTocItemDTO(2, "C2", "chuong-2"),
				new ReaderChapterTocItemDTO(5, "C5", "chuong-5")
		);

		when(renderedChapterCachePort.getOrLoad(eq(slug), any())).thenReturn(snapshot);
		when(navigationCachePort.getOrLoad(any())).thenReturn(toc);

		ReaderChapterDetailDTO result = useCase.execute(slug);

		assertThat(result.id()).isEqualTo(CHAPTER_ID);
		assertThat(result.chapterNumber()).isEqualTo(2);
		assertThat(result.contentHtml()).isEqualTo("<p>Text</p>");

		assertThat(result.previousChapter().chapterNumber()).isEqualTo(1);
		assertThat(result.nextChapter().chapterNumber()).isEqualTo(5);
		assertThat(result.tableOfContents()).isEqualTo(toc);

		verify(renderedChapterCachePort).getOrLoad(eq(slug), any());
		verify(navigationCachePort).getOrLoad(any());
	}

	@Test
	@DisplayName("B4 missing-current guard still invalidates and reloads once")
	void missingCurrentChapterInCacheTriggersExactlyOneReload() {
		String slug = "chuong-3";
		ReaderVolumeSummaryDTO volume = new ReaderVolumeSummaryDTO(VOLUME_ID, "V1", "v-1", 1);
		ReaderChapterRenderedSnapshotDTO snapshot = new ReaderChapterRenderedSnapshotDTO(
				CHAPTER_ID, 3, "C3", slug, "<p>Text</p>", volume
		);

		List<ReaderChapterTocItemDTO> staleToc = List.of(
				new ReaderChapterTocItemDTO(1, "C1", "chuong-1"),
				new ReaderChapterTocItemDTO(2, "C2", "chuong-2")
		);

		List<ReaderChapterTocItemDTO> freshToc = List.of(
				new ReaderChapterTocItemDTO(1, "C1", "chuong-1"),
				new ReaderChapterTocItemDTO(2, "C2", "chuong-2"),
				new ReaderChapterTocItemDTO(3, "C3", "chuong-3")
		);

		when(renderedChapterCachePort.getOrLoad(eq(slug), any())).thenReturn(snapshot);
		when(navigationCachePort.getOrLoad(any())).thenReturn(staleToc).thenReturn(freshToc);

		ReaderChapterDetailDTO result = useCase.execute(slug);

		org.mockito.InOrder inOrder = inOrder(navigationCachePort);
		inOrder.verify(navigationCachePort).getOrLoad(any());
		inOrder.verify(navigationCachePort).invalidate();
		inOrder.verify(navigationCachePort).getOrLoad(any());

		assertThat(result.previousChapter().chapterNumber()).isEqualTo(2);
		assertThat(result.nextChapter()).isNull();
	}

	@Test
	void throwsChapterNotFoundExceptionWhenSlugIsNullOrBlank() {
		assertThatThrownBy(() -> useCase.execute(null)).isInstanceOf(ChapterNotFoundException.class);
		assertThatThrownBy(() -> useCase.execute("   ")).isInstanceOf(ChapterNotFoundException.class);
		verifyNoInteractions(renderedChapterCachePort);
	}
}
