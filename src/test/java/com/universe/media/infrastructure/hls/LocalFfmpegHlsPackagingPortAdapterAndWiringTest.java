package com.universe.media.infrastructure.hls;

import com.universe.media.application.hls.CompleteVerifiedHlsPackagingUseCase;
import com.universe.media.application.hls.FailMediaVideoPackagingJobUseCase;
import com.universe.media.application.hls.HlsLocalPackagingException;
import com.universe.media.application.hls.HlsLocalPackagingPort;
import com.universe.media.application.hls.HlsPackagingOperationalException;
import com.universe.media.application.hls.MediaVideoSourceDownloadPort;
import com.universe.media.application.hls.PrepareLocalHlsPackageCommand;
import com.universe.media.application.hls.PreparedHlsPackageScope;
import com.universe.media.application.hls.ProcessClaimedHlsPackagingLeaseUseCase;
import com.universe.media.application.hls.ResolveMediaVideoSourceSnapshotUseCase;
import com.universe.media.application.hls.UnsupportedHlsSourceMediaException;
import com.universe.media.application.ports.storage.HlsExpectedArtifactManifest;
import com.universe.media.application.ports.storage.HlsPackageArtifactStoragePort;
import com.universe.media.application.ports.storage.HlsPackageKeyValidator;
import com.universe.media.domain.StorageKey;
import com.universe.media.domain.StorageProviderId;
import com.universe.media.infrastructure.storage.r2hls.R2HlsStorageProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

@DisplayName("MS-08 HLS-2B3B1: LocalFfmpegHlsPackagingPortAdapter & Passive Conditional Spring Wiring Tests")
class LocalFfmpegHlsPackagingPortAdapterAndWiringTest {

    private static final UUID ASSET_ID = UUID.fromString("11111111-2222-3333-4444-555555555555");
    private static final UUID PACKAGE_UUID = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");
    private static final StorageProviderId R2_HLS = HlsPackageKeyValidator.REQUIRED_PROVIDER_ID;

    private static HlsLadderPlanner.ProbedVideoSource probedSource() {
        return new HlsLadderPlanner.ProbedVideoSource(
                1280, 720, 0, "1:1", "", 1280, 720,
                "24/1", "24/1", 24.0, 6.0,
                "h264", "yuv420p", 8, "bt709", "bt709", "bt709",
                true, 1, "aac", 2, 48_000, "und"
        );
    }

