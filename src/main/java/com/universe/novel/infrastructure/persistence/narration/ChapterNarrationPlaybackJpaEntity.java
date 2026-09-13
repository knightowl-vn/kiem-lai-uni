package com.universe.novel.infrastructure.persistence.narration;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;

import java.time.Instant;

@Entity
@Table(
        name = "novel_chapter_narration_playbacks",
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "uq_novel_chapter_narration_playbacks_chapter_voice",
                        columnNames = {"chapter_id", "managed_voice_id"}
                )
        },
        indexes = {
                @Index(
                        name = "idx_novel_chapter_narration_playbacks_voice",
                        columnList = "managed_voice_id"
                ),
                @Index(
                        name = "idx_novel_chapter_narration_playbacks_current_artifact",
                        columnList = "current_artifact_id"
                )
        }
)
public class ChapterNarrationPlaybackJpaEntity {

    @Id
    @Column(
            name = "id",
            nullable = false,
            length = 36,
            columnDefinition = "CHAR(36)"
    )
    private String id;

    @Column(
            name = "chapter_id",
            nullable = false,
            length = 36,
            columnDefinition = "CHAR(36)"
    )
    private String chapterId;

    @Column(
            name = "managed_voice_id",
            nullable = false,
            length = 36,
            columnDefinition = "CHAR(36)"
    )
    private String managedVoiceId;

    @Column(
            name = "current_artifact_id",
            length = 36,
            columnDefinition = "CHAR(36)"
    )
    private String currentArtifactId;

    @Version
    @Column(
            name = "version",
            nullable = false
    )
    private Long version;

    @Column(
            name = "created_at",
            nullable = false
    )
    private Instant createdAt;

    @Column(
            name = "updated_at",
            nullable = false
    )
    private Instant updatedAt;

    public ChapterNarrationPlaybackJpaEntity() {
    }

    public ChapterNarrationPlaybackJpaEntity(
            String id,
            String chapterId,
            String managedVoiceId,
            String currentArtifactId,
            Long version,
            Instant createdAt,
            Instant updatedAt
    ) {
        this.id = id;
        this.chapterId = chapterId;
        this.managedVoiceId = managedVoiceId;
        this.currentArtifactId = currentArtifactId;
        this.version = version;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getChapterId() {
        return chapterId;
    }

    public void setChapterId(String chapterId) {
        this.chapterId = chapterId;
    }

    public String getManagedVoiceId() {
        return managedVoiceId;
    }

    public void setManagedVoiceId(String managedVoiceId) {
        this.managedVoiceId = managedVoiceId;
    }

    public String getCurrentArtifactId() {
        return currentArtifactId;
    }

    public void setCurrentArtifactId(String currentArtifactId) {
        this.currentArtifactId = currentArtifactId;
    }

    public Long getVersion() {
        return version;
    }

    public void setVersion(Long version) {
        this.version = version;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }
}
