package com.universe.media.infrastructure.hls;

import com.universe.media.application.ports.storage.HlsArtifactDescriptor;
import com.universe.media.application.ports.storage.HlsExpectedArtifactManifest;
import com.universe.media.application.ports.storage.HlsPackageUploadCommand;
import com.universe.media.domain.StorageKey;
import com.universe.media.domain.StorageProviderId;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LocalHlsPackageInventoryTest {

    private static final UUID ASSET_ID = UUID.fromString("11111111-2222-3333-4444-555555555555");
    private static final UUID PACKAGE_UUID = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");
    private static final StorageProviderId R2_HLS = StorageProviderId.of("r2-hls");

    private static HlsLadderPlanner.ProbedVideoSource probedSource(int width, int height, boolean hasAudio) {
        return new HlsLadderPlanner.ProbedVideoSource(
                width, height, 0, "1:1", "", width, height,
                "24/1", "24/1", 24.0, 6.0,
                "h264", "yuv420p", 8, "bt709", "bt709", "bt709",
                hasAudio, hasAudio ? 1 : 0, hasAudio ? "aac" : null,
                hasAudio ? 2 : 0, hasAudio ? 48_000 : 0, hasAudio ? "und" : null
        );
    }

    private static LocalFfmpegHlsPackagingEngine createSyntheticEngine(
            Path scratchRoot,
            HlsLadderPlanner.ProbedVideoSource probed,
            int segmentsPerTier
    ) {
        HlsPackagingProperties props = new HlsPackagingProperties(
                true, "ffmpeg", "ffprobe", 1, 2, Duration.ofMinutes(1), Duration.ofSeconds(10),
                scratchRoot, 0L, "veryfast", 6
        );
        HlsFfmpegExecutionGate gate = new HlsFfmpegExecutionGate(props);
        BoundedSubprocessRunner dummyRunner = new BoundedSubprocessRunner((cmd, dir) -> {
            throw new AssertionError("No OS FFmpeg subprocess should run in unit tests");
        });
        FfprobeMediaSourceProbe probe = new FfprobeMediaSourceProbe(props, dummyRunner) {
            @Override
            public HlsLadderPlanner.ProbedVideoSource probeSource(Path sourceFile) {
                return probed;
            }

            @Override
            public HlsPlaylistProbe probeHlsMediaPlaylist(Path playlistPath) {
                String p = playlistPath.toString().replace('\\', '/');
                if (p.contains("audio/main")) {
                    return new HlsPlaylistProbe(1, "audio", "aac", 0, 0, 48_000, 2, 6.0 * segmentsPerTier, 280 * segmentsPerTier);
                }
                for (var tier : new HlsLadderPlanner().plan(
                        probed, 6,
                        HlsLadderPlanner.Below720Policy.SOURCE_FALLBACK_NO_UPSCALE,
                        HlsLadderPlanner.HdrPolicy.REJECT_EXPLICIT
                ).videoTiers()) {
                    if (p.contains("/" + tier.label() + "/")) {
                        return new HlsPlaylistProbe(1, "video", "h264", tier.width(), tier.height(), 0, 0,
                                6.0 * segmentsPerTier, 144 * segmentsPerTier);
                    }
                }
                return new HlsPlaylistProbe(1, "video", "h264", probed.displayWidth(), probed.displayHeight(), 0, 0,
                        6.0 * segmentsPerTier, 144 * segmentsPerTier);
            }
        };
        FfmpegHlsPackager syntheticPackager = new FfmpegHlsPackager(props, dummyRunner) {
            @Override
            public PackagingExecution packageToDirectory(
                    Path sourceFile,
                    Path outputRoot,
                    HlsLadderPlanner.HlsPackagingPlan plan
            ) throws IOException {
                writeSyntheticPackageForPlan(outputRoot, plan, segmentsPerTier);
                return new PackagingExecution(outputRoot, plan, List.of("synthetic"), 5L, "");
            }
        };
        LocalHlsPackageVerifier verifier = new LocalHlsPackageVerifier(probe);
        return new LocalFfmpegHlsPackagingEngine(props, gate, probe, syntheticPackager, verifier);
    }

    @Test
    @DisplayName("Builds inventory for single-tier, two-tier, three-tier, fallback source, and video-only packages with deterministic master-last ordering")
    void shouldBuildDeterministicInventoryForApprovedLadderAndAudioConfigurations(@TempDir Path tempDir) throws Exception {
        Path srcDir = Files.createDirectories(tempDir.resolve("src"));
        Path sourceFile = Files.write(srcDir.resolve("video.mp4"), new byte[]{1, 2, 3, 4});

        verifyScenario(tempDir.resolve("s720"), sourceFile, probedSource(1280, 720, true), 7, true);
        verifyScenario(tempDir.resolve("s1080"), sourceFile, probedSource(1920, 1080, true), 10, true);
        verifyScenario(tempDir.resolve("s2160"), sourceFile, probedSource(3840, 2160, true), 13, true);
        verifyScenario(tempDir.resolve("s480"), sourceFile, probedSource(854, 480, true), 7, true);
        verifyScenario(tempDir.resolve("s1080-noaudio"), sourceFile, probedSource(1920, 1080, false), 7, false);
    }

    @Test
    @DisplayName("Enforces bounded traversal limit early and validates streaming SHA-256 and tamper/missing rejection")
    void shouldEnforceEarlyTraversalBoundsAndStreamingHashIntegrity(@TempDir Path tempDir) throws Exception {
        Path srcDir = Files.createDirectories(tempDir.resolve("src"));
        Path scratchRoot = Files.createDirectories(tempDir.resolve("scratch"));
        Path sourceFile = Files.write(srcDir.resolve("episode.mp4"), new byte[]{9, 8, 7, 6});

        // 10 segments per tier + audio/main -> 25 total files, stopped early under maxObjectCount=6
        LocalFfmpegHlsPackagingEngine manyFilesEngine =
                createSyntheticEngine(scratchRoot, probedSource(1280, 720, true), 10);
        try (LocalFfmpegHlsPackagingEngine.LocalHlsPackagingResult manyResult = manyFilesEngine.packageLocalSource(sourceFile)) {
            assertThatThrownBy(() -> LocalHlsPackageInventory.fromVerifiedResult(
                    manyResult, ASSET_ID, 1, PACKAGE_UUID, R2_HLS, 6, 1024 * 1024
            )).isInstanceOf(IllegalStateException.class).hasMessageContaining("exceeds bounded maximum (6)");
        }

        LocalFfmpegHlsPackagingEngine engine =
                createSyntheticEngine(scratchRoot, probedSource(1280, 720, true), 1);

        try (LocalFfmpegHlsPackagingEngine.LocalHlsPackagingResult result = engine.packageLocalSource(sourceFile)) {
            LocalHlsPackageInventory inv =
                    LocalHlsPackageInventory.fromVerifiedResult(result, ASSET_ID, 1, PACKAGE_UUID, R2_HLS);
            HlsExpectedArtifactManifest manifest = inv.expectedManifest();

            byte[] masterBytes = Files.readAllBytes(result.packageRoot().resolve("master.m3u8"));
            String expectedMasterSha = HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(masterBytes)
            ).toLowerCase(Locale.ROOT);
            assertThat(manifest.masterManifestSha256().value()).isEqualTo(expectedMasterSha);

            assertThatThrownBy(() -> LocalHlsPackageInventory.fromVerifiedResult(
                    result, ASSET_ID, 1, PACKAGE_UUID, R2_HLS, 100, 10L
            )).isInstanceOf(IllegalStateException.class).hasMessageContaining("exceeds maxSingleObjectBytes");

            Path masterPath = result.packageRoot().resolve("master.m3u8");
            Files.writeString(masterPath, Files.readString(masterPath) + "\n# tampered");
            assertThatThrownBy(() -> LocalHlsPackageInventory.fromVerifiedResult(
                    result, ASSET_ID, 1, PACKAGE_UUID, R2_HLS
            )).isInstanceOf(IllegalStateException.class).hasMessageContaining("mismatches LocalHlsVerificationReport");
            Files.write(masterPath, masterBytes);

            Path rogue = result.packageRoot().resolve("video/720p/extra.txt");
            Files.writeString(rogue, "unexpected");
            assertThatThrownBy(() -> LocalHlsPackageInventory.fromVerifiedResult(
                    result, ASSET_ID, 1, PACKAGE_UUID, R2_HLS
            )).isInstanceOf(IllegalArgumentException.class);
            Files.deleteIfExists(rogue);

            Path seg = result.packageRoot().resolve("video/720p/seg_00000.m4s");
            byte[] origSeg = Files.readAllBytes(seg);
            Files.delete(seg);
            assertThatThrownBy(() -> LocalHlsPackageInventory.fromVerifiedResult(
                    result, ASSET_ID, 1, PACKAGE_UUID, R2_HLS
            )).isInstanceOf(IllegalStateException.class);
            Files.write(seg, origSeg);
        }
    }

    @Test
    @DisplayName("Pins local package directory during concurrent upload read, blocks premature close deletion, and rejects post-close access")
    void shouldPinAttemptDirectoryAgainstConcurrentCloseWithoutHarmingSourceOrSiblings(@TempDir Path tempDir) throws Exception {
        Path srcDir = Files.createDirectories(tempDir.resolve("src"));
        Path scratchRoot = Files.createDirectories(tempDir.resolve("scratch"));
        byte[] sourceBytes = new byte[]{4, 3, 2, 1};
        Path sourceFile = Files.write(srcDir.resolve("episode.mp4"), sourceBytes);
        Path siblingFile = Files.writeString(scratchRoot.resolve("unrelated-keep.txt"), "keep-me");
        Path siblingDirFile = Files.writeString(
                Files.createDirectories(scratchRoot.resolve("hls-pkg-unrelated")).resolve("keep.txt"),
                "preserve-sibling-prefix"
        );

        LocalFfmpegHlsPackagingEngine engine =
                createSyntheticEngine(scratchRoot, probedSource(1280, 720, true), 1);

        LocalFfmpegHlsPackagingEngine.LocalHlsPackagingResult result = engine.packageLocalSource(sourceFile);
        Path packageRoot = result.packageRoot();
        LocalHlsPackageInventory inv =
                LocalHlsPackageInventory.fromVerifiedResult(result, ASSET_ID, 1, PACKAGE_UUID, R2_HLS);
        HlsPackageUploadCommand cmd = inv.toUploadCommand("kiemlai-hls-priv");
        HlsArtifactDescriptor targetSeg = cmd.expectedManifest().stage1BinaryArtifacts().get(0);

        CountDownLatch enteredActiveStream = new CountDownLatch(1);
        CountDownLatch allowUploadToFinish = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<byte[]> activeUploadFuture = pool.submit(() -> cmd.artifactSource().executeWhilePinned(scope -> {
                byte[] bytesRead = cmd.artifactSource().withArtifactStream(targetSeg.objectKey(), (stream, size) -> {
                    enteredActiveStream.countDown();
                    try {
                        assertThat(allowUploadToFinish.await(5, TimeUnit.SECONDS)).isTrue();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new IOException("Interrupted waiting in test", e);
                    }
                    assertThat(scope.isCloseRequested()).isTrue();
                    assertThat(Files.exists(packageRoot)).isTrue();
                    return stream.readAllBytes();
                });

                // Even while pinned, opening a second artifact stream AFTER close was requested is forbidden
                assertThatThrownBy(() -> cmd.artifactSource().withArtifactStream(
                        cmd.expectedManifest().stage3MasterManifest().objectKey(),
                        (stream, size) -> stream.readAllBytes()
                )).isInstanceOf(IllegalStateException.class);
                return bytesRead;
            }));

            assertThat(enteredActiveStream.await(5, TimeUnit.SECONDS)).isTrue();

            // Attempt concurrent close() while upload stream is active
            Future<?> closeFuture = pool.submit(() -> {
                result.close();
                return null;
            });

            // Close must wait for active pinned reader and must NOT delete files underneath active upload
            Thread.sleep(80);
            assertThat(closeFuture.isDone()).isFalse();
            assertThat(Files.isDirectory(packageRoot)).isTrue();
            assertThatThrownBy(() -> cmd.artifactSource().openUploadScope())
                    .isInstanceOf(IllegalStateException.class);

            allowUploadToFinish.countDown();

            byte[] streamed = activeUploadFuture.get(5, TimeUnit.SECONDS);
            closeFuture.get(5, TimeUnit.SECONDS);

            assertThat(streamed).hasSize((int) targetSeg.sizeBytes());
            assertThat(Files.exists(packageRoot)).isFalse();
            assertThat(inv.isOwningResultAlive()).isFalse();

            // After finish + close, further access fails closed
            assertThatThrownBy(() -> cmd.artifactSource().openUploadScope())
                    .isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> cmd.artifactSource().withArtifactStream(
                    targetSeg.objectKey(), (s, len) -> s.readAllBytes()
            )).isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> inv.toUploadCommand("kiemlai-hls-priv"))
                    .isInstanceOf(IllegalStateException.class);

            // Source MP4 and sibling files remain completely untouched
            assertThat(Files.readAllBytes(sourceFile)).isEqualTo(sourceBytes);
            assertThat(Files.readString(siblingFile)).isEqualTo("keep-me");
            assertThat(Files.readString(siblingDirFile)).isEqualTo("preserve-sibling-prefix");
        } finally {
            pool.shutdownNow();
            result.close();
        }
    }

    @Test
    @DisplayName("Exposes bounded worker factory enforcing R2 storage limits before any remote call and validates limit bounds")
    void shouldEnforceEffectiveRemoteLimitsBeforeUploadAndRejectInvalidLimits(@TempDir Path tempDir) throws Exception {
        Path srcDir = Files.createDirectories(tempDir.resolve("src"));
        Path scratchRoot = Files.createDirectories(tempDir.resolve("scratch"));
        Path sourceFile = Files.write(srcDir.resolve("episode.mp4"), new byte[]{1, 2, 3, 4});

        // 1 segment per tier (video/720p + audio/main) => exact 7 objects (master + 2 playlists + 2 inits + 2 segs)
        LocalFfmpegHlsPackagingEngine engine =
                createSyntheticEngine(scratchRoot, probedSource(1280, 720, true), 1);

        try (LocalFfmpegHlsPackagingEngine.LocalHlsPackagingResult result = engine.packageLocalSource(sourceFile)) {
            int exactObjectCount = result.verificationReport().objectCount();
            final long maxArtifactBytes;
            try (var walk = Files.walk(result.packageRoot())) {
                maxArtifactBytes = walk.filter(Files::isRegularFile)
                        .mapToLong(p -> {
                            try {
                                return Files.size(p);
                            } catch (IOException e) {
                                throw new RuntimeException(e);
                            }
                        }).max().orElseThrow();
            }

            // Exactly at object limit AND exact byte limit -> succeeds
            LocalHlsPackageInventory atLimit = LocalHlsPackageInventory.fromVerifiedResult(
                    result, ASSET_ID, 1, PACKAGE_UUID, R2_HLS,
                    LocalHlsPackageInventory.InventoryLimits.stricterOfDefaultsAndRemote(exactObjectCount, maxArtifactBytes)
            );
            assertThat(atLimit.expectedManifest().objectCount()).isEqualTo(exactObjectCount);

            // One object above limit (maxObjectCount = exactObjectCount - 1) -> fails locally before remote upload
            int belowCount = exactObjectCount - 1;
            var r2MockPort = org.mockito.Mockito.mock(com.universe.media.application.ports.storage.HlsPackageArtifactStoragePort.class);
            assertThatThrownBy(() -> {
                LocalHlsPackageInventory rejected = LocalHlsPackageInventory.fromVerifiedResult(
                        result, ASSET_ID, 1, PACKAGE_UUID, R2_HLS, belowCount, maxArtifactBytes
                );
                r2MockPort.uploadAndVerifyPackage(rejected.toUploadCommand("kiemlai-hls-priv"));
            }).isInstanceOf(IllegalStateException.class).hasMessageContaining("exceeds bounded maximum");
            org.mockito.Mockito.verifyNoInteractions(r2MockPort);

            // One byte above artifact limit -> fails locally before remote upload
            long belowBytes = maxArtifactBytes - 1;
            assertThatThrownBy(() -> {
                LocalHlsPackageInventory rejected = LocalHlsPackageInventory.fromVerifiedResult(
                        result, ASSET_ID, 1, PACKAGE_UUID, R2_HLS, exactObjectCount, belowBytes
                );
                r2MockPort.uploadAndVerifyPackage(rejected.toUploadCommand("kiemlai-hls-priv"));
            }).isInstanceOf(IllegalStateException.class).hasMessageContaining("exceeds maxSingleObjectBytes");
            org.mockito.Mockito.verifyNoInteractions(r2MockPort);

            // Stricter limit resolution picks min(local default 10000, R2 default 5000) == 5000
            LocalHlsPackageInventory.InventoryLimits effective =
                    LocalHlsPackageInventory.InventoryLimits.stricterOfDefaultsAndRemote(
                            com.universe.media.infrastructure.storage.r2hls.R2HlsStorageProperties.DEFAULT_MAX_VERIFICATION_COUNT,
                            com.universe.media.infrastructure.storage.r2hls.R2HlsStorageProperties.DEFAULT_MAX_ARTIFACT_SIZE_BYTES
                    );
            assertThat(effective.maxObjectCount()).isEqualTo(5000);
            assertThat(effective.maxSingleObjectBytes()).isEqualTo(64L * 1024L * 1024L);

            // Invalid limits fail closed
            assertThatThrownBy(() -> LocalHlsPackageInventory.fromVerifiedResult(
                    result, ASSET_ID, 1, PACKAGE_UUID, R2_HLS, 3, 1024L
            )).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> LocalHlsPackageInventory.fromVerifiedResult(
                    result, ASSET_ID, 1, PACKAGE_UUID, R2_HLS, LocalHlsPackageInventory.MAX_ALLOWED_OBJECT_COUNT + 1, 1024L
            )).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> LocalHlsPackageInventory.fromVerifiedResult(
                    result, ASSET_ID, 1, PACKAGE_UUID, R2_HLS, 10, 0L
            )).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> LocalHlsPackageInventory.fromVerifiedResult(
                    result, ASSET_ID, 1, PACKAGE_UUID, R2_HLS, 10, LocalHlsPackageInventory.MAX_ALLOWED_SINGLE_OBJECT_BYTES + 1
            )).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    @DisplayName("Explicitly rejects symbolic link inside local package directory on symlink-capable hosts")
    void shouldRejectSymlinkInsidePackageOnCapableHost(@TempDir Path tempDir) throws Exception {
        Path srcDir = Files.createDirectories(tempDir.resolve("src"));
        Path scratchRoot = Files.createDirectories(tempDir.resolve("scratch"));
        Path sourceFile = Files.write(srcDir.resolve("episode.mp4"), new byte[]{9, 8, 7, 6});
        Path outside = Files.writeString(tempDir.resolve("outside-secret.m4s"), "secret");

        LocalFfmpegHlsPackagingEngine engine =
                createSyntheticEngine(scratchRoot, probedSource(1280, 720, true), 1);
        try (LocalFfmpegHlsPackagingEngine.LocalHlsPackagingResult result = engine.packageLocalSource(sourceFile)) {
            Path link = result.packageRoot().resolve("video/720p/seg_00001.m4s");
            try {
                Files.createSymbolicLink(link, outside);
            } catch (IOException | UnsupportedOperationException | SecurityException e) {
                Assumptions.assumeTrue(false, "POSIX symbolic link creation unavailable on this host: " + e.getMessage());
                return;
            }
            try {
                assertThatThrownBy(() -> LocalHlsPackageInventory.fromVerifiedResult(
                        result, ASSET_ID, 1, PACKAGE_UUID, R2_HLS
                )).isInstanceOf(IllegalStateException.class).hasMessageContaining("Symbolic link");
            } finally {
                Files.deleteIfExists(link);
            }
        }
    }

    private void verifyScenario(
            Path scratchRoot,
            Path sourceFile,
            HlsLadderPlanner.ProbedVideoSource source,
            int expectedObjectCount,
            boolean expectAudio
    ) throws Exception {
        Files.createDirectories(scratchRoot);
        LocalFfmpegHlsPackagingEngine engine = createSyntheticEngine(scratchRoot, source, 1);
        try (LocalFfmpegHlsPackagingEngine.LocalHlsPackagingResult result = engine.packageLocalSource(sourceFile)) {
            LocalHlsPackageInventory inventory =
                    LocalHlsPackageInventory.fromVerifiedResult(result, ASSET_ID, 2, PACKAGE_UUID, R2_HLS);
            HlsExpectedArtifactManifest manifest = inventory.expectedManifest();

            assertThat(manifest.objectCount()).isEqualTo(expectedObjectCount);
            assertThat(manifest.totalSizeBytes()).isEqualTo(result.verificationReport().totalSizeBytes());
            assertThat(manifest.masterManifestSha256().value())
                    .isEqualTo(result.verificationReport().masterManifestSha256());

            List<HlsArtifactDescriptor> ordered = manifest.orderedArtifacts();
            assertThat(ordered.get(ordered.size() - 1).relativePath()).isEqualTo("master.m3u8");
            boolean hasAudio = ordered.stream().anyMatch(a -> a.relativePath().startsWith("audio/"));
            assertThat(hasAudio).isEqualTo(expectAudio);

            HlsPackageUploadCommand cmd = inventory.toUploadCommand("kiemlai-hls-priv");
            assertThat(cmd.bucketName()).isEqualTo("kiemlai-hls-priv");
            assertThat(cmd.expectedManifest()).isSameAs(manifest);
            StorageKey firstKey = ordered.get(0).objectKey();
            long streamedBytes = cmd.artifactSource().withArtifactStream(firstKey, (in, len) -> {
                byte[] read = in.readAllBytes();
                assertThat(read.length).isEqualTo(len);
                return (long) read.length;
            });
            assertThat(streamedBytes).isEqualTo(ordered.get(0).sizeBytes());
        }
    }

    private static void writeSyntheticPackageForPlan(
            Path root,
            HlsLadderPlanner.HlsPackagingPlan plan,
            int segmentsPerTier
    ) throws IOException {
        StringBuilder master = new StringBuilder("#EXTM3U\n#EXT-X-VERSION:7\n");
        if (plan.includeAudio()) {
            Files.createDirectories(root.resolve("audio/main"));
            master.append("#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID=\"group_aud\",NAME=\"main\",DEFAULT=YES,CHANNELS=\"2\",URI=\"audio/main/playlist.m3u8\"\n");
            Files.writeString(root.resolve("audio/main/playlist.m3u8"), buildMediaPlaylist("init_a.mp4", segmentsPerTier), StandardCharsets.UTF_8);
            Files.write(root.resolve("audio/main/init_a.mp4"), bmffPair("ftyp", "moov"));
            for (int s = 0; s < segmentsPerTier; s++) {
                Files.write(root.resolve(String.format(Locale.ROOT, "audio/main/seg_%05d.m4s", s)), bmffPair("moof", "mdat"));
            }
        }

        int tierIdx = 0;
        for (HlsLadderPlanner.VideoTierSpec tier : plan.videoTiers()) {
            Path dir = Files.createDirectories(root.resolve("video/" + tier.label()));
            String audioAttr = plan.includeAudio() ? ",AUDIO=\"group_aud\"" : "";
            String codecs = plan.includeAudio() ? "avc1.64001f,mp4a.40.2" : "avc1.64001f";
            master.append(String.format(
                    Locale.ROOT,
                    "#EXT-X-STREAM-INF:BANDWIDTH=%d,RESOLUTION=%dx%d,CODECS=\"%s\"%s\n%s\n",
                    tier.maxrateKbps() * 1000L, tier.width(), tier.height(), codecs, audioAttr, tier.playlistRelativeUri()
            ));
            String initName = "init_" + (tierIdx++) + ".mp4";
            Files.writeString(dir.resolve("playlist.m3u8"), buildMediaPlaylist(initName, segmentsPerTier), StandardCharsets.UTF_8);
            Files.write(dir.resolve(initName), bmffPair("ftyp", "moov"));
            for (int s = 0; s < segmentsPerTier; s++) {
                Files.write(dir.resolve(String.format(Locale.ROOT, "seg_%05d.m4s", s)), bmffPair("moof", "mdat"));
            }
        }
        Files.writeString(root.resolve("master.m3u8"), master.toString(), StandardCharsets.UTF_8);
    }

    private static String buildMediaPlaylist(String initName, int segmentsPerTier) {
        StringBuilder sb = new StringBuilder();
        sb.append("#EXTM3U\n#EXT-X-VERSION:7\n#EXT-X-TARGETDURATION:6\n#EXT-X-PLAYLIST-TYPE:VOD\n");
        sb.append("#EXT-X-INDEPENDENT-SEGMENTS\n");
        sb.append("#EXT-X-MAP:URI=\"").append(initName).append("\"\n");
        for (int s = 0; s < segmentsPerTier; s++) {
            sb.append("#EXTINF:6.000000,\n");
            sb.append(String.format(Locale.ROOT, "seg_%05d.m4s\n", s));
        }
        sb.append("#EXT-X-ENDLIST\n");
        return sb.toString();
    }

    private static byte[] bmffPair(String box1, String box2) {
        ByteBuffer buf = ByteBuffer.allocate(32);
        buf.putInt(16).put(box1.getBytes(StandardCharsets.US_ASCII)).putLong(0L);
        buf.putInt(16).put(box2.getBytes(StandardCharsets.US_ASCII)).putLong(0L);
        return buf.array();
    }
}