    private static LocalFfmpegHlsPackagingEngine createSyntheticEngine(
            Path scratchRoot,
            AtomicInteger observedSegmentDuration
    ) {
        HlsLadderPlanner.ProbedVideoSource probed = probedSource();
        HlsPackagingProperties props = new HlsPackagingProperties(
                true, "ffmpeg", "ffprobe", 1, 2, Duration.ofMinutes(1), Duration.ofSeconds(10),
                scratchRoot, 0L, "veryfast", 6
        );
        HlsFfmpegExecutionGate gate = new HlsFfmpegExecutionGate(props);
        BoundedSubprocessRunner dummyRunner = new BoundedSubprocessRunner((cmd, dir) -> {
            throw new AssertionError("No OS subprocess should execute in unit tests");
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
                    return new HlsPlaylistProbe(1, "audio", "aac", 0, 0, 48_000, 2, 6.0, 280);
                }
                return new HlsPlaylistProbe(1, "video", "h264", 1280, 720, 0, 0, 6.0, 144);
            }
        };
        FfmpegHlsPackager syntheticPackager = new FfmpegHlsPackager(props, dummyRunner) {
            @Override
            public PackagingExecution packageToDirectory(
                    Path sourceFile,
                    Path outputRoot,
                    HlsLadderPlanner.HlsPackagingPlan plan
            ) throws IOException {
                observedSegmentDuration.set(plan.segmentDurationSeconds());
                writeSyntheticSingleTierPackage(outputRoot, plan);
                return new PackagingExecution(outputRoot, plan, List.of("synthetic"), 5L, "");
            }
        };
        LocalHlsPackageVerifier verifier = new LocalHlsPackageVerifier(probe);
        return new LocalFfmpegHlsPackagingEngine(props, gate, probe, syntheticPackager, verifier);
    }

    @Test
    @DisplayName("Adapts synthetic LocalFfmpegHlsPackagingEngine with requested segmentDuration, validates inventory, and cleans up idempotently")
    void preparesBoundedScopeWithRequestedSegmentDurationAndCleansUp(@TempDir Path tempDir) throws Exception {
        Path scratchRoot = Files.createDirectories(tempDir.resolve("scratch"));
        Path sourceFile = Files.write(tempDir.resolve("source.mp4"), new byte[]{1, 2, 3, 4});
        AtomicInteger observedDuration = new AtomicInteger();

        LocalFfmpegHlsPackagingEngine engine = createSyntheticEngine(scratchRoot, observedDuration);
        LocalFfmpegHlsPackagingPortAdapter adapter = new LocalFfmpegHlsPackagingPortAdapter(engine);

        try (PreparedHlsPackageScope scope = adapter.preparePackage(new PrepareLocalHlsPackageCommand(
                sourceFile, ASSET_ID, 2, PACKAGE_UUID, R2_HLS, 8, 5000, 64L * 1024 * 1024
        ))) {
            assertThat(observedDuration.get()).isEqualTo(8);
            assertThat(scope.segmentDurationSeconds()).isEqualTo(8);
            HlsExpectedArtifactManifest manifest = scope.expectedManifest();
            assertThat(manifest.objectCount()).isEqualTo(scope.objectCount());
            assertThat(scope.objectCount()).isEqualTo(7);
            assertThat(scope.totalSizeBytes()).isEqualTo(manifest.totalSizeBytes());
            assertThat(scope.isClosed()).isFalse();

            StorageKey masterKey = manifest.identity().masterManifestKey();
            byte[] masterBytes = scope.artifactStreamSource().withArtifactStream(masterKey, (in, len) -> in.readAllBytes());
            assertThat(new String(masterBytes, StandardCharsets.UTF_8)).contains("#EXTM3U");

            scope.close();
            scope.close(); // idempotent
            assertThat(scope.isClosed()).isTrue();
            assertThatThrownBy(() -> scope.artifactStreamSource().openArtifactStream(masterKey))
                    .isInstanceOf(IllegalStateException.class);
        }

        // Strict limit overflow cleans up temp scratch dir cleanly
        assertThatThrownBy(() -> adapter.preparePackage(new PrepareLocalHlsPackageCommand(
                sourceFile, ASSET_ID, 2, PACKAGE_UUID, R2_HLS, 4, 4, 64L * 1024 * 1024
        ))).isInstanceOf(IllegalStateException.class).hasMessageContaining("exceeds bounded maximum");
    }

    @Test
    @DisplayName("Translates UnsupportedHlsSource, missing FFmpeg binary, TimeoutException, IOException, and InterruptedException accurately")
    void translatesEngineExceptionsAccurately(@TempDir Path tempDir) throws Exception {
        Path scratchRoot = Files.createDirectories(tempDir.resolve("scratch"));
        Path sourceFile = Files.write(tempDir.resolve("src.mp4"), new byte[]{1, 2, 3});
        HlsPackagingProperties props = new HlsPackagingProperties(
                true, "ffmpeg", "ffprobe", 1, 2, Duration.ofMinutes(1), Duration.ofSeconds(10),
                scratchRoot, 0L, "veryfast", 6
        );
        HlsFfmpegExecutionGate gate = new HlsFfmpegExecutionGate(props);
        BoundedSubprocessRunner dummyRunner = new BoundedSubprocessRunner((c, d) -> {
            throw new AssertionError();
        });
        FfprobeMediaSourceProbe probe = new FfprobeMediaSourceProbe(props, dummyRunner);
        FfmpegHlsPackager packager = new FfmpegHlsPackager(props, dummyRunner);
        LocalHlsPackageVerifier verifier = new LocalHlsPackageVerifier(probe);

        assertTranslatedException(
                new LocalFfmpegHlsPackagingEngine(props, gate, probe, packager, verifier) {
                    @Override public LocalHlsPackagingResult packageLocalSource(
                            Path s, int d, HlsLadderPlanner.Below720Policy b, HlsLadderPlanner.HdrPolicy h) {
                        throw new HlsLadderPlanner.UnsupportedHlsSourceException("HDR10+");
                    }
                },
                sourceFile,
                UnsupportedHlsSourceMediaException.class
        );

        assertTranslatedException(
                new LocalFfmpegHlsPackagingEngine(props, gate, probe, packager, verifier) {
                    @Override public LocalHlsPackagingResult packageLocalSource(
                            Path s, int d, HlsLadderPlanner.Below720Policy b, HlsLadderPlanner.HdrPolicy h) throws IOException {
                        throw new IOException("Cannot run program \"ffmpeg\": error=2, No such file or directory");
                    }
                },
                sourceFile,
                HlsPackagingOperationalException.class
        );

        assertTranslatedException(
                new LocalFfmpegHlsPackagingEngine(props, gate, probe, packager, verifier) {
                    @Override public LocalHlsPackagingResult packageLocalSource(
                            Path s, int d, HlsLadderPlanner.Below720Policy b, HlsLadderPlanner.HdrPolicy h) throws TimeoutException {
                        throw new TimeoutException("timed out");
                    }
                },
                sourceFile,
                HlsLocalPackagingException.class
        );

        assertTranslatedException(
                new LocalFfmpegHlsPackagingEngine(props, gate, probe, packager, verifier) {
                    @Override public LocalHlsPackagingResult packageLocalSource(
                            Path s, int d, HlsLadderPlanner.Below720Policy b, HlsLadderPlanner.HdrPolicy h) throws IOException {
                        throw new IOException("disk write error");
                    }
                },
                sourceFile,
                HlsLocalPackagingException.class
        );

        try {
            assertTranslatedException(
                    new LocalFfmpegHlsPackagingEngine(props, gate, probe, packager, verifier) {
                        @Override public LocalHlsPackagingResult packageLocalSource(
                                Path s, int d, HlsLadderPlanner.Below720Policy b, HlsLadderPlanner.HdrPolicy h) throws InterruptedException {
                            throw new InterruptedException("interrupted");
                        }
                    },
                    sourceFile,
                    HlsLocalPackagingException.class
            );
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted(); // clear interrupt status for subsequent tests
        }
    }

    private void assertTranslatedException(
            LocalFfmpegHlsPackagingEngine engine,
            Path sourceFile,
            Class<? extends Throwable> expectedType
    ) {
        LocalFfmpegHlsPackagingPortAdapter adapter = new LocalFfmpegHlsPackagingPortAdapter(engine);
        assertThatThrownBy(() -> adapter.preparePackage(new PrepareLocalHlsPackageCommand(
                sourceFile, ASSET_ID, 1, PACKAGE_UUID, R2_HLS, 6, 5000, 64L * 1024 * 1024
        ))).isInstanceOf(expectedType);
    }

    @Configuration
    static class WorkerCoreMockDepsConfig {
        @Bean ResolveMediaVideoSourceSnapshotUseCase resolveUseCase() { return mock(ResolveMediaVideoSourceSnapshotUseCase.class); }
        @Bean MediaVideoSourceDownloadPort downloadPort() { return mock(MediaVideoSourceDownloadPort.class); }
        @Bean HlsLocalPackagingPort localPackagingPort() { return mock(HlsLocalPackagingPort.class); }
        @Bean HlsPackageArtifactStoragePort storagePort() { return mock(HlsPackageArtifactStoragePort.class); }
        @Bean CompleteVerifiedHlsPackagingUseCase completeUseCase() { return mock(CompleteVerifiedHlsPackagingUseCase.class); }
        @Bean FailMediaVideoPackagingJobUseCase failUseCase() { return mock(FailMediaVideoPackagingJobUseCase.class); }
    }

    @Test
    @DisplayName("Spring ContextRunner: default & partial feature flags keep worker core absent; both flags=true registers passive bean with zero background tasks")
    void conditionalWiringRespectsDualFlagsAndRemainsPassive() {
        ApplicationContextRunner baseRunner = new ApplicationContextRunner()
                .withUserConfiguration(
                        WorkerCoreMockDepsConfig.class,
                        R2HlsStorageProperties.class,
                        HlsPackagingProperties.class,
                        HlsPackagingWorkerCoreConfiguration.class
                );

        // 1. Default flags unset -> completely dormant
        baseRunner.run(ctx -> {
            assertThat(ctx).hasNotFailed();
            assertThat(ctx).doesNotHaveBean(ProcessClaimedHlsPackagingLeaseUseCase.class);
            assertThat(ctx).doesNotHaveBean(R2HlsStorageProperties.class);
            assertThat(ctx).doesNotHaveBean(HlsPackagingProperties.class);
            assertThat(ctx).doesNotHaveBean(ScheduledAnnotationBeanPostProcessor.class);
        });

        // 2. Partial flag: only packaging enabled -> no worker core bean
        baseRunner.withPropertyValues(
                "media.hls.packaging.enabled=true",
                "media.hls.storage.r2.enabled=false"
        ).run(ctx -> {
            assertThat(ctx).hasNotFailed();
            assertThat(ctx).hasSingleBean(HlsPackagingProperties.class);
            assertThat(ctx).doesNotHaveBean(ProcessClaimedHlsPackagingLeaseUseCase.class);
        });

        // 3. Partial flag: only r2 enabled -> no worker core bean
        baseRunner.withPropertyValues(
                "media.hls.packaging.enabled=false",
                "media.hls.storage.r2.enabled=true",
                "media.storage.r2-hls.bucket=kiemlai-hls-priv",
                "media.storage.r2-hls.endpoint=https://example.r2.cloudflarestorage.com",
                "media.storage.r2-hls.access-key-id=test-key",
                "media.storage.r2-hls.secret-access-key=test-secret"
        ).run(ctx -> {
            assertThat(ctx).hasNotFailed();
            assertThat(ctx).hasSingleBean(R2HlsStorageProperties.class);
            assertThat(ctx).doesNotHaveBean(ProcessClaimedHlsPackagingLeaseUseCase.class);
        });

        // 4. Both flags true -> registers passive ProcessClaimedHlsPackagingLeaseUseCase without startup activity
        baseRunner.withPropertyValues(
                "media.hls.packaging.enabled=true",
                "media.hls.storage.r2.enabled=true",
                "media.storage.r2-hls.bucket=kiemlai-hls-priv",
                "media.storage.r2-hls.endpoint=https://example.r2.cloudflarestorage.com",
                "media.storage.r2-hls.access-key-id=test-key",
                "media.storage.r2-hls.secret-access-key=test-secret"
        ).run(ctx -> {
            assertThat(ctx).hasNotFailed();
            assertThat(ctx).hasSingleBean(ProcessClaimedHlsPackagingLeaseUseCase.class);
            assertThat(ctx).doesNotHaveBean(ScheduledAnnotationBeanPostProcessor.class);
        });
    }

    private static void writeSyntheticSingleTierPackage(
            Path root,
            HlsLadderPlanner.HlsPackagingPlan plan
    ) throws IOException {
        StringBuilder master = new StringBuilder("#EXTM3U\n#EXT-X-VERSION:7\n");
        if (plan.includeAudio()) {
            Files.createDirectories(root.resolve("audio/main"));
            master.append("#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID=\"group_aud\",NAME=\"main\",DEFAULT=YES,CHANNELS=\"2\",URI=\"audio/main/playlist.m3u8\"\n");
            Files.writeString(root.resolve("audio/main/playlist.m3u8"), buildPlaylist("init_a.mp4", plan.segmentDurationSeconds()), StandardCharsets.UTF_8);
            Files.write(root.resolve("audio/main/init_a.mp4"), bmffPair("ftyp", "moov"));
            Files.write(root.resolve("audio/main/seg_00000.m4s"), bmffPair("moof", "mdat"));
        }
        HlsLadderPlanner.VideoTierSpec tier = plan.videoTiers().get(0);
        Path dir = Files.createDirectories(root.resolve("video/" + tier.label()));
        master.append(String.format(
                Locale.ROOT,
                "#EXT-X-STREAM-INF:BANDWIDTH=%d,RESOLUTION=%dx%d,CODECS=\"avc1.64001f,mp4a.40.2\",AUDIO=\"group_aud\"\n%s\n",
                tier.maxrateKbps() * 1000L, tier.width(), tier.height(), tier.playlistRelativeUri()
        ));
        Files.writeString(dir.resolve("playlist.m3u8"), buildPlaylist("init_0.mp4", plan.segmentDurationSeconds()), StandardCharsets.UTF_8);
        Files.write(dir.resolve("init_0.mp4"), bmffPair("ftyp", "moov"));
        Files.write(dir.resolve("seg_00000.m4s"), bmffPair("moof", "mdat"));
        Files.writeString(root.resolve("master.m3u8"), master.toString(), StandardCharsets.UTF_8);
    }

    private static String buildPlaylist(String initName, int segDuration) {
        return "#EXTM3U\n#EXT-X-VERSION:7\n#EXT-X-TARGETDURATION:" + segDuration + "\n"
                + "#EXT-X-PLAYLIST-TYPE:VOD\n#EXT-X-INDEPENDENT-SEGMENTS\n"
                + "#EXT-X-MAP:URI=\"" + initName + "\"\n"
                + "#EXTINF:" + segDuration + ".000000,\nseg_00000.m4s\n#EXT-X-ENDLIST\n";
    }

    private static byte[] bmffPair(String box1, String box2) {
        ByteBuffer buf = ByteBuffer.allocate(32);
        buf.putInt(16).put(box1.getBytes(StandardCharsets.US_ASCII)).putLong(0L);
        buf.putInt(16).put(box2.getBytes(StandardCharsets.US_ASCII)).putLong(0L);
        return buf.array();
    }
}
