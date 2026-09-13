package com.universe.novel.application.ports;

import java.util.List;

/**
 * Port for extracting ordered speakable semantic blocks from chapter Markdown.
 */
public interface ChapterNarrationBlockExtractorPort {

    /**
     * Extracts ordered speakable semantic blocks from raw chapter Markdown.
     * Formatting-only Markdown syntax is stripped while preserving text, dialogue, and punctuation.
     *
     * @param markdown raw chapter Markdown
     * @return ordered list of speakable block text strings, or empty list if input is empty/blank
     */
    List<String> extractBlocks(String markdown);
}
