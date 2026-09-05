package com.universe.novel.application.narration;

import com.universe.novel.domain.narration.NarrationTextSegment;

import java.util.List;

/**
 * Port contract for segmenting novel chapter content into deterministic speakable narration segments.
 */
public interface NarrationTextSegmenter {

    /**
     * Converts raw chapter content into an ordered list of deterministic speakable narration segments.
     *
     * @param chapterContent raw markdown chapter content
     * @return ordered list of narration segments, or empty list if content is blank/empty
     */
    List<NarrationTextSegment> segment(String chapterContent);
}
