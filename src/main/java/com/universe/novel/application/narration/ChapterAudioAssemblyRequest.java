package com.universe.novel.application.narration;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Passive request model for chapter narration audio assembly.
 */
public record ChapterAudioAssemblyRequest(
        List<ChapterAudioSegmentSource> segments
) {
    public ChapterAudioAssemblyRequest {
        Objects.requireNonNull(segments, "segments must not be null");
        if (segments.isEmpty()) {
            throw new IllegalArgumentException("segments must not be empty");
        }

        Set<UUID> seenSegmentIds = new HashSet<>();
        Set<Integer> seenSegmentIndexes = new HashSet<>();
        int previousSegmentIndex = -1;

        for (int i = 0; i < segments.size(); i++) {
            ChapterAudioSegmentSource segment = Objects.requireNonNull(
                    segments.get(i),
                    "segments must not contain null elements"
            );
            if (!seenSegmentIds.add(segment.segmentId())) {
                throw new IllegalArgumentException("Duplicate segmentId in assembly request: " + segment.segmentId());
            }
            if (!seenSegmentIndexes.add(segment.segmentIndex())) {
                throw new IllegalArgumentException("Duplicate segmentIndex in assembly request: " + segment.segmentIndex());
            }
            if (segment.segmentIndex() <= previousSegmentIndex) {
                throw new IllegalArgumentException(
                        "segmentIndex values must be strictly increasing in input order"
                );
            }
            previousSegmentIndex = segment.segmentIndex();
        }

        segments = List.copyOf(segments);
    }
}
