package com.universe.novel.infrastructure.persistence.narration;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(
        name = "novel_chapter_narration_playback_artifacts",
        indexes = {
                @Index(
                        name = "idx_novel_chapter_narration_playback_artifacts_playback",
                        columnList = "playback_id"
                ),
                @Index(
                        name = "idx_novel_chapter_narration_playback_artifacts_chapter_voice",
                        columnList = "chapter_id,managed_voice_id"
                ),
                @Index(
                        name = "idx_novel_chapter_narration_playback_artifacts_media",
                        columnList = "media_asset_id"
                )
        }
)
public class ChapterNarrationPlaybackArtifactJpaEntity {

    @Id
    @Column(
            name = "id",
            nullable = false,
            length = 36,
            columnDefinition = "CHAR(36)"
    )
    private String id;

    @Column(
            name = "playback_id",
            nullable = false,
            length = 36,
            columnDefinition = "CHAR(36)"
    )
    private String playbackId;

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
            name = "source_content_version",
            nullable = false
    )
    private long sourceContentVersion;

    @Column(
            name = "synthesis_revision",
            nullable = false
    )
    private long synthesisRevision;

    @Column(
            name = "manifest_hash",
            nullable = false,
            length = 64
    )
    private String manifestHash;

    @Column(
            name = "media_asset_id",
            nullable = false,
            length = 36,
            columnDefinition = "CHAR(36)"
    )
    private String mediaAssetId;

    @Column(
            name = "duration_millis",
            nullable = false
    )
    private long durationMillis;

    @Column(
            name = "cue_count",
            nullable = false
    )
    private int cueCount;

    @Column(
            name = "codec_mime_type",
            nullable = false,
            length = 100
    )
    private String codecMimeType;

    @Column(
            name = "created_at",
            nullable = false
    )
    private Instant createdAt;

    @Column(name = "source_fingerprint", length = 64, columnDefinition = "CHAR(64)")
    private String sourceFingerprint;

    public ChapterNarrationPlaybackArtifactJpaEntity() {
    }

    public ChapterNarrationPlaybackArtifactJpaEntity(
            String id,
            String playbackId,
            String chapterId,
            String managedVoiceId,
            long sourceContentVersion,
            long synthesisRevision,
            String manifestHash,
            String mediaAssetId,
            long durationMillis,
            int cueCount,
            String codecMimeType,
            Instant createdAt,
            String sourceFingerprint
    ) {
        this.id = id;
        this.playbackId = playbackId;
        this.chapterId = chapterId;
        this.managedVoiceId = managedVoiceId;
        this.sourceContentVersion = sourceContentVersion;
        this.synthesisRevision = synthesisRevision;
        this.manifestHash = manifestHash;
        this.mediaAssetId = mediaAssetId;
        this.durationMillis = durationMillis;
        this.cueCount = cueCount;
        this.codecMimeType = codecMimeType;
        this.createdAt = createdAt;
        this.sourceFingerprint = sourceFingerprint;
    }

    public String getId() {
        return id;
    }

    public String getPlaybackId() {
        return playbackId;
    }

    public String getChapterId() {
        return chapterId;
    }

    public String getManagedVoiceId() {
        return managedVoiceId;
    }

    public long getSourceContentVersion() {
        return sourceContentVersion;
    }

    public long getSynthesisRevision() {
        return synthesisRevision;
    }

    public String getManifestHash() {
        return manifestHash;
    }

    public String getMediaAssetId() {
        return mediaAssetId;
    }

    public long getDurationMillis() {
        return durationMillis;
    }

    public int getCueCount() {
        return cueCount;
    }

    public String getCodecMimeType() {
        return codecMimeType;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public String getSourceFingerprint() {
        return sourceFingerprint;
    }
}
