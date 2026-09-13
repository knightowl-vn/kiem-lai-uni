package com.universe.novel.infrastructure.markdown;

import com.universe.novel.application.ports.ChapterNarrationBlockExtractorPort;
import org.springframework.stereotype.Component;
import java.util.List;

/** Speakable text from the shared CommonMark semantic block sequence. */
@Component
public class CommonMarkChapterNarrationBlockExtractor implements ChapterNarrationBlockExtractorPort {
    private final CommonMarkNarrationSourceBlocks sourceBlocks = new CommonMarkNarrationSourceBlocks();

    @Override
    public List<String> extractBlocks(String markdown) {
        if (markdown == null || markdown.isBlank()) return List.of();
        return sourceBlocks.parse(markdown).blocks().stream()
                .map(CommonMarkNarrationSourceBlocks.SourceBlock::text).toList();
    }
}
