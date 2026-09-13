package com.universe.novel.application.narration;

import com.universe.novel.domain.narration.NarrationTextSegment;
import java.util.List;

/** Transient provenance in the semantic block sequence of one Markdown snapshot. */
public record NarrationTextSegmentPlan(NarrationTextSegment segment, List<Integer> sourceBlockIndexes) {
}
