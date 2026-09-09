package com.universe.novel.application.reader;

import com.universe.novel.application.narration.NarrationTextSegmentPlan;
import com.universe.novel.domain.narration.ChapterNarrationSegment;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Pure read-side validation: never searches by text, repositions, or reconciles segments. */
@Component
public class ReaderNarrationBlockMappingResolver {
    public Map<Integer, List<UUID>> resolve(UUID chapterId, List<NarrationTextSegmentPlan> plans,
                                            List<ChapterNarrationSegment> currentSegments) {
        if (chapterId == null || plans == null || plans.isEmpty() || currentSegments == null
                || plans.size() != currentSegments.size()) return Map.of();

        Map<Integer, ChapterNarrationSegment> byIndex = new HashMap<>();
        Set<UUID> ids = new HashSet<>();
        for (ChapterNarrationSegment row : currentSegments) {
            if (row == null || !row.isCurrent() || !chapterId.equals(row.getChapterId()) || row.getId() == null
                    || !ids.add(row.getId()) || row.getSegmentIndex() < 0 || row.getSegmentIndex() >= plans.size()
                    || byIndex.putIfAbsent(row.getSegmentIndex(), row) != null) return Map.of();
        }

        Map<Integer, List<UUID>> mapping = new LinkedHashMap<>();
        for (int index = 0; index < plans.size(); index++) {
            NarrationTextSegmentPlan plan = plans.get(index);
            ChapterNarrationSegment row = byIndex.get(index);
            if (plan == null || plan.segment() == null || row == null || plan.segment().index() != index
                    || !Objects.equals(row.getText(), plan.segment().text())
                    || !Objects.equals(row.getContentHash(), plan.segment().contentHash())
                    || row.getCharacterCount() != plan.segment().characterCount()
                    || plan.sourceBlockIndexes() == null || plan.sourceBlockIndexes().isEmpty()) return Map.of();
            int previousBlock = -1;
            for (Integer block : plan.sourceBlockIndexes()) {
                if (block == null || block < 0 || block <= previousBlock) return Map.of();
                previousBlock = block;
                mapping.computeIfAbsent(block, ignored -> new ArrayList<>()).add(row.getId());
            }
        }
        mapping.replaceAll((block, segmentIds) -> List.copyOf(segmentIds));
        return Collections.unmodifiableMap(mapping);
    }
}
