package com.universe.novel.infrastructure.persistence.narration;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

@Entity
@Table(
        name = "novel_chapter_narration_playback_cues",
        indexes = {
                @Index(
                        name = "idx_novel_chapter_narration_playback_cues_segment",
                        columnList = "segment_id"
                ),
                @Index(
                        name = "idx_novel_chapter_narration_playback_cues_artifact_segment_index",
                        columnList = "artifact_id,segment_index"
                )
        }
)
public class ChapterNarrationPlaybackCueJpaEntity {

    @EmbeddedId
    private ChapterNarrationPlaybackCueJpaId id;

    @Column(
            name = "segment_id",
            nullable = false,
            length = 36,
            columnDefinition = "CHAR(36)"
    )
    private String segmentId;

    @Column(
            name = "segment_index",
            nullable = false
    )
    private int segmentIndex;

    @Column(
            name = "start_millis",
            nullable = false
    )
    private long startMillis;

    @Column(
            name = "end_millis",
            nullable = false
    )
    private long endMillis;

    public ChapterNarrationPlaybackCueJpaEntity() {
    }

    public ChapterNarrationPlaybackCueJpaEntity(
            ChapterNarrationPlaybackCueJpaId id,
            String segmentId,
            int segmentIndex,
            long startMillis,
            long endMillis
    ) {
        this.id = id;
        this.segmentId = segmentId;
        this.segmentIndex = segmentIndex;
        this.startMillis = startMillis;
        this.endMillis = endMillis;
    }

    public ChapterNarrationPlaybackCueJpaId getId() {
        return id;
    }

    public String getSegmentId() {
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
}
