package com.universe.novel.domain.narration;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Immutable metadata for one assembled chapter-level Reader audio artifact.
 */
public class ChapterNarrationPlaybackArtifact {

    private static final Pattern MANIFEST_HASH_PATTERN = Pattern.compile("^[0-9a-f]{64}$");
    private static final int MAX_CODEC_MIME_TYPE_LENGTH = 100;

    private final UUID id;
    private final UUID playbackId;
    private final UUID chapterId;
    private final UUID managedVoiceId;
    private final long sourceContentVersion;
    private final long synthesisRevision;
    private final String manifestHash;
    private final UUID mediaAssetId;
    private final long durationMillis;
    private final int cueCount;
    private final String codecMimeType;
    private final Instant createdAt;
    private final String sourceFingerprint;

    private ChapterNarrationPlaybackArtifact(
            UUID id,
            UUID playbackId,
            UUID chapterId,
            UUID managedVoiceId,
            long sourceContentVersion,
            long synthesisRevision,
            String manifestHash,
            UUID mediaAssetId,
            long durationMillis,
            int cueCount,
            String codecMimeType,
            Instant createdAt,
            String sourceFingerprint
    ) {
        this.id = Objects.requireNonNull(id, "id must not be null.");
        this.playbackId = Objects.requireNonNull(playbackId, "playbackId must not be null.");
        this.chapterId = Objects.requireNonNull(chapterId, "chapterId must not be null.");
        this.managedVoiceId = Objects.requireNonNull(managedVoiceId, "managedVoiceId must not be null.");
        this.sourceContentVersion = validatePositive(sourceContentVersion, "sourceContentVersion");
        this.synthesisRevision = validatePositive(synthesisRevision, "synthesisRevision");
        this.manifestHash = validateManifestHash(manifestHash);
        this.mediaAssetId = Objects.requireNonNull(mediaAssetId, "mediaAssetId must not be null.");
        if (durationMillis <= 0) {
            throw new IllegalArgumentException("durationMillis must be > 0: " + durationMillis);
        }
        this.durationMillis = durationMillis;
        if (cueCount < 0) {
            throw new IllegalArgumentException("cueCount must be >= 0: " + cueCount);
        }
        this.cueCount = cueCount;
        this.codecMimeType = validateCodecMimeType(codecMimeType);
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt must not be null.");
        this.sourceFingerprint = sourceFingerprint == null ? null : validateManifestHash(sourceFingerprint);
    }

    public static ChapterNarrationPlaybackArtifact create(
            UUID id,
            ChapterNarrationPlayback playback,
            long sourceContentVersion,
            long synthesisRevision,
            String manifestHash,
            UUID mediaAssetId,
            long durationMillis,
            int cueCount,
            String codecMimeType,
            Instant createdAt
    ) {
        return create(id, playback, sourceContentVersion, synthesisRevision, manifestHash, mediaAssetId,
                durationMillis, cueCount, codecMimeType, createdAt, null);
    }

    public static ChapterNarrationPlaybackArtifact create(
            UUID id, ChapterNarrationPlayback playback, long sourceContentVersion, long synthesisRevision,
            String manifestHash, UUID mediaAssetId, long durationMillis, int cueCount,
            String codecMimeType, Instant createdAt, String sourceFingerprint
    ) {
        Objects.requireNonNull(playback, "playback must not be null.");
        return new ChapterNarrationPlaybackArtifact(
                id,
                playback.getId(),
                playback.getChapterId(),
                playback.getManagedVoiceId(),
                sourceContentVersion,
                synthesisRevision,
                manifestHash,
                mediaAssetId,
                durationMillis,
                cueCount,
                codecMimeType,
                createdAt,
                sourceFingerprint
        );
    }

    public static ChapterNarrationPlaybackArtifact rehydrate(
            UUID id,
            UUID playbackId,
            UUID chapterId,
            UUID managedVoiceId,
            long sourceContentVersion,
            long synthesisRevision,
            String manifestHash,
            UUID mediaAssetId,
            long durationMillis,
            int cueCount,
            String codecMimeType,
            Instant createdAt
    ) {
        return rehydrate(id, playbackId, chapterId, managedVoiceId, sourceContentVersion, synthesisRevision,
                manifestHash, mediaAssetId, durationMillis, cueCount, codecMimeType, createdAt, null);
    }

    public static ChapterNarrationPlaybackArtifact rehydrate(
            UUID id, UUID playbackId, UUID chapterId, UUID managedVoiceId,
            long sourceContentVersion, long synthesisRevision, String manifestHash, UUID mediaAssetId,
            long durationMillis, int cueCount, String codecMimeType, Instant createdAt, String sourceFingerprint
    ) {
        return new ChapterNarrationPlaybackArtifact(
                id,
                playbackId,
                chapterId,
                managedVoiceId,
                sourceContentVersion,
                synthesisRevision,
                manifestHash,
                mediaAssetId,
                durationMillis,
                cueCount,
                codecMimeType,
                createdAt,
                sourceFingerprint
        );
    }

    private static long validatePositive(long value, String fieldName) {
        if (value < 1) {
            throw new IllegalArgumentException(fieldName + " must be >= 1: " + value);
        }
        return value;
    }

    private static String validateManifestHash(String manifestHash) {
        if (manifestHash == null || !MANIFEST_HASH_PATTERN.matcher(manifestHash).matches()) {
            throw new IllegalArgumentException("manifestHash must be exactly a 64-character lowercase hexadecimal string: " + manifestHash);
        }
        return manifestHash;
    }

    private static String validateCodecMimeType(String codecMimeType) {
        if (codecMimeType == null || codecMimeType.isBlank()) {
            throw new IllegalArgumentException("codecMimeType must not be blank.");
        }
        String trimmed = codecMimeType.trim();
        if (trimmed.length() > MAX_CODEC_MIME_TYPE_LENGTH) {
            throw new IllegalArgumentException("codecMimeType must not exceed " + MAX_CODEC_MIME_TYPE_LENGTH + " characters.");
        }
        return trimmed;
    }

    public UUID getId() {
        return id;
    }

    public UUID getPlaybackId() {
        return playbackId;
    }

    public UUID getChapterId() {
        return chapterId;
    }

    public UUID getManagedVoiceId() {
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

    public UUID getMediaAssetId() {
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

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        ChapterNarrationPlaybackArtifact that = (ChapterNarrationPlaybackArtifact) o;
        return Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }
}
