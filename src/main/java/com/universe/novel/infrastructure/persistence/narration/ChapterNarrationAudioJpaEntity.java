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
        name = "novel_chapter_narration_audio",
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "uq_novel_chapter_narration_audio_segment_voice",
                        columnNames = {"segment_id", "managed_voice_id"}
                )
        },
        indexes = {
                @Index(
                        name = "idx_novel_chapter_narration_audio_voice",
                        columnList = "managed_voice_id"
                ),
                @Index(
                        name = "idx_novel_chapter_narration_audio_media_asset",
                        columnList = "media_asset_id"
                )
        }
)
public class ChapterNarrationAudioJpaEntity {

    @Id
    @Column(
            name = "id",
            nullable = false,
            length = 36,
            columnDefinition = "CHAR(36)"
    )
    private String id;

    @Column(
            name = "segment_id",
            nullable = false,
            length = 36,
            columnDefinition = "CHAR(36)"
    )
    private String segmentId;

    @Column(
            name = "managed_voice_id",
            nullable = false,
            length = 36,
            columnDefinition = "CHAR(36)"
    )
    private String managedVoiceId;

    @Column(
            name = "media_asset_id",
            nullable = false,
            length = 36,
            columnDefinition = "CHAR(36)"
    )
    private String mediaAssetId;

    @Column(
            name = "generated_synthesis_revision",
            nullable = false
    )
    private long generatedSynthesisRevision;

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

    public ChapterNarrationAudioJpaEntity() {
    }

    public ChapterNarrationAudioJpaEntity(
            String id,
            String segmentId,
            String managedVoiceId,
            String mediaAssetId,
            long generatedSynthesisRevision,
            Long version,
            Instant createdAt,
            Instant updatedAt
    ) {
        this.id = id;
        this.segmentId = segmentId;
        this.managedVoiceId = managedVoiceId;
        this.mediaAssetId = mediaAssetId;
        this.generatedSynthesisRevision = generatedSynthesisRevision;
        this.version = version;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    public ChapterNarrationAudioJpaEntity(
            String id,
            String segmentId,
            String managedVoiceId,
            String mediaAssetId,
            long generatedSynthesisRevision,
            Instant createdAt,
            Instant updatedAt
    ) {
        this(id, segmentId, managedVoiceId, mediaAssetId, generatedSynthesisRevision, null, createdAt, updatedAt);
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getSegmentId() {
        return segmentId;
    }

    public void setSegmentId(String segmentId) {
        this.segmentId = segmentId;
    }

    public String getManagedVoiceId() {
        return managedVoiceId;
    }

    public void setManagedVoiceId(String managedVoiceId) {
        this.managedVoiceId = managedVoiceId;
    }

    public String getMediaAssetId() {
        return mediaAssetId;
    }

    public void setMediaAssetId(String mediaAssetId) {
        this.mediaAssetId = mediaAssetId;
    }

    public long getGeneratedSynthesisRevision() {
        return generatedSynthesisRevision;
    }

    public void setGeneratedSynthesisRevision(long generatedSynthesisRevision) {
        this.generatedSynthesisRevision = generatedSynthesisRevision;
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
