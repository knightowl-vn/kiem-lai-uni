package com.universe.novel.infrastructure.persistence.narration;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "novel_chapter_narration_manifests")
public class ChapterNarrationManifestJpaEntity {

    @Id
    @Column(
            name = "chapter_id",
            nullable = false,
            length = 36,
            columnDefinition = "CHAR(36)"
    )
    private String chapterId;

    @Column(
            name = "source_content_version",
            nullable = false
    )
    private long sourceContentVersion;

    @Column(
            name = "manifest_hash",
            nullable = false,
            length = 64
    )
    private String manifestHash;

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

    public ChapterNarrationManifestJpaEntity() {
    }

    public ChapterNarrationManifestJpaEntity(
            String chapterId,
            long sourceContentVersion,
            String manifestHash,
            Instant createdAt,
            Instant updatedAt
    ) {
        this.chapterId = chapterId;
        this.sourceContentVersion = sourceContentVersion;
        this.manifestHash = manifestHash;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    public String getChapterId() {
        return chapterId;
    }

    public void setChapterId(String chapterId) {
        this.chapterId = chapterId;
    }

    public long getSourceContentVersion() {
        return sourceContentVersion;
    }

    public void setSourceContentVersion(long sourceContentVersion) {
        this.sourceContentVersion = sourceContentVersion;
    }

    public String getManifestHash() {
        return manifestHash;
    }

    public void setManifestHash(String manifestHash) {
        this.manifestHash = manifestHash;
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
