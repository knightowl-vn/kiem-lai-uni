package com.universe.novel.application.reader;

import com.universe.novel.application.exceptions.ChapterNotFoundException;
import com.universe.novel.application.ports.PublicReaderNavigationIndexCachePort;
import com.universe.novel.application.ports.PublicReaderRenderedChapterCachePort;
import com.universe.novel.contracts.dto.reader.ReaderChapterDetailDTO;
import com.universe.novel.contracts.dto.reader.ReaderChapterNavigationDTO;
import com.universe.novel.contracts.dto.reader.ReaderChapterRenderedSnapshotDTO;
import com.universe.novel.contracts.dto.reader.ReaderChapterTocItemDTO;

import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;
import java.util.Objects;

@Service
public class GetReaderChapterDetailUseCase {
	private final PublicReaderRenderedChapterCachePort renderedChapterCachePort;
	private final PublicReaderRenderedChapterLoader renderedChapterLoader;
	private final PublicReaderNavigationIndexCachePort navigationCachePort;
	private final PublicReaderNavigationIndexLoader navigationIndexLoader;

	public GetReaderChapterDetailUseCase(
			PublicReaderRenderedChapterCachePort renderedChapterCachePort,
			PublicReaderRenderedChapterLoader renderedChapterLoader,
			PublicReaderNavigationIndexCachePort navigationCachePort,
			PublicReaderNavigationIndexLoader navigationIndexLoader) {
		this.renderedChapterCachePort = Objects.requireNonNull(renderedChapterCachePort, "renderedChapterCachePort");
		this.renderedChapterLoader = Objects.requireNonNull(renderedChapterLoader, "renderedChapterLoader");
		this.navigationCachePort = Objects.requireNonNull(navigationCachePort, "navigationCachePort");
		this.navigationIndexLoader = Objects.requireNonNull(navigationIndexLoader, "navigationIndexLoader");
	}

	public ReaderChapterDetailDTO execute(String chapterSlug) {
		if (chapterSlug == null || chapterSlug.isBlank()) {
			throw new ChapterNotFoundException(chapterSlug);
		}

		String normalizedSlug = chapterSlug.trim().toLowerCase(Locale.ROOT);

		ReaderChapterRenderedSnapshotDTO snapshot = renderedChapterCachePort.getOrLoad(
				normalizedSlug,
				() -> renderedChapterLoader.load(normalizedSlug)
		);

		int currentChapterNumber = snapshot.chapterNumber();
		List<ReaderChapterTocItemDTO> tableOfContents = navigationCachePort
				.getOrLoad(navigationIndexLoader::load);

		int index = indexOfChapterNumber(tableOfContents, currentChapterNumber);
		if (index == -1) {
			navigationCachePort.invalidate();
			tableOfContents = navigationCachePort
					.getOrLoad(navigationIndexLoader::load);
			index = indexOfChapterNumber(tableOfContents, currentChapterNumber);
		}

		ReaderChapterNavigationDTO previousChapter = null;
		ReaderChapterNavigationDTO nextChapter = null;

		if (index != -1) {
			if (index > 0) {
				ReaderChapterTocItemDTO prevItem = tableOfContents.get(index - 1);
				previousChapter = new ReaderChapterNavigationDTO(prevItem.chapterNumber(), prevItem.title(),
						prevItem.slug());
			}
			if (index < tableOfContents.size() - 1) {
				ReaderChapterTocItemDTO nextItem = tableOfContents.get(index + 1);
				nextChapter = new ReaderChapterNavigationDTO(nextItem.chapterNumber(), nextItem.title(),
						nextItem.slug());
			}
		}

		return new ReaderChapterDetailDTO(
				snapshot.id(),
				snapshot.chapterNumber(),
				snapshot.title(),
				snapshot.slug(),
				snapshot.contentHtml(),
				snapshot.volume(),
				previousChapter,
				nextChapter,
				tableOfContents
		);
	}

	private int indexOfChapterNumber(List<ReaderChapterTocItemDTO> toc, int chapterNumber) {
		for (int i = 0; i < toc.size(); i++) {
			if (toc.get(i).chapterNumber() == chapterNumber) {
				return i;
			}
		}
		return -1;
	}
}
