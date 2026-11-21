package com.universe.media.application.ports.storage;

import com.universe.media.domain.ContentHash;
import com.universe.media.domain.StorageKey;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Immutable manifest of all locally verified HLS artifacts ordered deterministically by upload stage:
 * Stage 1 binary segments/init boxes -> Stage 2 media playlists -> Stage 3 master.m3u8 last.
 */
public final class HlsExpectedArtifactManifest {

    public static final int MIN_OBJECT_COUNT = 4;

    private final HlsPackageIdentity identity;
    private final List<HlsArtifactDescriptor> stage1BinaryArtifacts;
    private final List<HlsArtifactDescriptor> stage2MediaPlaylists;
    private final HlsArtifactDescriptor stage3MasterManifest;
    private final List<HlsArtifactDescriptor> orderedArtifacts;
    private final ContentHash masterManifestSha256;
    private final long totalSizeBytes;
    private final int objectCount;
    private final String renditionsSummaryJson;

    public HlsExpectedArtifactManifest(
            HlsPackageIdentity identity,
            List<HlsArtifactDescriptor> artifacts,
            String renditionsSummaryJson
    ) {
        this.identity = Objects.requireNonNull(identity, "identity cannot be null.");
        Objects.requireNonNull(artifacts, "artifacts cannot be null.");
        if (artifacts.size() < MIN_OBJECT_COUNT) {
            throw new IllegalArgumentException(
                    "HLS package must contain at least " + MIN_OBJECT_COUNT + " artifacts, got: " + artifacts.size()
            );
        }
        if (renditionsSummaryJson == null || renditionsSummaryJson.isBlank()) {
            throw new IllegalArgumentException("renditionsSummaryJson cannot be blank.");
        }
        this.renditionsSummaryJson = renditionsSummaryJson;

        List<HlsArtifactDescriptor> s1 = new ArrayList<>();
        List<HlsArtifactDescriptor> s2 = new ArrayList<>();
        HlsArtifactDescriptor s3Master = null;

        Set<String> seenRelativePaths = new HashSet<>();
        Set<StorageKey> seenObjectKeys = new HashSet<>();
        long computedTotalBytes = 0L;

        for (HlsArtifactDescriptor artifact : artifacts) {
            Objects.requireNonNull(artifact, "artifact entry cannot be null.");
            HlsPackageKeyValidator.validateExactObjectKey(
                    identity.basePrefix(),
                    artifact.relativePath(),
                    artifact.objectKey()
            );
            if (!seenRelativePaths.add(artifact.relativePath())) {
                throw new IllegalArgumentException("Duplicate HLS artifact relative path: " + artifact.relativePath());
            }
            if (!seenObjectKeys.add(artifact.objectKey())) {
                throw new IllegalArgumentException("Duplicate HLS artifact objectKey: " + artifact.objectKey().value());
            }
            computedTotalBytes = Math.addExact(computedTotalBytes, artifact.sizeBytes());

            switch (artifact.uploadStage()) {
                case STAGE_1_INIT_AND_SEGMENTS -> s1.add(artifact);
                case STAGE_2_MEDIA_PLAYLISTS -> s2.add(artifact);
                case STAGE_3_MASTER_MANIFEST -> {
                    if (s3Master != null) {
                        throw new IllegalArgumentException("Duplicate STAGE_3 master manifest entry.");
                    }
                    s3Master = artifact;
                }
            }
        }

        if (s1.size() < 2 || s2.isEmpty() || s3Master == null) {
            throw new IllegalArgumentException(
                    "Incomplete HLS stage structure: stage1=" + s1.size()
                            + ", stage2=" + s2.size() + ", hasMaster=" + (s3Master != null)
            );
        }
        if (!identity.masterManifestKey().equals(s3Master.objectKey())) {
            throw new IllegalArgumentException("Master manifest key mismatch against package identity.");
        }

        s1.sort((a, b) -> a.relativePath().compareTo(b.relativePath()));
        s2.sort((a, b) -> a.relativePath().compareTo(b.relativePath()));

        List<HlsArtifactDescriptor> combined = new ArrayList<>(s1.size() + s2.size() + 1);
        combined.addAll(s1);
        combined.addAll(s2);
        combined.add(s3Master);

        this.stage1BinaryArtifacts = List.copyOf(s1);
        this.stage2MediaPlaylists = List.copyOf(s2);
        this.stage3MasterManifest = s3Master;
        this.orderedArtifacts = List.copyOf(combined);
        this.masterManifestSha256 = s3Master.sha256();
        this.totalSizeBytes = computedTotalBytes;
        this.objectCount = combined.size();
    }

    public HlsPackageIdentity identity() {
        return identity;
    }

    public List<HlsArtifactDescriptor> stage1BinaryArtifacts() {
        return stage1BinaryArtifacts;
    }

    public List<HlsArtifactDescriptor> stage2MediaPlaylists() {
        return stage2MediaPlaylists;
    }

    public HlsArtifactDescriptor stage3MasterManifest() {
        return stage3MasterManifest;
    }

    public List<HlsArtifactDescriptor> orderedArtifacts() {
        return orderedArtifacts;
    }

    public ContentHash masterManifestSha256() {
        return masterManifestSha256;
    }

    public long totalSizeBytes() {
        return totalSizeBytes;
    }

    public int objectCount() {
        return objectCount;
    }

    public String renditionsSummaryJson() {
        return renditionsSummaryJson;
    }
}
