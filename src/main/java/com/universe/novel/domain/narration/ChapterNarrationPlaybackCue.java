package com.universe.novel.domain.narration;

import java.util.Objects;
import java.util.UUID;

/**
 * Immutable cue mapping an artifact-local chapter audio interval to one narration segment.
 */
public class ChapterNarrationPlaybackCue {

    private final UUID artifactId;
    private final int cueOrdinal;
    private final UUID segmentId;
    private final int segmentIndex;
    private final long startMillis;
    private final long endMillis;

    private ChapterNarrationPlaybackCue(
            UUID artifactId,
            int cueOrdinal,
            UUID segmentId,
            int segmentIndex,
            long startMillis,
            long endMillis
    ) {
        this.artifactId = Objects.requireNonNull(artifactId, "artifactId must not be null.");
        if (cueOrdinal < 0) {
            throw new IllegalArgumentException("cueOrdinal must be >= 0: " + cueOrdinal);
        }
        this.cueOrdinal = cueOrdinal;
        this.segmentId = Objects.requireNonNull(segmentId, "segmentId must not be null.");
        if (segmentIndex < 0) {
            throw new IllegalArgumentException("segmentIndex must be >= 0: " + segmentIndex);
        }
        this.segmentIndex = segmentIndex;
        if (startMillis < 0) {
            throw new IllegalArgumentException("startMillis must be >= 0: " + startMillis);
        }
        this.startMillis = startMillis;
        if (endMillis <= startMillis) {
            throw new IllegalArgumentException("endMillis must be greater than startMillis.");
        }
        this.endMillis = endMillis;
    }

    public static ChapterNarrationPlaybackCue create(
            UUID artifactId,
            int cueOrdinal,
            UUID segmentId,
            int segmentIndex,
            long startMillis,
            long endMillis
    ) {
        return new ChapterNarrationPlaybackCue(
                artifactId,
                cueOrdinal,
                segmentId,
                segmentIndex,
                startMillis,
                endMillis
        );
    }

    public static ChapterNarrationPlaybackCue rehydrate(
            UUID artifactId,
            int cueOrdinal,
            UUID segmentId,
            int segmentIndex,
            long startMillis,
            long endMillis
    ) {
        return new ChapterNarrationPlaybackCue(
                artifactId,
                cueOrdinal,
                segmentId,
                segmentIndex,
                startMillis,
                endMillis
        );
    }

    public UUID getArtifactId() {
        return artifactId;
    }

    public int getCueOrdinal() {
        return cueOrdinal;
    }

    public UUID getSegmentId() {
        return segmentId;
    }

    public int getSegmentIndex() {
        return segmentIndex;
    }

    public long getStartMillis() {
        return startMillis;
    }

    public long getEndMillis() {
        return endMillis;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        ChapterNarrationPlaybackCue that = (ChapterNarrationPlaybackCue) o;
        return cueOrdinal == that.cueOrdinal && Objects.equals(artifactId, that.artifactId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(artifactId, cueOrdinal);
    }
}
