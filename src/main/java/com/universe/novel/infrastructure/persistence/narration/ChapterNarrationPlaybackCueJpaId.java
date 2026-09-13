package com.universe.novel.infrastructure.persistence.narration;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

import java.io.Serializable;
import java.util.Objects;

@Embeddable
public class ChapterNarrationPlaybackCueJpaId implements Serializable {

    @Column(
            name = "artifact_id",
            nullable = false,
            length = 36,
            columnDefinition = "CHAR(36)"
    )
    private String artifactId;

    @Column(
            name = "cue_ordinal",
            nullable = false
    )
    private int cueOrdinal;

    public ChapterNarrationPlaybackCueJpaId() {
    }

    public ChapterNarrationPlaybackCueJpaId(String artifactId, int cueOrdinal) {
        this.artifactId = artifactId;
        this.cueOrdinal = cueOrdinal;
    }

    public String getArtifactId() {
        return artifactId;
    }

    public int getCueOrdinal() {
        return cueOrdinal;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        ChapterNarrationPlaybackCueJpaId that = (ChapterNarrationPlaybackCueJpaId) o;
        return cueOrdinal == that.cueOrdinal && Objects.equals(artifactId, that.artifactId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(artifactId, cueOrdinal);
    }
}
