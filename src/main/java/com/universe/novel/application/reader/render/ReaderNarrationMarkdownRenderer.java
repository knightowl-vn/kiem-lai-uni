package com.universe.novel.application.reader.render;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Reader-only annotation of verified semantic block ordinals from the same Markdown snapshot. */
public interface ReaderNarrationMarkdownRenderer {
    String renderToHtml(String markdown, Map<Integer, List<UUID>> segmentIdsByBlock);
}
