package com.universe.novel.domain.narration;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Persisted aggregate representing the published narration manifest metadata for a chapter.
 * <p>
 * A chapter narration manifest identifies which published chapter content version produced
 * the currently reconciled narration segment manifest, along with the deterministic SHA-256
 * digest of that ordered speakable manifest.
 * <p>
 * Exactly one current manifest exists per chapter, using {@code chapterId} as the aggregate identity.
 * <p>
 * Core Domain Invariants:
 * <ul>
 *     <li>{@code chapterId} is mandatory and immutable (aggregate identity).</li>
 *     <li>{@code sourceContentVersion} is mandatory and strictly positive (>= 1).</li>
 *     <li>{@code manifestHash} is mandatory and must be a 64-character lowercase hexadecimal string (SHA-256).</li>
 *     <li>{@code createdAt} and {@code updatedAt} are mandatory; {@code updatedAt} must not precede {@code createdAt}.</li>
 *     <li>{@code reconcileTo} prevents moving backward to an earlier {@code sourceContentVersion}.</li>
 * </ul>
 */
public class ChapterNarrationManifest {

    private static final Pattern MANIFEST_HASH_PATTERN = Pattern.compile("^[0-9a-f]{64}$");

    private final UUID chapterId;
    private long sourceContentVersion;
    private String manifestHash;
    private final Instant createdAt;
    private Instant updatedAt;

    private ChapterNarrationManifest(
            UUID chapterId,
            long sourceContentVersion,
            String manifestHash,
            Instant createdAt,
            Instant updatedAt
    ) {
        this.chapterId = Objects.requireNonNull(chapterId, "chapterId must not be null.");
        if (sourceContentVersion < 1) {
            throw new IllegalArgumentException("sourceContentVersion must be >= 1: " + sourceContentVersion);
        }
        this.sourceContentVersion = sourceContentVersion;
        this.manifestHash = validateManifestHash(manifestHash);
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt must not be null.");
        this.updatedAt = Objects.requireNonNull(updatedAt, "updatedAt must not be null.");
        if (this.updatedAt.isBefore(this.createdAt)) {
            throw new IllegalArgumentException("updatedAt must not precede createdAt.");
        }
    }

    /**
     * Factory method creating a new chapter narration manifest for a chapter.
     */
    public static ChapterNarrationManifest create(
            UUID chapterId,
            long sourceContentVersion,
            String manifestHash,
            Instant now
    ) {
        Objects.requireNonNull(now, "now must not be null.");
        return new ChapterNarrationManifest(
                chapterId,
                sourceContentVersion,
                manifestHash,
                now,
                now
        );
    }

    /**
     * Factory method to rehydrate an existing chapter narration manifest from persistence.
     */
    public static ChapterNarrationManifest rehydrate(
            UUID chapterId,
            long sourceContentVersion,
            String manifestHash,
            Instant createdAt,
            Instant updatedAt
    ) {
        return new ChapterNarrationManifest(
                chapterId,
                sourceContentVersion,
                manifestHash,
                createdAt,
                updatedAt
        );
    }

    /**
     * Reconciles the manifest to a new published version and manifest hash.
     * <p>
     * Rejects version decreases ({@code newSourceContentVersion < this.sourceContentVersion}).
     * If version and hash are identical, this is a no-op and does not modify timestamps.
     *
     * @param newSourceContentVersion the updated published content version (>= current version)
     * @param newManifestHash         the 64-character lowercase hex SHA-256 hash of the reconciled segments
     * @param now                     the current timestamp
     */
    public void reconcileTo(long newSourceContentVersion, String newManifestHash, Instant now) {
        Objects.requireNonNull(now, "now must not be null.");
        if (now.isBefore(this.createdAt)) {
            throw new IllegalArgumentException("now must not precede createdAt.");
        }
        if (newSourceContentVersion < 1) {
            throw new IllegalArgumentException("newSourceContentVersion must be >= 1: " + newSourceContentVersion);
        }
        if (newSourceContentVersion < this.sourceContentVersion) {
            throw new IllegalArgumentException(
                    "Cannot reconcile manifest to an earlier source content version (current: "
                            + this.sourceContentVersion + ", requested: " + newSourceContentVersion + ")."
            );
        }
        String validatedHash = validateManifestHash(newManifestHash);

        if (this.sourceContentVersion == newSourceContentVersion && Objects.equals(this.manifestHash, validatedHash)) {
            return;
        }

        this.sourceContentVersion = newSourceContentVersion;
        this.manifestHash = validatedHash;
        this.updatedAt = now;
    }

    private static String validateManifestHash(String hash) {
        if (hash == null || !MANIFEST_HASH_PATTERN.matcher(hash).matches()) {
            throw new IllegalArgumentException("manifestHash must be exactly a 64-character lowercase hexadecimal string: " + hash);
        }
        return hash;
    }

    public UUID getChapterId() {
        return chapterId;
    }

    public long getSourceContentVersion() {
        return sourceContentVersion;
    }

    public String getManifestHash() {
        return manifestHash;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        ChapterNarrationManifest that = (ChapterNarrationManifest) o;
        return Objects.equals(chapterId, that.chapterId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(chapterId);
    }

    @Override
    public String toString() {
        return "ChapterNarrationManifest{" +
                "chapterId=" + chapterId +
                ", sourceContentVersion=" + sourceContentVersion +
                ", manifestHash='" + manifestHash + '\'' +
                ", createdAt=" + createdAt +
                ", updatedAt=" + updatedAt +
                '}';
    }
}
