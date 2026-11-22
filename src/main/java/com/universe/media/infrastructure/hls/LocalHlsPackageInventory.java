package com.universe.media.infrastructure.hls;

import com.universe.media.application.ports.storage.HlsArtifactDescriptor;
import com.universe.media.application.ports.storage.HlsArtifactStreamSource;
import com.universe.media.application.ports.storage.HlsArtifactType;
import com.universe.media.application.ports.storage.HlsExpectedArtifactManifest;
import com.universe.media.application.ports.storage.HlsPackageIdentity;
import com.universe.media.application.ports.storage.HlsPackageKeyValidator;
import com.universe.media.application.ports.storage.HlsPackageUploadCommand;
import com.universe.media.domain.ContentHash;
import com.universe.media.domain.StorageKey;
import com.universe.media.domain.StorageProviderId;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.FileVisitOption;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Bounded, streaming local artifact inventory derived strictly from an active, already locally
 * verified {@link LocalFfmpegHlsPackagingEngine.LocalHlsPackagingResult}.
 *
 * <p>Enforces bounded streaming directory traversal ({@link Files#walkFileTree}) without
 * pre-materializing unbounded file lists, binds artifact streaming strictly to an active usage
 * pin on the owning {@link LocalFfmpegHlsPackagingEngine.LocalHlsPackagingResult}, never vends
 * detached raw {@link Path} handles across the upload port, and never creates remote receipts.
 */
public final class LocalHlsPackageInventory {

    public static final int DEFAULT_MAX_OBJECT_COUNT = 10_000;
    public static final long DEFAULT_MAX_SINGLE_OBJECT_BYTES = 64L * 1024L * 1024L;
    public static final int MAX_ALLOWED_OBJECT_COUNT = 50_000;
    public static final long MAX_ALLOWED_SINGLE_OBJECT_BYTES = 256L * 1024L * 1024L;
    private static final int MAX_DIRECTORY_DEPTH = 4;
    private static final int STREAM_HASH_BUFFER_BYTES = 8192;

    /**
     * Bounded inventory limits applied during local HLS artifact inspection.
     */
    public record InventoryLimits(int maxObjectCount, long maxSingleObjectBytes) {
        public InventoryLimits {
            validateLimits(maxObjectCount, maxSingleObjectBytes);
        }

        /**
         * Returns the stricter of local default bounds and destination storage bounds so oversized
         * packages fail locally before remote upload attempts begin.
         */
        public static InventoryLimits stricterOfDefaultsAndRemote(
                int remoteMaxObjectCount,
                long remoteMaxSingleObjectBytes
        ) {
            validateLimits(remoteMaxObjectCount, remoteMaxSingleObjectBytes);
            return new InventoryLimits(
                    Math.min(DEFAULT_MAX_OBJECT_COUNT, remoteMaxObjectCount),
                    Math.min(DEFAULT_MAX_SINGLE_OBJECT_BYTES, remoteMaxSingleObjectBytes)
            );
        }
    }

    private final LocalFfmpegHlsPackagingEngine.LocalHlsPackagingResult owningResult;
    private final Path realPackageRoot;
    private final HlsExpectedArtifactManifest expectedManifest;
    private final Map<StorageKey, LocalArtifactEntry> entriesByKey;

    private record LocalArtifactEntry(Path realFilePath, long expectedSizeBytes) {
    }

    private LocalHlsPackageInventory(
            LocalFfmpegHlsPackagingEngine.LocalHlsPackagingResult owningResult,
            Path realPackageRoot,
            HlsExpectedArtifactManifest expectedManifest,
            Map<StorageKey, LocalArtifactEntry> entriesByKey
    ) {
        this.owningResult = owningResult;
        this.realPackageRoot = realPackageRoot;
        this.expectedManifest = expectedManifest;
        this.entriesByKey = Collections.unmodifiableMap(new LinkedHashMap<>(entriesByKey));
    }

    public static LocalHlsPackageInventory fromVerifiedResult(
            LocalFfmpegHlsPackagingEngine.LocalHlsPackagingResult result,
            UUID assetId,
            int sourceVersionNumber,
            UUID packageUuid,
            StorageProviderId providerId
    ) throws IOException {
        return fromVerifiedResult(
                result,
                assetId,
                sourceVersionNumber,
                packageUuid,
                providerId,
                DEFAULT_MAX_OBJECT_COUNT,
                DEFAULT_MAX_SINGLE_OBJECT_BYTES
        );
    }

    public static LocalHlsPackageInventory fromVerifiedResult(
            LocalFfmpegHlsPackagingEngine.LocalHlsPackagingResult result,
            UUID assetId,
            int sourceVersionNumber,
            UUID packageUuid,
            StorageProviderId providerId,
            InventoryLimits limits
    ) throws IOException {
        Objects.requireNonNull(limits, "InventoryLimits cannot be null.");
        return fromVerifiedResult(
                result,
                assetId,
                sourceVersionNumber,
                packageUuid,
                providerId,
                limits.maxObjectCount(),
                limits.maxSingleObjectBytes()
        );
    }

    public static LocalHlsPackageInventory fromVerifiedResult(
            LocalFfmpegHlsPackagingEngine.LocalHlsPackagingResult result,
            UUID assetId,
            int sourceVersionNumber,
            UUID packageUuid,
            StorageProviderId providerId,
            int maxObjectCount,
            long maxSingleObjectBytes
    ) throws IOException {
        Objects.requireNonNull(result, "LocalHlsPackagingResult cannot be null.");
        validateLimits(maxObjectCount, maxSingleObjectBytes);

        try (LocalFfmpegHlsPackagingEngine.LocalHlsPackagingResult.ActiveUsagePin inventoryPin = result.acquireUsagePin()) {
            Path packageRoot = result.packageRoot();
            if (!Files.isDirectory(packageRoot, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(packageRoot)) {
                throw new IllegalStateException("Local HLS package root is missing or is a symbolic link: " + packageRoot);
            }
            Path realRoot = packageRoot.toRealPath();

            HlsPackageIdentity identity =
                    HlsPackageIdentity.ofCanonical(assetId, sourceVersionNumber, packageUuid, providerId);
            HlsLadderPlanner.HlsPackagingPlan plan = result.plan();
            LocalHlsPackageVerifier.LocalHlsVerificationReport report = result.verificationReport();

            Set<String> allowedVideoDirs = new HashSet<>();
            for (HlsLadderPlanner.VideoTierSpec tier : plan.videoTiers()) {
                allowedVideoDirs.add("video/" + tier.label());
            }

            List<HlsArtifactDescriptor> descriptors = new ArrayList<>();
            Map<StorageKey, LocalArtifactEntry> rawEntriesByKey = new HashMap<>();
            int[] observedFiles = new int[]{0};

            Files.walkFileTree(
                    realRoot,
                    EnumSet.noneOf(FileVisitOption.class),
                    MAX_DIRECTORY_DEPTH,
                    new SimpleFileVisitor<>() {
                        @Override
                        public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                            if (inventoryPin.isCloseRequested()) {
                                throw new IllegalStateException("Owning LocalHlsPackagingResult closed during inventory.");
                            }
                            if (attrs.isSymbolicLink() || Files.isSymbolicLink(dir)) {
                                throw new IllegalStateException("Symbolic link directory rejected inside HLS package: " + dir);
                            }
                            Path normalized = dir.toAbsolutePath().normalize();
                            if (!normalized.startsWith(realRoot) || !normalized.toRealPath().startsWith(realRoot)) {
                                throw new IllegalStateException("Directory escapes HLS package root: " + dir);
                            }
                            if (!dir.equals(realRoot)) {
                                String relDir = realRoot.relativize(normalized).toString().replace('\\', '/');
                                validateAllowedDirectory(relDir, allowedVideoDirs, plan.includeAudio());
                            }
                            return FileVisitResult.CONTINUE;
                        }

                        @Override
                        public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                            if (inventoryPin.isCloseRequested()) {
                                throw new IllegalStateException("Owning LocalHlsPackagingResult closed during inventory.");
                            }
                            if (attrs.isSymbolicLink() || Files.isSymbolicLink(file)) {
                                throw new IllegalStateException("Symbolic link rejected inside HLS package inventory: " + file);
                            }
                            if (!attrs.isRegularFile()) {
                                throw new IllegalStateException("Non-regular file rejected inside HLS package: " + file);
                            }
                            Path normalized = file.toAbsolutePath().normalize();
                            if (!normalized.startsWith(realRoot)) {
                                throw new IllegalStateException("Path escapes HLS package root: " + file);
                            }
                            Path realEntry = normalized.toRealPath();
                            if (!realEntry.startsWith(realRoot)) {
                                throw new IllegalStateException("Real path escapes HLS package root: " + file);
                            }

                            observedFiles[0]++;
                            if (observedFiles[0] > maxObjectCount) {
                                throw new IllegalStateException(
                                        "HLS package artifact count exceeds bounded maximum (" + maxObjectCount + ")."
                                );
                            }

                            String rel = realRoot.relativize(normalized).toString().replace('\\', '/');
                            long fileSize = attrs.size();
                            if (fileSize <= 0) {
                                throw new IllegalStateException("Empty (0-byte) HLS artifact rejected: " + rel);
                            }
                            if (fileSize > maxSingleObjectBytes) {
                                throw new IllegalStateException(
                                        "HLS artifact '" + rel + "' size (" + fileSize
                                                + " bytes) exceeds maxSingleObjectBytes (" + maxSingleObjectBytes + ")."
                                );
                            }

                            HlsPackageKeyValidator.ValidatedArtifactClassification classified =
                                    HlsPackageKeyValidator.validateAndClassifyRelativePath(rel);
                            StorageKey objectKey =
                                    HlsPackageKeyValidator.buildAndValidateObjectKey(identity.basePrefix(), classified.relativePath());
                            if (rawEntriesByKey.containsKey(objectKey)) {
                                throw new IllegalStateException(
                                        "Duplicate objectKey in local HLS inventory: " + objectKey.value()
                                );
                            }

                            ContentHash digest = streamSha256(realEntry, fileSize);
                            descriptors.add(new HlsArtifactDescriptor(
                                    classified.relativePath(),
                                    objectKey,
                                    fileSize,
                                    digest,
                                    classified.artifactType(),
                                    classified.contentType(),
                                    classified.uploadStage()
                            ));
                            rawEntriesByKey.put(objectKey, new LocalArtifactEntry(realEntry, fileSize));
                            return FileVisitResult.CONTINUE;
                        }

                        @Override
                        public FileVisitResult visitFileFailed(Path file, IOException exc) throws IOException {
                            throw exc;
                        }
                    }
            );

            HlsExpectedArtifactManifest manifest =
                    new HlsExpectedArtifactManifest(identity, descriptors, report.renditionsSummaryJson());

            verifyMatchesLocalReportAndPlan(manifest, report, plan);

            Map<StorageKey, LocalArtifactEntry> orderedEntries = new LinkedHashMap<>(manifest.objectCount());
            for (HlsArtifactDescriptor descriptor : manifest.orderedArtifacts()) {
                LocalArtifactEntry entry = rawEntriesByKey.get(descriptor.objectKey());
                if (entry == null) {
                    throw new IllegalStateException(
                            "Missing resolved file for ordered artifact: " + descriptor.objectKey().value()
                    );
                }
                orderedEntries.put(descriptor.objectKey(), entry);
            }

            return new LocalHlsPackageInventory(result, realRoot, manifest, orderedEntries);
        }
    }

    public HlsExpectedArtifactManifest expectedManifest() {
        ensureAlive();
        return expectedManifest;
    }

    /**
     * Returns a lifecycle-bound {@link HlsArtifactStreamSource} tied directly to the owning
     * {@link LocalFfmpegHlsPackagingEngine.LocalHlsPackagingResult} without leaking detached paths.
     */
    public HlsArtifactStreamSource openArtifactStreamSource() {
        ensureAlive();
        return new ScopedInventoryStreamSource();
    }

    public HlsPackageUploadCommand toUploadCommand(String trustedBucketName) {
        ensureAlive();
        return new HlsPackageUploadCommand(trustedBucketName, expectedManifest, new ScopedInventoryStreamSource());
    }

    public boolean isOwningResultAlive() {
        return !owningResult.isClosed() && Files.isDirectory(realPackageRoot, LinkOption.NOFOLLOW_LINKS);
    }

    private void ensureAlive() {
        if (owningResult.isClosed()) {
            throw new IllegalStateException(
                    "Owning LocalHlsPackagingResult has been closed or is closing; detached artifact access is forbidden."
            );
        }
        if (!Files.isDirectory(realPackageRoot, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalStateException("Verified local HLS package directory no longer exists: " + realPackageRoot);
        }
    }

    private final class ScopedInventoryStreamSource implements HlsArtifactStreamSource {

        @Override
        public ActiveUploadScope openUploadScope() {
            return owningResult.acquireUsagePin();
        }

        @Override
        public InputStream openArtifactStream(StorageKey objectKey) throws IOException {
            Objects.requireNonNull(objectKey, "objectKey cannot be null.");
            LocalArtifactEntry entry = entriesByKey.get(objectKey);
            if (entry == null) {
                throw new IllegalArgumentException("Object key is not part of this HLS inventory: " + objectKey.value());
            }

            LocalFfmpegHlsPackagingEngine.LocalHlsPackagingResult.ActiveUsagePin pin = owningResult.acquireUsagePin();
            try {
                Path file = entry.realFilePath();
                if (Files.isSymbolicLink(file) || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
                    throw new IllegalStateException("Local HLS artifact file is missing or unsafe: " + file);
                }
                long actualSize = Files.size(file);
                if (actualSize != entry.expectedSizeBytes()) {
                    throw new IllegalStateException(
                            "Local HLS artifact size changed since inventory: key=" + objectKey.value()
                                    + ", expected=" + entry.expectedSizeBytes() + ", actual=" + actualSize
                    );
                }
                if (pin.isCloseRequested()) {
                    throw new IllegalStateException(
                            "Owning LocalHlsPackagingResult closure requested before stream read started."
                    );
                }
                InputStream raw = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS);
                return new FilterInputStream(raw) {
                    private final AtomicBoolean closed = new AtomicBoolean();

                    @Override
                    public void close() throws IOException {
                        if (closed.compareAndSet(false, true)) {
                            try {
                                super.close();
                            } finally {
                                pin.close();
                            }
                        }
                    }
                };
            } catch (Throwable t) {
                pin.close();
                throw t;
            }
        }

        @Override
        public <T> T withArtifactStream(StorageKey objectKey, ArtifactStreamReader<T> reader) throws IOException {
            Objects.requireNonNull(reader, "reader cannot be null.");
            LocalArtifactEntry entry = entriesByKey.get(objectKey);
            if (entry == null) {
                throw new IllegalArgumentException("Object key is not part of this HLS inventory: " + objectKey.value());
            }
            try (InputStream in = openArtifactStream(objectKey)) {
                return reader.read(in, entry.expectedSizeBytes());
            }
        }

        @Override
        public void close() throws IOException {
            owningResult.close();
        }
    }

    private static void validateAllowedDirectory(
            String relDir,
            Set<String> allowedVideoDirs,
            boolean includeAudio
    ) {
        if ("video".equals(relDir) || allowedVideoDirs.contains(relDir)) {
            return;
        }
        if (includeAudio && ("audio".equals(relDir) || "audio/main".equals(relDir))) {
            return;
        }
        throw new IllegalStateException("Unexpected or disallowed directory inside HLS package: " + relDir);
    }

    private static void verifyMatchesLocalReportAndPlan(
            HlsExpectedArtifactManifest manifest,
            LocalHlsPackageVerifier.LocalHlsVerificationReport report,
            HlsLadderPlanner.HlsPackagingPlan plan
    ) {
        if (manifest.objectCount() != report.objectCount()) {
            throw new IllegalStateException(
                    "Inventory object count (" + manifest.objectCount()
                            + ") mismatches LocalHlsVerificationReport (" + report.objectCount() + ")."
            );
        }
        if (manifest.totalSizeBytes() != report.totalSizeBytes()) {
            throw new IllegalStateException(
                    "Inventory total bytes (" + manifest.totalSizeBytes()
                            + ") mismatches LocalHlsVerificationReport (" + report.totalSizeBytes() + ")."
            );
        }
        ContentHash expectedMasterSha = ContentHash.of(report.masterManifestSha256());
        if (!manifest.masterManifestSha256().equals(expectedMasterSha)) {
            throw new IllegalStateException(
                    "Inventory master.m3u8 SHA-256 (" + manifest.masterManifestSha256()
                            + ") mismatches LocalHlsVerificationReport (" + expectedMasterSha + ")."
            );
        }

        Map<String, Integer> observedSegmentsPerPrefix = new HashMap<>();
        boolean hasAudioArtifact = false;
        for (HlsArtifactDescriptor descriptor : manifest.orderedArtifacts()) {
            String rel = descriptor.relativePath();
            if (rel.startsWith("audio/")) {
                hasAudioArtifact = true;
            }
            if (descriptor.artifactType() == HlsArtifactType.MEDIA_SEGMENT) {
                int lastSlash = rel.lastIndexOf('/');
                String parentDir = rel.substring(0, lastSlash);
                observedSegmentsPerPrefix.merge(parentDir, 1, Integer::sum);
            }
        }
        if (plan.includeAudio() != hasAudioArtifact) {
            throw new IllegalStateException(
                    "Audio artifact presence mismatch: plan.includeAudio=" + plan.includeAudio()
                            + ", hasAudioArtifact=" + hasAudioArtifact
            );
        }
        if (plan.includeAudio()) {
            int audioSegs = observedSegmentsPerPrefix.getOrDefault("audio/main", 0);
            if (audioSegs != report.audioSegmentCount()) {
                throw new IllegalStateException(
                        "Audio segment count mismatch: inventory=" + audioSegs + ", report=" + report.audioSegmentCount()
                );
            }
        }
        for (HlsLadderPlanner.VideoTierSpec tier : plan.videoTiers()) {
            String tierDir = "video/" + tier.label();
            int actualSegs = observedSegmentsPerPrefix.getOrDefault(tierDir, 0);
            int expectedSegs = report.videoSegmentCountsByTier().getOrDefault(tier.label(), -1);
            if (actualSegs != expectedSegs) {
                throw new IllegalStateException(
                        "Video tier '" + tier.label() + "' segment count mismatch: inventory="
                                + actualSegs + ", report=" + expectedSegs
                );
            }
        }
    }

    private static void validateLimits(int maxObjectCount, long maxSingleObjectBytes) {
        if (maxObjectCount < HlsExpectedArtifactManifest.MIN_OBJECT_COUNT || maxObjectCount > MAX_ALLOWED_OBJECT_COUNT) {
            throw new IllegalArgumentException(
                    "maxObjectCount must be between " + HlsExpectedArtifactManifest.MIN_OBJECT_COUNT
                            + " and " + MAX_ALLOWED_OBJECT_COUNT + ", got: " + maxObjectCount
            );
        }
        if (maxSingleObjectBytes <= 0L || maxSingleObjectBytes > MAX_ALLOWED_SINGLE_OBJECT_BYTES) {
            throw new IllegalArgumentException(
                    "maxSingleObjectBytes must be between 1 and " + MAX_ALLOWED_SINGLE_OBJECT_BYTES
                            + ", got: " + maxSingleObjectBytes
            );
        }
    }

    private static ContentHash streamSha256(Path file, long expectedSizeBytes) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 digest algorithm unavailable.", e);
        }
        byte[] buffer = new byte[STREAM_HASH_BUFFER_BYTES];
        long totalRead = 0L;
        try (InputStream in = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
            int read;
            while ((read = in.read(buffer)) != -1) {
                if (read > 0) {
                    digest.update(buffer, 0, read);
                    totalRead += read;
                }
            }
        }
        if (totalRead != expectedSizeBytes) {
            throw new IOException(
                    "Concurrent file size change while streaming SHA-256 for " + file.getFileName()
                            + ": expected=" + expectedSizeBytes + ", read=" + totalRead
            );
        }
        return ContentHash.of(HexFormat.of().formatHex(digest.digest()).toLowerCase(Locale.ROOT));
    }
}
