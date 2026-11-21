package com.universe.media.infrastructure.hls;

import com.universe.media.application.exceptions.MediaAssetVersionContentHashMismatchException;
import com.universe.media.application.exceptions.StorageException;
import com.universe.media.application.exceptions.StorageObjectNotFoundException;
import com.universe.media.application.exceptions.UploadContentMimeMismatchException;
import com.universe.media.application.hls.DownloadedSourceScope;
import com.universe.media.application.hls.MediaVideoSourceSnapshot;
import com.universe.media.application.ports.storage.BinaryStoragePort;
import com.universe.media.application.ports.storage.StorageProviderResolverPort;
import com.universe.media.application.ports.storage.StoredBinaryObject;
import com.universe.media.domain.ContentHash;
import com.universe.media.domain.ContentHashStatus;
import com.universe.media.domain.MimeType;
import com.universe.media.domain.StorageKey;
import com.universe.media.domain.StorageProviderId;
import com.universe.media.infrastructure.storage.local.LocalFilesystemStorageAdapter;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.services.s3.model.S3Exception;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MediaVideoSourceDownloaderTest {

    private record RangeCall(StorageKey key, long start, long length) {
    }

    private static byte[] deterministicBytes(int length) {
        byte[] data = new byte[length];
        for (int i = 0; i < length; i++) {
            data[i] = (byte) ((i * 31 + 17) & 0xFF);
        }
        return data;
    }

    private static ContentHash sha256(byte[] data) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return ContentHash.of(HexFormat.of().formatHex(md.digest(data)).toLowerCase(Locale.ROOT));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static MediaVideoSourceSnapshot snapshot(
            String provider,
            String key,
            byte[] payload,
            ContentHashStatus hashStatus
    ) {
        return snapshotWithHash(provider, key, payload.length, sha256(payload), hashStatus, "video/mp4");
    }

    private static MediaVideoSourceSnapshot snapshotWithHash(
            String provider,
            String key,
            long sizeBytes,
            ContentHash expectedHash,
            ContentHashStatus hashStatus,
            String mimeType
    ) {
        return new MediaVideoSourceSnapshot(
                UUID.randomUUID(),
                UUID.randomUUID(),
                1,
                StorageProviderId.of(provider),
                StorageKey.of(key),
                expectedHash,
                hashStatus,
                sizeBytes,
                MimeType.of(mimeType)
        );
    }

    private static HlsSourceDownloadProperties smallChunkProperties(Path tempRoot, int retries) {
        return new HlsSourceDownloadProperties(
                tempRoot,
                4096L,
                100L * 1024L * 1024L,
                retries,
                0L,
                Duration.ofMinutes(5),
                1024L
        );
    }

    private static BinaryStoragePort fakeRangeProvider(
            String provider,
            byte[] data,
            List<RangeCall> recordedCalls
    ) {
        StorageProviderId id = StorageProviderId.of(provider);
        return new BinaryStoragePort() {
            @Override
            public StorageProviderId providerId() {
                return id;
            }

            @Override
            public StoredBinaryObject store(StorageKey key, InputStream content, long sizeBytes, MimeType mimeType) {
                throw new UnsupportedOperationException();
            }

            @Override
            public InputStream open(StorageKey key) {
                throw new AssertionError("Whole-object open() must never be called by MediaVideoSourceDownloader");
            }

            @Override
            public InputStream openRange(StorageKey key, long startInclusive, long length) {
                if (recordedCalls != null) {
                    recordedCalls.add(new RangeCall(key, startInclusive, length));
                }
                int start = (int) startInclusive;
                int end = (int) Math.min(data.length, startInclusive + length);
                if (start >= data.length) {
                    return new ByteArrayInputStream(new byte[0]);
                }
                return new ByteArrayInputStream(Arrays.copyOfRange(data, start, end));
            }

            @Override
            public void delete(StorageKey key) {
            }
        };
    }

    private static void assertNoFilesInDirectory(Path dir) throws IOException {
        if (!Files.exists(dir)) {
            return;
        }
        try (Stream<Path> s = Files.list(dir)) {
            assertThat(s.toList()).isEmpty();
        }
    }

    // =========================================================================
    // 1. Dormancy & Configuration Validation
    // =========================================================================

    @Test
    @DisplayName("Dormant by default and when disabled: zero source download beans register")
    void dormantByDefaultAndWhenDisabled(@TempDir Path tempDir) {
        ApplicationContextRunner base = new ApplicationContextRunner()
                .withBean(StorageProviderResolverPort.class, () -> id -> {
                    throw new AssertionError("Should never resolve provider");
                })
                .withUserConfiguration(
                        HlsPackagingProperties.class,
                        HlsSourceDownloadProperties.class,
                        MediaVideoSourceDownloader.class
                );

        base.run(ctx -> {
            assertThat(ctx).hasNotFailed();
            assertThat(ctx).doesNotHaveBean(HlsSourceDownloadProperties.class);
            assertThat(ctx).doesNotHaveBean(MediaVideoSourceDownloader.class);
        });

        base.withPropertyValues(
                "media.hls.packaging.enabled=false",
                "media.hls.source-download.chunk-size-bytes=1",
                "media.hls.source-download.temp-root-dir=" + tempDir.toAbsolutePath()
        ).run(ctx -> {
            assertThat(ctx).hasNotFailed();
            assertThat(ctx).doesNotHaveBean(HlsSourceDownloadProperties.class);
            assertThat(ctx).doesNotHaveBean(MediaVideoSourceDownloader.class);
        });
    }

    @Test
    @DisplayName("Validates config bounds, rejects worktree root, and rejects overlap with packaging scratchRootDir")
    void validatesConfigBoundsWorktreeAndScratchOverlap(@TempDir Path tempDir) {
        Path externalSource = tempDir.resolve("sources");
        Path externalScratch = tempDir.resolve("scratch");

        assertThatThrownBy(() -> new HlsSourceDownloadProperties(
                null, 8192, 1024 * 1024, 3, 100, Duration.ofMinutes(5), 0
        )).isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> new HlsSourceDownloadProperties(
                externalSource, 1024, 1024 * 1024, 3, 100, Duration.ofMinutes(5), 0
        )).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("chunk-size-bytes");

        assertThatThrownBy(() -> new HlsSourceDownloadProperties(
                externalSource, 128L * 1024 * 1024, 1024 * 1024, 3, 100, Duration.ofMinutes(5), 0
        )).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("chunk-size-bytes");

        assertThatThrownBy(() -> new HlsSourceDownloadProperties(
                externalSource, 8192, 0L, 3, 100, Duration.ofMinutes(5), 0
        )).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("max-source-bytes");

        assertThatThrownBy(() -> new HlsSourceDownloadProperties(
                externalSource, 8192, 1024 * 1024, -1, 100, Duration.ofMinutes(5), 0
        )).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("max-chunk-retries");

        assertThatThrownBy(() -> new HlsSourceDownloadProperties(
                externalSource, 8192, 1024 * 1024, 3, -1L, Duration.ofMinutes(5), 0
        )).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("retry-backoff-ms");

        assertThatThrownBy(() -> new HlsSourceDownloadProperties(
                externalSource, 8192, 1024 * 1024, 3, 100, Duration.ZERO, 0
        )).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("total-timeout");

        assertThatThrownBy(() -> new HlsSourceDownloadProperties(
                externalSource, 8192, 1024 * 1024, 3, 100, Duration.ofMinutes(5), -1L
        )).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("min-free-disk-bytes");

        // Worktree containment rejection
        Path insideWorktree = Path.of("").toAbsolutePath().normalize().resolve("target/bad-hls-src");
        assertThatThrownBy(() -> new HlsSourceDownloadProperties(
                insideWorktree, 8192, 1024 * 1024, 3, 100, Duration.ofMinutes(5), 0
        )).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("worktree");

        // Overlap with packaging scratchRootDir (equal, child, parent)
        assertThatThrownBy(() -> new HlsSourceDownloadProperties(
                externalScratch, 8192, 1024 * 1024, 3, 100, Duration.ofMinutes(5), 0, externalScratch
        )).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("separate");

        assertThatThrownBy(() -> new HlsSourceDownloadProperties(
                externalScratch.resolve("nested"), 8192, 1024 * 1024, 3, 100, Duration.ofMinutes(5), 0, externalScratch
        )).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("separate");

        assertThatThrownBy(() -> new HlsSourceDownloadProperties(
                tempDir, 8192, 1024 * 1024, 3, 100, Duration.ofMinutes(5), 0, externalScratch
        )).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("separate");
    }

    // =========================================================================
    // 2. Happy Paths & Multi-Provider Routing
    // =========================================================================

    @Test
    @DisplayName("Downloads single-chunk and multi-chunk MP4 with final partial chunk, retaining verified file until scope close")
    void downloadsSingleAndMultiChunkWithExactRangeRequests(@TempDir Path tempDir) throws Exception {
        Path sourceRoot = tempDir.resolve("sources");
        HlsSourceDownloadProperties props = smallChunkProperties(sourceRoot, 1);

        // Single-chunk payload (2,000 bytes < 4096 chunk size)
        byte[] smallData = deterministicBytes(2_000);
        List<RangeCall> smallCalls = new ArrayList<>();
        StorageProviderResolverPort smallResolver = p -> fakeRangeProvider("b2", smallData, smallCalls);
        MediaVideoSourceDownloader smallDownloader = new MediaVideoSourceDownloader(smallResolver, props);

        Path verifiedSmallPath;
        try (DownloadedSourceScope scope = smallDownloader.downloadVerifiedSource(
                snapshot("b2", "objects/small.mp4", smallData, null))) {
            assertThat(smallCalls).containsExactly(new RangeCall(StorageKey.of("objects/small.mp4"), 0L, 2_000L));
            verifiedSmallPath = scope.sourceFile();
            assertThat(Files.isRegularFile(verifiedSmallPath)).isTrue();
            assertThat(Files.readAllBytes(verifiedSmallPath)).isEqualTo(smallData);
            assertThat(scope.verifiedSizeBytes()).isEqualTo(2_000L);
            assertThat(scope.verifiedSha256()).isEqualTo(sha256(smallData));

            if (FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) {
                Set<PosixFilePermission> perms = Files.getPosixFilePermissions(verifiedSmallPath);
                assertThat(perms).containsExactlyInAnyOrder(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);
            }
        }
        assertThat(Files.exists(verifiedSmallPath)).isFalse();
        assertNoFilesInDirectory(sourceRoot);

        // Multi-chunk with final partial chunk (10,000 = 4096 + 4096 + 1808)
        byte[] multiData = deterministicBytes(10_000);
        List<RangeCall> multiCalls = new ArrayList<>();
        StorageProviderResolverPort multiResolver = p -> fakeRangeProvider("r2-movie", multiData, multiCalls);
        MediaVideoSourceDownloader multiDownloader = new MediaVideoSourceDownloader(multiResolver, props);

        try (DownloadedSourceScope scope = multiDownloader.downloadVerifiedSource(
                snapshot("r2-movie", "objects/multi.mp4", multiData, ContentHashStatus.CLIENT_DECLARED_UNVERIFIED))) {
            assertThat(multiCalls).containsExactly(
                    new RangeCall(StorageKey.of("objects/multi.mp4"), 0L, 4096L),
                    new RangeCall(StorageKey.of("objects/multi.mp4"), 4096L, 4096L),
                    new RangeCall(StorageKey.of("objects/multi.mp4"), 8192L, 1808L)
            );
            assertThat(Files.readAllBytes(scope.sourceFile())).isEqualTo(multiData);
            assertThat(scope.verifiedSha256()).isEqualTo(sha256(multiData));
        }
        assertNoFilesInDirectory(sourceRoot);
    }

    @Test
    @DisplayName("Routes across fake b2, r2-movie, r2, and real LocalFilesystemStorageAdapter (local)")
    void routesAcrossAllSupportedProvidersIncludingLocalAdapter(@TempDir Path tempDir) throws Exception {
        Path localStoreRoot = tempDir.resolve("local-store");
        LocalFilesystemStorageAdapter localAdapter = new LocalFilesystemStorageAdapter(localStoreRoot);

        byte[] localPayload = deterministicBytes(9_500);
        StorageKey localKey = StorageKey.of("objects/local-video.mp4");
        localAdapter.store(localKey, new ByteArrayInputStream(localPayload), localPayload.length, MimeType.of("video/mp4"));

        byte[] b2Payload = deterministicBytes(5_000);
        byte[] r2MoviePayload = deterministicBytes(6_000);
        byte[] r2Payload = deterministicBytes(7_000);

        Map<StorageProviderId, BinaryStoragePort> ports = Map.of(
                StorageProviderId.of("local"), localAdapter,
                StorageProviderId.of("b2"), fakeRangeProvider("b2", b2Payload, null),
                StorageProviderId.of("r2-movie"), fakeRangeProvider("r2-movie", r2MoviePayload, null),
                StorageProviderId.of("r2"), fakeRangeProvider("r2", r2Payload, null)
        );
        StorageProviderResolverPort resolver = id -> {
            BinaryStoragePort port = ports.get(id);
            if (port == null) {
                throw new StorageException("Unsupported provider: " + id.value());
            }
            return port;
        };

        Path sourceRoot = tempDir.resolve("source-dl");
        MediaVideoSourceDownloader downloader = new MediaVideoSourceDownloader(resolver, smallChunkProperties(sourceRoot, 1));

        for (var entry : Map.of(
                "local", Map.entry(localKey.value(), localPayload),
                "b2", Map.entry("objects/b2.mp4", b2Payload),
                "r2-movie", Map.entry("objects/r2m.mp4", r2MoviePayload),
                "r2", Map.entry("objects/r2.mp4", r2Payload)
        ).entrySet()) {
            try (DownloadedSourceScope scope = downloader.downloadVerifiedSource(
                    snapshot(entry.getKey(), entry.getValue().getKey(), entry.getValue().getValue(), null))) {
                assertThat(Files.readAllBytes(scope.sourceFile())).isEqualTo(entry.getValue().getValue());
            }
        }
        assertNoFilesInDirectory(sourceRoot);
    }

    // =========================================================================
    // 3. Non-Retryable, Retry, Partial-Chunk Rollback & Corruption Tests
    // =========================================================================

    @Test
    @DisplayName("Fails fast without retries on unknown provider, missing object (404), and authorization failure (403)")
    void failsFastOnNonRetryableProviderMissingAndForbidden(@TempDir Path tempDir) throws Exception {
        Path sourceRoot = tempDir.resolve("sources");
        HlsSourceDownloadProperties props = smallChunkProperties(sourceRoot, 3);
        byte[] sample = deterministicBytes(5_000);

        // Unknown provider
        MediaVideoSourceDownloader unknownDownloader = new MediaVideoSourceDownloader(
                id -> { throw new StorageException("Unknown provider: " + id.value()); },
                props
        );
        assertThatThrownBy(() -> unknownDownloader.downloadVerifiedSource(
                snapshot("unknown-provider", "objects/x.mp4", sample, null)))
                .isInstanceOf(StorageException.class);
        assertNoFilesInDirectory(sourceRoot);

        // Missing object (404)
        AtomicInteger missingCalls = new AtomicInteger();
        MediaVideoSourceDownloader missingDownloader = new MediaVideoSourceDownloader(
                id -> new BinaryStoragePort() {
                    @Override public StorageProviderId providerId() { return id; }
                    @Override public StoredBinaryObject store(StorageKey k, InputStream c, long s, MimeType m) { throw new UnsupportedOperationException(); }
                    @Override public InputStream open(StorageKey k) { throw new UnsupportedOperationException(); }
                    @Override public InputStream openRange(StorageKey k, long s, long l) {
                        missingCalls.incrementAndGet();
                        throw new StorageObjectNotFoundException(k);
                    }
                    @Override public void delete(StorageKey k) {}
                },
                props
        );
        assertThatThrownBy(() -> missingDownloader.downloadVerifiedSource(
                snapshot("b2", "objects/missing.mp4", sample, null)))
                .isInstanceOf(StorageObjectNotFoundException.class);
        assertThat(missingCalls.get()).isEqualTo(1);
        assertNoFilesInDirectory(sourceRoot);

        // Authorization failure (403 Forbidden)
        AtomicInteger forbiddenCalls = new AtomicInteger();
        MediaVideoSourceDownloader forbiddenDownloader = new MediaVideoSourceDownloader(
                id -> new BinaryStoragePort() {
                    @Override public StorageProviderId providerId() { return id; }
                    @Override public StoredBinaryObject store(StorageKey k, InputStream c, long s, MimeType m) { throw new UnsupportedOperationException(); }
                    @Override public InputStream open(StorageKey k) { throw new UnsupportedOperationException(); }
                    @Override public InputStream openRange(StorageKey k, long s, long l) {
                        forbiddenCalls.incrementAndGet();
                        throw new StorageException("Access denied", S3Exception.builder().statusCode(403).message("Forbidden").build());
                    }
                    @Override public void delete(StorageKey k) {}
                },
                props
        );
        assertThatThrownBy(() -> forbiddenDownloader.downloadVerifiedSource(
                snapshot("r2-movie", "objects/forbidden.mp4", sample, null)))
                .isInstanceOf(StorageException.class);
        assertThat(forbiddenCalls.get()).isEqualTo(1);
        assertNoFilesInDirectory(sourceRoot);
    }

    @Test
    @DisplayName("Transient mid-chunk failure rolls back partial chunk bytes cleanly before retry without byte duplication")
    void transientPartialChunkFailureRollsBackAndRetriesCleanly(@TempDir Path tempDir) throws Exception {
        Path sourceRoot = tempDir.resolve("sources");
        HlsSourceDownloadProperties props = smallChunkProperties(sourceRoot, 3);
        byte[] data = deterministicBytes(10_000); // chunks: [0,4096), [4096,4096), [8192,1808)

        AtomicInteger chunk1Attempts = new AtomicInteger();
        AtomicInteger chunk0Calls = new AtomicInteger();
        AtomicInteger chunk2Attempts = new AtomicInteger();

        BinaryStoragePort flakyPort = new BinaryStoragePort() {
            @Override public StorageProviderId providerId() { return StorageProviderId.of("r2-movie"); }
            @Override public StoredBinaryObject store(StorageKey k, InputStream c, long s, MimeType m) { throw new UnsupportedOperationException(); }
            @Override public InputStream open(StorageKey k) { throw new UnsupportedOperationException(); }
            @Override public void delete(StorageKey k) {}

            @Override
            public InputStream openRange(StorageKey key, long startInclusive, long length) {
                if (startInclusive == 0L) {
                    chunk0Calls.incrementAndGet();
                    return new ByteArrayInputStream(data, 0, (int) length);
                }
                if (startInclusive == 4096L) {
                    int attempt = chunk1Attempts.incrementAndGet();
                    if (attempt == 1) {
                        // First attempt fails transiently BEFORE stream opens (HTTP 503)
                        throw new StorageException("503 SlowDown", S3Exception.builder().statusCode(503).message("Unavailable").build());
                    }
                    if (attempt == 2) {
                        // Second attempt writes 1,500 bytes, then throws mid-stream IOException!
                        return new InputStream() {
                            int pos = 0;

                            @Override
                            public int read() throws IOException {
                                byte[] b = new byte[1];
                                int n = read(b, 0, 1);
                                return n == -1 ? -1 : (b[0] & 0xFF);
                            }

                            @Override
                            public int read(byte[] buf, int off, int len) throws IOException {
                                if (pos >= 1500) {
                                    throw new SocketTimeoutException("Simulated mid-chunk socket timeout");
                                }
                                int toCopy = Math.min(len, 1500 - pos);
                                System.arraycopy(data, 4096 + pos, buf, off, toCopy);
                                pos += toCopy;
                                return toCopy;
                            }
                        };
                    }
                    return new ByteArrayInputStream(data, 4096, (int) length);
                }
                chunk2Attempts.incrementAndGet();
                return new ByteArrayInputStream(data, (int) startInclusive, (int) length);
            }
        };

        MediaVideoSourceDownloader downloader = new MediaVideoSourceDownloader(p -> flakyPort, props);
        try (DownloadedSourceScope scope = downloader.downloadVerifiedSource(
                snapshot("r2-movie", "objects/flaky.mp4", data, ContentHashStatus.CLIENT_DECLARED_UNVERIFIED))) {
            // Chunk 0 was NOT re-downloaded when Chunk 1 failed!
            assertThat(chunk0Calls.get()).isEqualTo(1);
            assertThat(chunk1Attempts.get()).isEqualTo(3);
            assertThat(chunk2Attempts.get()).isEqualTo(1);
            // Exact byte match proves no duplicated or corrupt partial chunk bytes remained!
            assertThat(Files.readAllBytes(scope.sourceFile())).isEqualTo(data);
            assertThat(scope.verifiedSha256()).isEqualTo(sha256(data));
        }
        assertNoFilesInDirectory(sourceRoot);
    }

    @Test
    @DisplayName("Rejects early EOF / truncated range, excess range bytes, and SHA-256 mismatch (including CLIENT_DECLARED_UNVERIFIED)")
    void rejectsTruncatedRangeExcessRangeAndShaMismatch(@TempDir Path tempDir) throws Exception {
        Path sourceRoot = tempDir.resolve("sources");
        HlsSourceDownloadProperties props = smallChunkProperties(sourceRoot, 2);
        byte[] data = deterministicBytes(5_000);

        // 1. Early EOF (returns only 3,000 bytes for 4,096 chunk)
        AtomicInteger eofCalls = new AtomicInteger();
        MediaVideoSourceDownloader eofDownloader = new MediaVideoSourceDownloader(
                p -> new BinaryStoragePort() {
                    @Override public StorageProviderId providerId() { return p; }
                    @Override public StoredBinaryObject store(StorageKey k, InputStream c, long s, MimeType m) { throw new UnsupportedOperationException(); }
                    @Override public InputStream open(StorageKey k) { throw new UnsupportedOperationException(); }
                    @Override public void delete(StorageKey k) {}
                    @Override public InputStream openRange(StorageKey k, long start, long len) {
                        eofCalls.incrementAndGet();
                        return new ByteArrayInputStream(data, 0, 3000);
                    }
                },
                props
        );
        assertThatThrownBy(() -> eofDownloader.downloadVerifiedSource(snapshot("b2", "objects/eof.mp4", data, null)))
                .isInstanceOf(StorageException.class)
                .hasMessageContaining("early EOF");
        assertThat(eofCalls.get()).isEqualTo(1);
        assertNoFilesInDirectory(sourceRoot);

        // 2. Excess range bytes returned by broken provider
        MediaVideoSourceDownloader excessDownloader = new MediaVideoSourceDownloader(
                p -> new BinaryStoragePort() {
                    @Override public StorageProviderId providerId() { return p; }
                    @Override public StoredBinaryObject store(StorageKey k, InputStream c, long s, MimeType m) { throw new UnsupportedOperationException(); }
                    @Override public InputStream open(StorageKey k) { throw new UnsupportedOperationException(); }
                    @Override public void delete(StorageKey k) {}
                    @Override public InputStream openRange(StorageKey k, long start, long len) {
                        return new ByteArrayInputStream(deterministicBytes((int) len + 10));
                    }
                },
                props
        );
        assertThatThrownBy(() -> excessDownloader.downloadVerifiedSource(snapshot("b2", "objects/excess.mp4", data, null)))
                .isInstanceOf(StorageException.class)
                .hasMessageContaining("exceeded requested length");
        assertNoFilesInDirectory(sourceRoot);

        // 3. Corrupt source bytes / SHA-256 mismatch for CLIENT_DECLARED_UNVERIFIED
        byte[] corrupted = Arrays.copyOf(data, data.length);
        corrupted[4500] ^= (byte) 0xFF;
        MediaVideoSourceDownloader mismatchDownloader = new MediaVideoSourceDownloader(
                p -> fakeRangeProvider("r2-movie", corrupted, null),
                props
        );
        MediaVideoSourceSnapshot unverifiedMismatchSnapshot = new MediaVideoSourceSnapshot(
                UUID.randomUUID(), UUID.randomUUID(), 1,
                StorageProviderId.of("r2-movie"), StorageKey.of("objects/corrupt.mp4"),
                sha256(data), ContentHashStatus.CLIENT_DECLARED_UNVERIFIED,
                data.length, MimeType.of("video/mp4")
        );
        assertThatThrownBy(() -> mismatchDownloader.downloadVerifiedSource(unverifiedMismatchSnapshot))
                .isInstanceOf(MediaAssetVersionContentHashMismatchException.class);
        assertNoFilesInDirectory(sourceRoot);
    }

    // =========================================================================
    // 4. Interruption, Timeout, Disk Space, Symlink Escape & Scope Lifecycle
    // =========================================================================

    @Test
    @DisplayName("Aborts on thread interruption, timeout budget, insufficient disk, overflow, and symlinked tempRootDir")
    void abortsOnInterruptionTimeoutDiskAndSymlink(@TempDir Path tempDir) throws Exception {
        byte[] data = deterministicBytes(8_192);
        Path sourceRoot = tempDir.resolve("sources");
        HlsSourceDownloadProperties props = smallChunkProperties(sourceRoot, 2);

        // 1. Thread interruption closes active stream and preserves interrupt flag
        AtomicBoolean streamClosed = new AtomicBoolean(false);
        MediaVideoSourceDownloader interruptDownloader = new MediaVideoSourceDownloader(
                p -> new BinaryStoragePort() {
                    @Override public StorageProviderId providerId() { return p; }
                    @Override public StoredBinaryObject store(StorageKey k, InputStream c, long s, MimeType m) { throw new UnsupportedOperationException(); }
                    @Override public InputStream open(StorageKey k) { throw new UnsupportedOperationException(); }
                    @Override public void delete(StorageKey k) {}
                    @Override public InputStream openRange(StorageKey k, long start, long len) {
                        return new InputStream() {
                            @Override
                            public int read() {
                                Thread.currentThread().interrupt();
                                return 1;
                            }

                            @Override
                            public int read(byte[] b, int off, int l) {
                                Thread.currentThread().interrupt();
                                b[off] = 1;
                                return 1;
                            }

                            @Override
                            public void close() {
                                streamClosed.set(true);
                            }
                        };
                    }
                },
                props
        );
        try {
            assertThatThrownBy(() -> interruptDownloader.downloadVerifiedSource(
                    snapshot("b2", "objects/interrupt.mp4", data, null)))
                    .isInstanceOf(StorageException.class)
                    .hasMessageContaining("interrupted");
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
            assertThat(streamClosed.get()).isTrue();
        } finally {
            Thread.interrupted(); // clear interrupt flag
        }
        assertNoFilesInDirectory(sourceRoot);

        // 2. Clean non-interrupt local disk write failure (e.g., disk fills mid-download): non-retryable & cleans temp file
        AtomicInteger localDiskFailRangeCalls = new AtomicInteger();
        FileChannel failingChannel = org.mockito.Mockito.mock(FileChannel.class);
        org.mockito.Mockito.when(failingChannel.write(org.mockito.ArgumentMatchers.any(ByteBuffer.class)))
                .thenThrow(new IOException("No space left on device"));
        MediaVideoSourceDownloader localIoFailDownloader = new MediaVideoSourceDownloader(
                p -> {
                    localDiskFailRangeCalls.incrementAndGet();
                    return fakeRangeProvider("b2", data, null);
                },
                props,
                null,
                dir -> 100_000_000L,
                System::nanoTime,
                ms -> {},
                partPath -> failingChannel
        );
        assertThatThrownBy(() -> localIoFailDownloader.downloadVerifiedSource(
                snapshot("b2", "objects/diskfull.mp4", data, null)))
                .isInstanceOf(StorageException.class)
                .hasMessageContaining("Local I/O failure");
        assertThat(localDiskFailRangeCalls.get()).isEqualTo(1);
        org.mockito.Mockito.verify(failingChannel).close();
        assertNoFilesInDirectory(sourceRoot);

        // 3. Total download timeout budget exceeded
        AtomicLong clock = new AtomicLong(1_000_000_000L);
        HlsSourceDownloadProperties shortTimeoutProps = new HlsSourceDownloadProperties(
                sourceRoot, 4096L, 100L * 1024L * 1024L, 2, 10L, Duration.ofMillis(100), 0L
        );
        MediaVideoSourceDownloader timeoutDownloader = new MediaVideoSourceDownloader(
                p -> fakeRangeProvider("b2", data, null),
                shortTimeoutProps,
                null,
                dir -> 100_000_000L,
                () -> clock.getAndAdd(200_000_000L), // jumps +200ms per check > 100ms timeout
                ms -> {}
        );
        assertThatThrownBy(() -> timeoutDownloader.downloadVerifiedSource(
                snapshot("b2", "objects/timeout.mp4", data, null)))
                .isInstanceOf(StorageException.class)
                .hasMessageContaining("timeout budget");
        assertNoFilesInDirectory(sourceRoot);

        // 3. Insufficient disk & arithmetic overflow
        AtomicBoolean openRangeCalled = new AtomicBoolean(false);
        BinaryStoragePort delegatePort = fakeRangeProvider("b2", data, null);
        BinaryStoragePort recordingPort = new BinaryStoragePort() {
            @Override public StorageProviderId providerId() { return delegatePort.providerId(); }
            @Override public StoredBinaryObject store(StorageKey key, InputStream content, long sizeBytes, MimeType mimeType) {
                return delegatePort.store(key, content, sizeBytes, mimeType);
            }
            @Override public InputStream open(StorageKey key) {
                return delegatePort.open(key);
            }
            @Override public InputStream openRange(StorageKey key, long startInclusive, long length) {
                openRangeCalled.set(true);
                return delegatePort.openRange(key, startInclusive, length);
            }
            @Override public void delete(StorageKey key) {
                delegatePort.delete(key);
            }
        };
        MediaVideoSourceDownloader lowDiskDownloader = new MediaVideoSourceDownloader(
                p -> recordingPort,
                props,
                null,
                dir -> 4000L, // < 8192 + 1024
                System::nanoTime,
                ms -> {}
        );
        assertThatThrownBy(() -> lowDiskDownloader.downloadVerifiedSource(
                snapshot("b2", "objects/lowdisk.mp4", data, null)))
                .isInstanceOf(StorageException.class)
                .hasMessageContaining("Insufficient usable disk space");
        assertThat(openRangeCalled.get()).isFalse();

        // Overflow check (minFreeDiskBytes = Long.MAX_VALUE)
        HlsSourceDownloadProperties overflowProps = new HlsSourceDownloadProperties(
                sourceRoot, 4096L, 100L * 1024L * 1024L, 1, 0L, Duration.ofMinutes(1), Long.MAX_VALUE
        );
        MediaVideoSourceDownloader overflowDownloader = new MediaVideoSourceDownloader(
                p -> fakeRangeProvider("b2", data, null),
                overflowProps
        );
        assertThatThrownBy(() -> overflowDownloader.downloadVerifiedSource(
                snapshot("b2", "objects/overflow.mp4", data, null)))
                .isInstanceOf(StorageException.class)
                .hasMessageContaining("Overflow");

        // 4. Unsupported MIME / invalid snapshot size fails closed at downloader boundary
        assertThatThrownBy(() -> lowDiskDownloader.downloadVerifiedSource(
                snapshotWithHash("b2", "objects/bad.webm", 5000L, sha256(data), null, "video/webm")))
                .isInstanceOf(UploadContentMimeMismatchException.class);

        // 5. Symlink tempRootDir rejection
        Path realTarget = tempDir.resolve("real-target");
        Files.createDirectories(realTarget);
        Path symlinkRoot = tempDir.resolve("symlink-sources");
        try {
            Files.createSymbolicLink(symlinkRoot, realTarget);
        } catch (UnsupportedOperationException | IOException e) {
            Assumptions.assumeTrue(false, "OS/user privileges disallow symlinks: " + e.getMessage());
        }
        MediaVideoSourceDownloader symlinkDownloader = new MediaVideoSourceDownloader(
                p -> fakeRangeProvider("b2", data, null),
                smallChunkProperties(symlinkRoot, 1)
        );
        assertThatThrownBy(() -> symlinkDownloader.downloadVerifiedSource(
                snapshot("b2", "objects/symlink.mp4", data, null)))
                .isInstanceOf(StorageException.class)
                .hasMessageContaining("symbolic link");
        assertNoFilesInDirectory(realTarget);
    }

    @Test
    @DisplayName("Scope close recovers from transient deletion failures, serializes concurrent close, and refuses directories or symlinks")
    void scopeLifecycleDoubleCloseAndSiblingPreservation(@TempDir Path tempDir) throws Exception {
        Path sourceRoot = tempDir.resolve("sources");
        Files.createDirectories(sourceRoot);
        Path unrelatedFile = sourceRoot.resolve("keep-me.txt");
        Files.writeString(unrelatedFile, "unrelated", StandardCharsets.UTF_8);

        byte[] data = deterministicBytes(3_000);
        MediaVideoSourceDownloader downloader = new MediaVideoSourceDownloader(
                p -> fakeRangeProvider("b2", data, null),
                smallChunkProperties(sourceRoot, 1)
        );

        DownloadedSourceScope scope = downloader.downloadVerifiedSource(
                snapshot("b2", "objects/scope.mp4", data, null)
        );
        Path verifiedPath = scope.sourceFile();
        assertThat(Files.exists(verifiedPath)).isTrue();
        assertThat(scope.isClosed()).isFalse();

        scope.close();
        assertThat(scope.isClosed()).isTrue();
        assertThat(Files.exists(verifiedPath)).isFalse();
        assertThat(Files.exists(unrelatedFile)).isTrue();

        // Double close after confirmed deletion is idempotent
        scope.close();
        assertThat(scope.isClosed()).isTrue();
        assertThat(Files.exists(unrelatedFile)).isTrue();

        // Post-close sourceFile() throws IllegalStateException
        assertThatThrownBy(scope::sourceFile)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("closed");

        // Recoverable cleanup failure: transient IOException leaves file intact for retry while sourceFile() remains closed
        Path retryTarget = sourceRoot.resolve("hls-src-retry.mp4");
        Files.write(retryTarget, data);
        AtomicBoolean failDelete = new AtomicBoolean(true);
        AtomicInteger deleteAttempts = new AtomicInteger(0);
        MediaVideoSourceDownloader.LocalDownloadedSourceScope retryScope =
                new MediaVideoSourceDownloader.LocalDownloadedSourceScope(
                        sourceRoot,
                        retryTarget,
                        snapshot("b2", "objects/scope.mp4", data, null),
                        sha256(data),
                        data.length,
                        path -> {
                            deleteAttempts.incrementAndGet();
                            if (failDelete.get()) {
                                throw new IOException("Transient file lock preventing unlink");
                            }
                            return Files.deleteIfExists(path);
                        }
                );

        assertThatThrownBy(retryScope::close)
                .isInstanceOf(IOException.class)
                .hasMessageContaining("Transient file lock");
        assertThat(retryScope.isClosed()).isTrue();
        assertThat(retryScope.isCleanupCompleted()).isFalse();
        assertThat(Files.exists(retryTarget)).isTrue();
        assertThatThrownBy(retryScope::sourceFile)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("closed");

        // Clear transient fault and explicitly retry close()
        failDelete.set(false);
        retryScope.close();
        assertThat(retryScope.isCleanupCompleted()).isTrue();
        assertThat(Files.exists(retryTarget)).isFalse();
        assertThat(Files.exists(unrelatedFile)).isTrue();
        assertThat(deleteAttempts.get()).isEqualTo(2);

        // Third close is a safe idempotent no-op without further deleter calls
        retryScope.close();
        assertThat(deleteAttempts.get()).isEqualTo(2);

        // Concurrent close() calls serialize cleanly and delete only once without touching sibling files
        Path concurrentTarget = sourceRoot.resolve("hls-src-concurrent.mp4");
        Files.write(concurrentTarget, data);
        AtomicInteger concurrentDeleteCalls = new AtomicInteger(0);
        CountDownLatch insideDeleteLatch = new CountDownLatch(1);
        CountDownLatch releaseDeleteLatch = new CountDownLatch(1);
        MediaVideoSourceDownloader.LocalDownloadedSourceScope concurrentScope =
                new MediaVideoSourceDownloader.LocalDownloadedSourceScope(
                        sourceRoot,
                        concurrentTarget,
                        snapshot("b2", "objects/concurrent.mp4", data, null),
                        sha256(data),
                        data.length,
                        path -> {
                            concurrentDeleteCalls.incrementAndGet();
                            insideDeleteLatch.countDown();
                            try {
                                releaseDeleteLatch.await(5, TimeUnit.SECONDS);
                            } catch (InterruptedException ie) {
                                Thread.currentThread().interrupt();
                                throw new IOException("Interrupted waiting in test deleter", ie);
                            }
                            return Files.deleteIfExists(path);
                        }
                );

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> f1 = pool.submit(() -> {
                concurrentScope.close();
                return null;
            });
            assertThat(insideDeleteLatch.await(5, TimeUnit.SECONDS)).isTrue();
            Future<?> f2 = pool.submit(() -> {
                concurrentScope.close();
                return null;
            });
            releaseDeleteLatch.countDown();
            f1.get(5, TimeUnit.SECONDS);
            f2.get(5, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }
        assertThat(concurrentDeleteCalls.get()).isEqualTo(1);
        assertThat(Files.exists(concurrentTarget)).isFalse();
        assertThat(Files.exists(unrelatedFile)).isTrue();

        // Non-empty directory, empty directory, and symlink replacements are ALWAYS refused on repeated calls
        Path nonEmptyDir = sourceRoot.resolve("hls-src-nonempty-dir.mp4");
        Files.createDirectories(nonEmptyDir);
        Files.writeString(nonEmptyDir.resolve("child"), "x", StandardCharsets.UTF_8);
        MediaVideoSourceDownloader.LocalDownloadedSourceScope nonEmptyDirScope =
                new MediaVideoSourceDownloader.LocalDownloadedSourceScope(
                        sourceRoot, nonEmptyDir, snapshot("b2", "objects/scope.mp4", data, null), sha256(data), data.length
                );
        assertThatThrownBy(nonEmptyDirScope::close)
                .isInstanceOf(IOException.class)
                .hasMessageContaining("Refusing unsafe cleanup");
        assertThat(nonEmptyDirScope.isClosed()).isTrue();
        assertThat(nonEmptyDirScope.isCleanupCompleted()).isFalse();
        assertThat(Files.isDirectory(nonEmptyDir)).isTrue();
        assertThatThrownBy(nonEmptyDirScope::close)
                .isInstanceOf(IOException.class)
                .hasMessageContaining("Refusing unsafe cleanup");
        assertThat(Files.exists(nonEmptyDir.resolve("child"))).isTrue();

        Path emptyDir = sourceRoot.resolve("hls-src-empty-dir.mp4");
        Files.createDirectories(emptyDir);
        MediaVideoSourceDownloader.LocalDownloadedSourceScope emptyDirScope =
                new MediaVideoSourceDownloader.LocalDownloadedSourceScope(
                        sourceRoot, emptyDir, snapshot("b2", "objects/scope.mp4", data, null), sha256(data), data.length
                );
        assertThatThrownBy(emptyDirScope::close)
                .isInstanceOf(IOException.class)
                .hasMessageContaining("Refusing unsafe cleanup");
        assertThat(Files.isDirectory(emptyDir)).isTrue();
        assertThat(Files.exists(unrelatedFile)).isTrue();

        // Symlink replacement is refused if OS permits creating symlinks
        Path maliciousTarget = tempDir.resolve("external-victim.txt");
        Files.writeString(maliciousTarget, "victim", StandardCharsets.UTF_8);
        Path symlinkTarget = sourceRoot.resolve("hls-src-symlink.mp4");
        try {
            Files.createSymbolicLink(symlinkTarget, maliciousTarget);
            MediaVideoSourceDownloader.LocalDownloadedSourceScope symlinkScope =
                    new MediaVideoSourceDownloader.LocalDownloadedSourceScope(
                            sourceRoot, symlinkTarget, snapshot("b2", "objects/scope.mp4", data, null), sha256(data), data.length
                    );
            assertThatThrownBy(symlinkScope::close)
                    .isInstanceOf(IOException.class)
                    .hasMessageContaining("Refusing unsafe cleanup");
            assertThat(Files.isSymbolicLink(symlinkTarget)).isTrue();
            assertThat(Files.exists(maliciousTarget)).isTrue();
        } catch (UnsupportedOperationException | IOException ignored) {
            // OS privileges disallow symlinks on this host
        }
    }

    @Test
    @DisplayName("Cleans up promoted .mp4 if post-move validation fails, preserves siblings, and retains suppressed cleanup errors")
    void cleansUpPromotedFileOnPostMoveValidationFailureAndPreservesSiblings(@TempDir Path tempDir) throws Exception {
        Path sourceRoot = tempDir.resolve("sources");
        Files.createDirectories(sourceRoot);
        Path unrelatedSibling = sourceRoot.resolve("keep-sibling.txt");
        Files.writeString(unrelatedSibling, "do-not-delete", StandardCharsets.UTF_8);

        byte[] data = deterministicBytes(3_500);
        AtomicReference<Path> observedMovedPath = new AtomicReference<>();

        MediaVideoSourceDownloader failingValidationDownloader = new MediaVideoSourceDownloader(
                p -> fakeRangeProvider("b2", data, null),
                smallChunkProperties(sourceRoot, 1),
                null,
                dir -> 100_000_000L,
                System::nanoTime,
                ms -> {},
                partFile -> FileChannel.open(partFile, StandardOpenOption.WRITE),
                (verifiedFile, expectedSize) -> {
                    observedMovedPath.set(verifiedFile);
                    assertThat(Files.isRegularFile(verifiedFile)).isTrue();
                    throw new IOException("Simulated post-move stat mismatch");
                },
                Files::deleteIfExists
        );

        assertThatThrownBy(() -> failingValidationDownloader.downloadVerifiedSource(
                snapshot("b2", "objects/post-move-fail.mp4", data, null)))
                .isInstanceOf(StorageException.class)
                .hasMessageContaining("Simulated post-move stat mismatch");

        assertThat(observedMovedPath.get()).isNotNull();
        assertThat(Files.exists(observedMovedPath.get())).isFalse();
        assertThat(Files.exists(unrelatedSibling)).isTrue();
        try (Stream<Path> entries = Files.list(sourceRoot)) {
            assertThat(entries.toList()).containsExactly(unrelatedSibling);
        }

        // Sub-case: post-move cleanup also fails -> original validation IOException is preserved and carries suppressed cleanup failure
        AtomicReference<Path> lockedMovedPath = new AtomicReference<>();
        MediaVideoSourceDownloader failingCleanupDownloader = new MediaVideoSourceDownloader(
                p -> fakeRangeProvider("b2", data, null),
                smallChunkProperties(sourceRoot, 1),
                null,
                dir -> 100_000_000L,
                System::nanoTime,
                ms -> {},
                partFile -> FileChannel.open(partFile, StandardOpenOption.WRITE),
                (verifiedFile, expectedSize) -> {
                    lockedMovedPath.set(verifiedFile);
                    throw new IOException("Primary post-move validation failure");
                },
                path -> {
                    if (path.getFileName().toString().endsWith(".mp4")) {
                        throw new IOException("Secondary unlink failure on promoted file");
                    }
                    return Files.deleteIfExists(path);
                }
        );

        assertThatThrownBy(() -> failingCleanupDownloader.downloadVerifiedSource(
                snapshot("b2", "objects/post-move-cleanup-fail.mp4", data, null)))
                .isInstanceOf(StorageException.class)
                .satisfies(ex -> {
                    assertThat(ex.getCause()).isInstanceOf(IOException.class);
                    assertThat(ex.getCause().getMessage()).contains("Primary post-move validation failure");
                    assertThat(ex.getCause().getSuppressed()).hasSize(1);
                    assertThat(ex.getCause().getSuppressed()[0]).isInstanceOf(IOException.class);
                    assertThat(ex.getCause().getSuppressed()[0].getMessage())
                            .contains("Secondary unlink failure on promoted file");
                });
        if (lockedMovedPath.get() != null) {
            Files.deleteIfExists(lockedMovedPath.get());
        }
        assertThat(Files.exists(unrelatedSibling)).isTrue();
    }

    @Test
    @DisplayName("Retains suppressed partial-file cleanup exception without masking primary download failure")
    void attachesSuppressedCleanupFailureWithoutHidingPrimaryDownloadFailure(@TempDir Path tempDir) throws Exception {
        Path sourceRoot = tempDir.resolve("sources");
        byte[] data = deterministicBytes(2_048);
        byte[] wrongBytes = deterministicBytes(2_048);
        wrongBytes[0] = (byte) (wrongBytes[0] ^ 0xFF);

        MediaVideoSourceDownloader downloader = new MediaVideoSourceDownloader(
                p -> fakeRangeProvider("b2", data, null),
                smallChunkProperties(sourceRoot, 0),
                null,
                dir -> 100_000_000L,
                System::nanoTime,
                ms -> {},
                partFile -> FileChannel.open(partFile, StandardOpenOption.WRITE),
                (vf, expectedSize) -> {},
                path -> {
                    throw new IOException("Simulated OS lock holding .part file");
                }
        );

        assertThatThrownBy(() -> downloader.downloadVerifiedSource(
                snapshotWithHash("b2", "objects/cleanup-suppressed.mp4", data.length, sha256(wrongBytes), null, "video/mp4")))
                .isInstanceOf(MediaAssetVersionContentHashMismatchException.class)
                .satisfies(ex -> {
                    assertThat(ex.getSuppressed()).hasSize(1);
                    assertThat(ex.getSuppressed()[0]).isInstanceOf(IOException.class);
                    assertThat(ex.getSuppressed()[0].getMessage()).contains("Simulated OS lock holding .part file");
                });
    }

    @Test
    @DisplayName("Aborts immediately when blocked range read advances clock past cooperative totalTimeout, skipping retry backoff")
    void abortsImmediatelyWhenBlockingReadAdvancesPastTotalTimeoutAndSkipsBackoff(@TempDir Path tempDir)
            throws Exception {
        Path sourceRoot = tempDir.resolve("sources");
        byte[] data = deterministicBytes(2_048);
        AtomicLong clockNanos = new AtomicLong(0L);
        AtomicInteger openRangeCalls = new AtomicInteger(0);
        AtomicInteger sleepCalls = new AtomicInteger(0);

        HlsSourceDownloadProperties timeoutProps = new HlsSourceDownloadProperties(
                sourceRoot,
                4096L,
                100L * 1024L * 1024L,
                3,
                500L,
                Duration.ofMillis(100),
                1024L
        );

        BinaryStoragePort slowSocketPort = new BinaryStoragePort() {
            @Override
            public StorageProviderId providerId() {
                return StorageProviderId.of("b2");
            }

            @Override
            public StoredBinaryObject store(StorageKey key, InputStream content, long sizeBytes, MimeType mimeType) {
                throw new UnsupportedOperationException();
            }

            @Override
            public InputStream open(StorageKey key) {
                throw new AssertionError("Whole-object open() must never be called");
            }

            @Override
            public InputStream openRange(StorageKey key, long startInclusive, long length) {
                openRangeCalls.incrementAndGet();
                return new InputStream() {
                    @Override
                    public int read() {
                        throw new AssertionError("Expected array read()");
                    }

                    @Override
                    public int read(byte[] b, int off, int len) throws IOException {
                        clockNanos.addAndGet(250_000_000L); // blocked 250ms > 100ms totalTimeout
                        throw new SocketTimeoutException("Simulated blocked socket read timeout");
                    }
                };
            }

            @Override
            public void delete(StorageKey key) {
            }
        };

        MediaVideoSourceDownloader downloader = new MediaVideoSourceDownloader(
                p -> slowSocketPort,
                timeoutProps,
                null,
                dir -> 100_000_000L,
                clockNanos::get,
                ms -> sleepCalls.incrementAndGet()
        );

        assertThatThrownBy(() -> downloader.downloadVerifiedSource(
                snapshot("b2", "objects/slow-socket.mp4", data, null)))
                .isInstanceOf(StorageException.class)
                .hasMessageContaining("timeout budget");

        assertThat(openRangeCalls.get()).isEqualTo(1);
        assertThat(sleepCalls.get()).isZero();
        assertNoFilesInDirectory(sourceRoot);
    }

    @Test
    @DisplayName("Aborts immediately on InterruptedIOException even when current thread interrupt flag is cleared")
    void abortsImmediatelyWithoutRetryWhenInterruptedIOExceptionOccursWithClearedInterruptFlag(@TempDir Path tempDir)
            throws Exception {
        Path sourceRoot = tempDir.resolve("sources");
        byte[] data = deterministicBytes(8_192);
        AtomicInteger openRangeCalls = new AtomicInteger(0);
        AtomicInteger sleepCalls = new AtomicInteger(0);

        HlsSourceDownloadProperties props = new HlsSourceDownloadProperties(
                sourceRoot, 4096L, 100L * 1024L * 1024L, 3, 25L, Duration.ofMinutes(1), 1024L
        );

        BinaryStoragePort interruptedIoPort = new BinaryStoragePort() {
            @Override
            public StorageProviderId providerId() {
                return StorageProviderId.of("b2");
            }

            @Override
            public StoredBinaryObject store(StorageKey key, InputStream content, long sizeBytes, MimeType mimeType) {
                throw new UnsupportedOperationException();
            }

            @Override
            public InputStream open(StorageKey key) {
                throw new AssertionError("Whole-object open() must never be called");
            }

            @Override
            public InputStream openRange(StorageKey key, long startInclusive, long length) {
                openRangeCalls.incrementAndGet();
                return new InputStream() {
                    @Override
                    public int read() throws IOException {
                        throw new InterruptedIOException("Simulated interrupted I/O");
                    }

                    @Override
                    public int read(byte[] b, int off, int len) throws IOException {
                        assertThat(Thread.currentThread().isInterrupted()).isFalse();
                        throw new InterruptedIOException("Simulated interrupted I/O");
                    }
                };
            }

            @Override
            public void delete(StorageKey key) {
            }
        };

        MediaVideoSourceDownloader downloader = new MediaVideoSourceDownloader(
                p -> interruptedIoPort,
                props,
                null,
                dir -> 100_000_000L,
                System::nanoTime,
                ms -> sleepCalls.incrementAndGet()
        );

        Thread.interrupted(); // ensure clean flag beforehand
        try {
            assertThatThrownBy(() -> downloader.downloadVerifiedSource(
                    snapshot("b2", "objects/interrupted-io-cleared.mp4", data, null)))
                    .isInstanceOf(StorageException.class)
                    .hasMessageContaining("interrupted")
                    .hasRootCauseInstanceOf(InterruptedIOException.class);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
        }

        assertThat(openRangeCalls.get()).isEqualTo(1);
        assertThat(sleepCalls.get()).isZero();
        assertNoFilesInDirectory(sourceRoot);
    }

    @Test
    @DisplayName("Detects nested cancellation causes even inside wrapped StorageException or SocketTimeoutException")
    void doesNotConcealCancellationWhenNestedInsideWrappedExceptionsOrSocketTimeout(@TempDir Path tempDir)
            throws Exception {
        Path sourceRoot = tempDir.resolve("sources");
        byte[] data = deterministicBytes(8_192);
        HlsSourceDownloadProperties props = new HlsSourceDownloadProperties(
                sourceRoot, 4096L, 100L * 1024L * 1024L, 3, 25L, Duration.ofMinutes(1), 1024L
        );

        // 1. StorageException -> RuntimeException -> InterruptedIOException (with cleared interrupt flag)
        AtomicInteger wrappedCalls = new AtomicInteger(0);
        AtomicInteger wrappedSleeps = new AtomicInteger(0);
        MediaVideoSourceDownloader wrappedDownloader = new MediaVideoSourceDownloader(
                p -> new BinaryStoragePort() {
                    @Override public StorageProviderId providerId() { return p; }
                    @Override public StoredBinaryObject store(StorageKey k, InputStream c, long s, MimeType m) { throw new UnsupportedOperationException(); }
                    @Override public InputStream open(StorageKey k) { throw new UnsupportedOperationException(); }
                    @Override public void delete(StorageKey k) {}
                    @Override public InputStream openRange(StorageKey k, long start, long len) {
                        wrappedCalls.incrementAndGet();
                        throw new StorageException(
                                "Outer provider wrapper",
                                new RuntimeException("Middle wrapper", new InterruptedIOException("Wrapped cancellation"))
                        );
                    }
                },
                props,
                null,
                dir -> 100_000_000L,
                System::nanoTime,
                ms -> wrappedSleeps.incrementAndGet()
        );

        Thread.interrupted();
        try {
            assertThatThrownBy(() -> wrappedDownloader.downloadVerifiedSource(
                    snapshot("r2-movie", "objects/wrapped-cancel.mp4", data, null)))
                    .isInstanceOf(StorageException.class)
                    .hasMessageContaining("interrupted")
                    .hasRootCauseInstanceOf(InterruptedIOException.class);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
        }
        assertThat(wrappedCalls.get()).isEqualTo(1);
        assertThat(wrappedSleeps.get()).isZero();
        assertNoFilesInDirectory(sourceRoot);

        // 2. SocketTimeoutException wrapping a deeper InterruptedException (outer SocketTimeout must not conceal root cause)
        AtomicInteger deeperCalls = new AtomicInteger(0);
        AtomicInteger deeperSleeps = new AtomicInteger(0);
        MediaVideoSourceDownloader deeperDownloader = new MediaVideoSourceDownloader(
                p -> new BinaryStoragePort() {
                    @Override public StorageProviderId providerId() { return p; }
                    @Override public StoredBinaryObject store(StorageKey k, InputStream c, long s, MimeType m) { throw new UnsupportedOperationException(); }
                    @Override public InputStream open(StorageKey k) { throw new UnsupportedOperationException(); }
                    @Override public void delete(StorageKey k) {}
                    @Override public InputStream openRange(StorageKey k, long start, long len) {
                        deeperCalls.incrementAndGet();
                        return new InputStream() {
                            @Override public int read() { throw new AssertionError("Expected array read()"); }
                            @Override public int read(byte[] b, int off, int len) throws IOException {
                                SocketTimeoutException timeout = new SocketTimeoutException("Timeout wrapper");
                                timeout.initCause(new InterruptedException("Underlying cancellation signal"));
                                throw timeout;
                            }
                        };
                    }
                },
                props,
                null,
                dir -> 100_000_000L,
                System::nanoTime,
                ms -> deeperSleeps.incrementAndGet()
        );

        Thread.interrupted();
        try {
            assertThatThrownBy(() -> deeperDownloader.downloadVerifiedSource(
                    snapshot("b2", "objects/socket-timeout-wrapping-interrupt.mp4", data, null)))
                    .isInstanceOf(StorageException.class)
                    .hasMessageContaining("interrupted")
                    .hasRootCauseInstanceOf(InterruptedException.class);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
        }
        assertThat(deeperCalls.get()).isEqualTo(1);
        assertThat(deeperSleeps.get()).isZero();
        assertNoFilesInDirectory(sourceRoot);
    }

    @Test
    @DisplayName("SocketTimeoutException without interrupt signal retries cleanly and preserves SHA-256 and size")
    void retriesSocketTimeoutWhenUninterruptedAndVerifiesSha256AndSize(@TempDir Path tempDir) throws Exception {
        Path sourceRoot = tempDir.resolve("sources");
        byte[] data = deterministicBytes(8_192);
        AtomicInteger chunk0Attempts = new AtomicInteger(0);
        AtomicInteger chunk1Attempts = new AtomicInteger(0);
        AtomicInteger sleepCalls = new AtomicInteger(0);

        HlsSourceDownloadProperties props = new HlsSourceDownloadProperties(
                sourceRoot, 4096L, 100L * 1024L * 1024L, 2, 10L, Duration.ofMinutes(1), 1024L
        );

        BinaryStoragePort flakySocketTimeoutPort = new BinaryStoragePort() {
            @Override public StorageProviderId providerId() { return StorageProviderId.of("b2"); }
            @Override public StoredBinaryObject store(StorageKey k, InputStream c, long s, MimeType m) { throw new UnsupportedOperationException(); }
            @Override public InputStream open(StorageKey k) { throw new AssertionError("Whole-object open() must never be called"); }
            @Override public void delete(StorageKey k) {}
            @Override public InputStream openRange(StorageKey k, long startInclusive, long length) {
                if (startInclusive == 0L) {
                    chunk0Attempts.incrementAndGet();
                    return new ByteArrayInputStream(data, 0, (int) length);
                }
                if (chunk1Attempts.incrementAndGet() == 1) {
                    return new InputStream() {
                        @Override public int read() { throw new AssertionError("Expected array read()"); }
                        @Override public int read(byte[] b, int off, int len) throws IOException {
                            throw new SocketTimeoutException("Transient read socket timeout");
                        }
                    };
                }
                return new ByteArrayInputStream(data, (int) startInclusive, (int) length);
            }
        };

        MediaVideoSourceDownloader downloader = new MediaVideoSourceDownloader(
                p -> flakySocketTimeoutPort,
                props,
                null,
                dir -> 100_000_000L,
                System::nanoTime,
                ms -> sleepCalls.incrementAndGet()
        );

        Thread.interrupted();
        try (DownloadedSourceScope scope = downloader.downloadVerifiedSource(
                snapshot("b2", "objects/retry-socket-timeout.mp4", data, ContentHashStatus.CLIENT_DECLARED_UNVERIFIED))) {
            assertThat(Thread.currentThread().isInterrupted()).isFalse();
            assertThat(chunk0Attempts.get()).isEqualTo(1);
            assertThat(chunk1Attempts.get()).isEqualTo(2);
            assertThat(sleepCalls.get()).isEqualTo(1);
            assertThat(scope.verifiedSizeBytes()).isEqualTo(data.length);
            assertThat(scope.verifiedSha256()).isEqualTo(sha256(data));
            assertThat(Files.readAllBytes(scope.sourceFile())).isEqualTo(data);
        }
        assertNoFilesInDirectory(sourceRoot);
    }
}
