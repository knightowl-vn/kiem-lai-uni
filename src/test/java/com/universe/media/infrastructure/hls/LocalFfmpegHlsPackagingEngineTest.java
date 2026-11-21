package com.universe.media.infrastructure.hls;

import com.universe.media.infrastructure.hls.poc.HlsLadderPlannerAndVerifierUnitTest;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LocalFfmpegHlsPackagingEngineTest {

    private static HlsLadderPlanner.ProbedVideoSource sample720pSource() {
        return new HlsLadderPlanner.ProbedVideoSource(
                1280, 720, 0, "1:1", "16:9", 1280, 720,
                "24/1", "24/1", 24.0, 12.0,
                "h264", "yuv420p", 8, "bt709", "bt709", "bt709",
                true, 1, "aac", 2, 48_000, "und"
        );
    }

    @Test
    @DisplayName("Dormant by default and when enabled=false: zero HLS packaging beans register and startup needs no binaries")
    void shouldRemainDormantByDefaultAndWhenDisabled(@TempDir Path tempDir) {
        ApplicationContextRunner baseRunner = new ApplicationContextRunner()
                .withUserConfiguration(
                        HlsPackagingProperties.class,
                        HlsFfmpegExecutionGate.class,
                        BoundedSubprocessRunner.class,
                        FfprobeMediaSourceProbe.class,
                        FfmpegHlsPackager.class,
                        LocalHlsPackageVerifier.class,
                        LocalFfmpegHlsPackagingEngine.class
                );

        baseRunner.run(ctx -> {
            assertThat(ctx).hasNotFailed();
            assertThat(ctx).doesNotHaveBean(HlsPackagingProperties.class);
            assertThat(ctx).doesNotHaveBean(HlsFfmpegExecutionGate.class);
            assertThat(ctx).doesNotHaveBean(BoundedSubprocessRunner.class);
            assertThat(ctx).doesNotHaveBean(FfprobeMediaSourceProbe.class);
            assertThat(ctx).doesNotHaveBean(FfmpegHlsPackager.class);
            assertThat(ctx).doesNotHaveBean(LocalHlsPackageVerifier.class);
            assertThat(ctx).doesNotHaveBean(LocalFfmpegHlsPackagingEngine.class);
        });

        baseRunner.withPropertyValues(
                "media.hls.packaging.enabled=false",
                "media.hls.packaging.ffmpeg-path=/nonexistent/bin/ffmpeg",
                "media.hls.packaging.ffprobe-path=/nonexistent/bin/ffprobe",
                "media.hls.packaging.scratch-root-dir=" + tempDir.toAbsolutePath()
        ).run(ctx -> {
            assertThat(ctx).hasNotFailed();
            assertThat(ctx).doesNotHaveBean(LocalFfmpegHlsPackagingEngine.class);
        });
    }

    @Test
    @DisplayName("Wires all 7 HLS packaging beans as singletons when media.hls.packaging.enabled=true")
    void shouldWirePackagingEngineWhenExplicitlyEnabled(@TempDir Path tempDir) {
        ApplicationContextRunner enabledRunner = new ApplicationContextRunner()
                .withUserConfiguration(
                        HlsPackagingProperties.class,
                        HlsFfmpegExecutionGate.class,
                        BoundedSubprocessRunner.class,
                        FfprobeMediaSourceProbe.class,
                        FfmpegHlsPackager.class,
                        LocalHlsPackageVerifier.class,
                        LocalFfmpegHlsPackagingEngine.class
                )
                .withPropertyValues(
                        "media.hls.packaging.enabled=true",
                        "media.hls.packaging.ffmpeg-path=ffmpeg",
                        "media.hls.packaging.ffprobe-path=ffprobe",
                        "media.hls.packaging.max-concurrent-encodes=1",
                        "media.hls.packaging.ffmpeg-threads=4",
                        "media.hls.packaging.encode-timeout=60m",
                        "media.hls.packaging.probe-timeout=60s",
                        "media.hls.packaging.scratch-root-dir=" + tempDir.resolve("scratch").toAbsolutePath(),
                        "media.hls.packaging.min-free-scratch-bytes=1024",
                        "media.hls.packaging.x264-preset=veryfast",
                        "media.hls.packaging.segment-duration-seconds=6"
                );

        enabledRunner.run(ctx -> {
            assertThat(ctx).hasNotFailed();
            assertThat(ctx).hasSingleBean(HlsPackagingProperties.class);
            assertThat(ctx).hasSingleBean(HlsFfmpegExecutionGate.class);
            assertThat(ctx).hasSingleBean(BoundedSubprocessRunner.class);
            assertThat(ctx).hasSingleBean(FfprobeMediaSourceProbe.class);
            assertThat(ctx).hasSingleBean(FfmpegHlsPackager.class);
            assertThat(ctx).hasSingleBean(LocalHlsPackageVerifier.class);
            assertThat(ctx).hasSingleBean(LocalFfmpegHlsPackagingEngine.class);

            HlsPackagingProperties props = ctx.getBean(HlsPackagingProperties.class);
            assertThat(props.enabled()).isTrue();
            assertThat(props.encodeTimeout()).isEqualTo(Duration.ofMinutes(60));
            assertThat(props.probeTimeout()).isEqualTo(Duration.ofSeconds(60));
            assertThat(ctx.getBean(HlsFfmpegExecutionGate.class).getAvailablePermits()).isEqualTo(1);
        });
    }

    @Test
    @DisplayName("Validates HlsPackagingProperties bounds and rejects scratchRootDir inside repository worktree")
    void shouldValidatePropertiesAndRejectWorktreeScratchRoot(@TempDir Path tempDir) {
        Path externalScratch = tempDir.resolve("scratch");

        assertThatThrownBy(() -> new HlsPackagingProperties(
                true, " ", "ffprobe", 1, 4, Duration.ofMinutes(60), Duration.ofSeconds(60),
                externalScratch, 0L, "veryfast", 6
        )).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("ffmpeg-path");

        assertThatThrownBy(() -> new HlsPackagingProperties(
                true, "ffmpeg", "ffprobe", 0, 4, Duration.ofMinutes(60), Duration.ofSeconds(60),
                externalScratch, 0L, "veryfast", 6
        )).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("max-concurrent-encodes");

        assertThatThrownBy(() -> new HlsPackagingProperties(
                true, "ffmpeg", "ffprobe", 1, 64, Duration.ofMinutes(60), Duration.ofSeconds(60),
                externalScratch, 0L, "veryfast", 6
        )).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("ffmpeg-threads");

        assertThatThrownBy(() -> new HlsPackagingProperties(
                true, "ffmpeg", "ffprobe", 1, 4, Duration.ZERO, Duration.ofSeconds(60),
                externalScratch, 0L, "veryfast", 6
        )).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("encode-timeout");

        assertThatThrownBy(() -> new HlsPackagingProperties(
                true, "ffmpeg", "ffprobe", 1, 4, Duration.ofMinutes(60), Duration.ofSeconds(60),
                externalScratch, 0L, "placebo", 6
        )).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Unsupported x264 preset");

        assertThatThrownBy(() -> new HlsPackagingProperties(
                true, "ffmpeg", "ffprobe", 1, 4, Duration.ofMinutes(60), Duration.ofSeconds(60),
                externalScratch, 0L, "veryfast", 12
        )).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("segment-duration-seconds");

        Path worktreeDir = Path.of("").toAbsolutePath().resolve("target/hls-worktree-scratch-forbidden");
        assertThatThrownBy(() -> HlsPackagingProperties.localDefaults(worktreeDir))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("outside repository worktree");
    }

    @Test
    @DisplayName("Execution gate serializes concurrent encodes and releases permit on failure and interruption")
    void shouldEnforceConcurrencyLimitAndAlwaysReleasePermits() throws Exception {
        HlsFfmpegExecutionGate gate = new HlsFfmpegExecutionGate(1);
        CountDownLatch enteredFirst = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        AtomicInteger maxConcurrentSeen = new AtomicInteger();
        AtomicInteger active = new AtomicInteger();

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<String> first = pool.submit(() -> gate.execute(() -> {
                int now = active.incrementAndGet();
                maxConcurrentSeen.accumulateAndGet(now, Math::max);
                enteredFirst.countDown();
                releaseFirst.await(5, TimeUnit.SECONDS);
                active.decrementAndGet();
                return "ok-1";
            }));

            assertThat(enteredFirst.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(gate.getAvailablePermits()).isZero();

            Future<String> second = pool.submit(() -> gate.execute(() -> {
                int now = active.incrementAndGet();
                maxConcurrentSeen.accumulateAndGet(now, Math::max);
                active.decrementAndGet();
                return "ok-2";
            }));

            // Second task must still be queued behind the single permit
            Thread.sleep(60);
            assertThat(second.isDone()).isFalse();

            releaseFirst.countDown();
            assertThat(first.get(5, TimeUnit.SECONDS)).isEqualTo("ok-1");
            assertThat(second.get(5, TimeUnit.SECONDS)).isEqualTo("ok-2");
            assertThat(maxConcurrentSeen.get()).isEqualTo(1);
            assertThat(gate.getAvailablePermits()).isEqualTo(1);

            // Runtime exception releases permit
            assertThatThrownBy(() -> gate.execute(() -> {
                throw new IllegalStateException("boom");
            })).isInstanceOf(IllegalStateException.class);
            assertThat(gate.getAvailablePermits()).isEqualTo(1);

            // Interrupted acquire preserves interrupt flag without leaking permits
            Thread.currentThread().interrupt();
            try {
                assertThatThrownBy(() -> gate.execute(() -> "unreachable"))
                        .isInstanceOf(InterruptedException.class);
                assertThat(Thread.currentThread().isInterrupted()).isTrue();
            } finally {
                Thread.interrupted();
            }
            assertThat(gate.getAvailablePermits()).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("BoundedSubprocessRunner forcefully terminates hanging process on timeout and interruption")
    void shouldTerminateProcessOnTimeoutAndInterruption(@TempDir Path tempDir) {
        HangingProcess timeoutProc = new HangingProcess();
        BoundedSubprocessRunner timeoutRunner = new BoundedSubprocessRunner((cmd, dir) -> timeoutProc);

        assertThatThrownBy(() -> timeoutRunner.execute(
                List.of("ffmpeg", "-version"), tempDir, Duration.ofMillis(50), 4096
        )).isInstanceOf(TimeoutException.class);
        assertThat(timeoutProc.destroyedForcibly.get()).isTrue();

        HangingProcess interruptedProc = new HangingProcess();
        BoundedSubprocessRunner interruptRunner = new BoundedSubprocessRunner((cmd, dir) -> interruptedProc);
        Thread.currentThread().interrupt();
        try {
            assertThatThrownBy(() -> interruptRunner.execute(
                    List.of("ffmpeg", "-version"), tempDir, Duration.ofSeconds(5), 4096
            )).isInstanceOf(InterruptedException.class);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
            assertThat(interruptedProc.destroyedForcibly.get()).isTrue();
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    @DisplayName("Engine cleans up attempt directory on timeout, verification failure, interruption, and close() without harming siblings or source")
    void shouldCleanUpAttemptDirectorySafelyOnAllFailureModesAndClose(@TempDir Path tempDir) throws Exception {
        Path srcDir = Files.createDirectories(tempDir.resolve("src"));
        Path scratchRoot = Files.createDirectories(tempDir.resolve("scratch"));
        byte[] sourceBytes = new byte[]{1, 2, 3, 4};
        Path sourceFile = Files.write(srcDir.resolve("episode.mp4"), sourceBytes);
        Path siblingFile = Files.writeString(scratchRoot.resolve("unrelated-keep.txt"), "do-not-delete");
        Path siblingDirFile = Files.writeString(
                Files.createDirectories(scratchRoot.resolve("other-attempt")).resolve("keep.bin"),
                "preserve-me"
        );
        Path unrelatedPrefixedSiblingFile = Files.writeString(
                Files.createDirectories(scratchRoot.resolve("hls-pkg-unrelated")).resolve("keep.txt"),
                "unrelated-attempt-keep"
        );

        HlsPackagingProperties props = new HlsPackagingProperties(
                true, "ffmpeg", "ffprobe", 1, 2, Duration.ofSeconds(30), Duration.ofSeconds(10),
                scratchRoot, 0L, "veryfast", 6
        );
        HlsFfmpegExecutionGate gate = new HlsFfmpegExecutionGate(props);
        BoundedSubprocessRunner dummyRunner = new BoundedSubprocessRunner((cmd, dir) -> {
            throw new AssertionError("Should not launch OS process");
        });
        FfprobeMediaSourceProbe stubProbe = new FfprobeMediaSourceProbe(props, dummyRunner) {
            @Override
            public HlsLadderPlanner.ProbedVideoSource probeSource(Path src) {
                return sample720pSource();
            }
        };
        LocalHlsPackageVerifier passVerifier = new LocalHlsPackageVerifier(stubProbe) {
            @Override
            public LocalHlsVerificationReport verify(Path packageRoot, HlsLadderPlanner.HlsPackagingPlan plan) {
                return new LocalHlsVerificationReport(
                        1024L, 5, 3, 2, Map.of("720p", 2), 2, "abc",
                        Map.of("720p", List.of(6.0, 6.0)), List.of(6.0, 6.0),
                        "aac-lc 128k ch=2 48000Hz", "{}"
                );
            }
        };

        // 1. Timeout after partial file creation removes only the attempt directory
        FfmpegHlsPackager timeoutPackager = new FfmpegHlsPackager(props, dummyRunner) {
            @Override
            public PackagingExecution packageToDirectory(Path src, Path outputRoot, HlsLadderPlanner.HlsPackagingPlan plan)
                    throws IOException, TimeoutException {
                Files.createDirectories(outputRoot.resolve("video/720p"));
                Files.writeString(outputRoot.resolve("video/720p/partial.m4s"), "partial");
                throw new TimeoutException("Simulated FFmpeg timeout");
            }
        };
        LocalFfmpegHlsPackagingEngine timeoutEngine =
                new LocalFfmpegHlsPackagingEngine(props, gate, stubProbe, timeoutPackager, passVerifier);
        assertThatThrownBy(() -> timeoutEngine.packageLocalSource(sourceFile))
                .isInstanceOf(TimeoutException.class);
        assertOnlySiblingEntriesExist(scratchRoot, siblingFile, siblingDirFile, unrelatedPrefixedSiblingFile, sourceFile, sourceBytes);
        assertThat(gate.getAvailablePermits()).isEqualTo(1);

        // 2. Verification failure cleans up the attempt directory
        FfmpegHlsPackager writingPackager = new FfmpegHlsPackager(props, dummyRunner) {
            @Override
            public PackagingExecution packageToDirectory(Path src, Path outputRoot, HlsLadderPlanner.HlsPackagingPlan plan)
                    throws IOException {
                Files.createDirectories(outputRoot.resolve("video/720p"));
                Files.writeString(outputRoot.resolve("master.m3u8"), "#EXTM3U");
                return new PackagingExecution(outputRoot, plan, List.of("ffmpeg"), 12L, "");
            }
        };
        LocalHlsPackageVerifier failingVerifier = new LocalHlsPackageVerifier(stubProbe) {
            @Override
            public LocalHlsVerificationReport verify(Path packageRoot, HlsLadderPlanner.HlsPackagingPlan plan) {
                throw new HlsPackageVerificationException("Simulated verification reject");
            }
        };
        LocalFfmpegHlsPackagingEngine verifyFailEngine =
                new LocalFfmpegHlsPackagingEngine(props, gate, stubProbe, writingPackager, failingVerifier);
        assertThatThrownBy(() -> verifyFailEngine.packageLocalSource(sourceFile))
                .isInstanceOf(LocalHlsPackageVerifier.HlsPackageVerificationException.class);
        assertOnlySiblingEntriesExist(scratchRoot, siblingFile, siblingDirFile, unrelatedPrefixedSiblingFile, sourceFile, sourceBytes);
        assertThat(gate.getAvailablePermits()).isEqualTo(1);

        // 3. Interruption cleans up the attempt directory
        FfmpegHlsPackager interruptingPackager = new FfmpegHlsPackager(props, dummyRunner) {
            @Override
            public PackagingExecution packageToDirectory(Path src, Path outputRoot, HlsLadderPlanner.HlsPackagingPlan plan)
                    throws IOException, InterruptedException {
                Files.createDirectories(outputRoot.resolve("audio/main"));
                Thread.currentThread().interrupt();
                throw new InterruptedException("Simulated worker interruption");
            }
        };
        LocalFfmpegHlsPackagingEngine interruptEngine =
                new LocalFfmpegHlsPackagingEngine(props, gate, stubProbe, interruptingPackager, passVerifier);
        try {
            assertThatThrownBy(() -> interruptEngine.packageLocalSource(sourceFile))
                    .isInstanceOf(InterruptedException.class);
            assertOnlySiblingEntriesExist(scratchRoot, siblingFile, siblingDirFile, unrelatedPrefixedSiblingFile, sourceFile, sourceBytes);
            assertThat(gate.getAvailablePermits()).isEqualTo(1);
        } finally {
            Thread.interrupted();
        }

        // 4. Success + unique attempt dirs + idempotent repeated close()
        LocalFfmpegHlsPackagingEngine successEngine =
                new LocalFfmpegHlsPackagingEngine(props, gate, stubProbe, writingPackager, passVerifier);
        Path createdAttemptDir1;
        Path createdAttemptDir2;
        try (LocalFfmpegHlsPackagingEngine.LocalHlsPackagingResult res1 = successEngine.packageLocalSource(sourceFile);
             LocalFfmpegHlsPackagingEngine.LocalHlsPackagingResult res2 = successEngine.packageLocalSource(sourceFile)) {
            createdAttemptDir1 = res1.packageRoot();
            createdAttemptDir2 = res2.packageRoot();
            assertThat(createdAttemptDir1).isNotEqualTo(createdAttemptDir2);
            assertThat(Files.isDirectory(createdAttemptDir1)).isTrue();
            assertThat(Files.isDirectory(createdAttemptDir2)).isTrue();
            assertThat(createdAttemptDir1.getParent()).isEqualTo(scratchRoot.toRealPath());
            res1.close();
            // Repeated close must remain safe and must not disturb sibling or second active attempt dir
            res1.close();
            assertThat(Files.exists(createdAttemptDir1)).isFalse();
            assertThat(Files.isDirectory(createdAttemptDir2)).isTrue();
            assertThat(Files.exists(unrelatedPrefixedSiblingFile)).isTrue();
        }
        assertThat(Files.exists(createdAttemptDir2)).isFalse();
        assertOnlySiblingEntriesExist(scratchRoot, siblingFile, siblingDirFile, unrelatedPrefixedSiblingFile, sourceFile, sourceBytes);
        assertThat(gate.getAvailablePermits()).isEqualTo(1);
    }

    @Test
    @DisplayName("Rejects missing, empty, in-scratch source files and insufficient scratch disk space")
    void shouldRejectInvalidSourcePathsAndLowScratchDiskSpace(@TempDir Path tempDir) throws Exception {
        Path scratchRoot = Files.createDirectories(tempDir.resolve("scratch"));
        Path outsideDir = Files.createDirectories(tempDir.resolve("src"));
        Path emptyFile = Files.write(outsideDir.resolve("empty.mp4"), new byte[0]);
        Path validFile = Files.write(outsideDir.resolve("valid.mp4"), new byte[]{1, 2, 3});
        Path insideScratchFile = Files.write(scratchRoot.resolve("bad.mp4"), new byte[]{1, 2, 3});

        BoundedSubprocessRunner dummyRunner = new BoundedSubprocessRunner((cmd, dir) -> {
            throw new AssertionError("Should not run");
        });

        HlsPackagingProperties normalProps = new HlsPackagingProperties(
                true, "ffmpeg", "ffprobe", 1, 2, Duration.ofMinutes(1), Duration.ofSeconds(10),
                scratchRoot, 0L, "veryfast", 6
        );
        FfprobeMediaSourceProbe probe = new FfprobeMediaSourceProbe(normalProps, dummyRunner);
        LocalFfmpegHlsPackagingEngine engine = new LocalFfmpegHlsPackagingEngine(
                normalProps,
                new HlsFfmpegExecutionGate(normalProps),
                probe,
                new FfmpegHlsPackager(normalProps, dummyRunner),
                new LocalHlsPackageVerifier(probe)
        );

        assertThatThrownBy(() -> engine.packageLocalSource(outsideDir.resolve("missing.mp4")))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("missing or unreadable");

        assertThatThrownBy(() -> engine.packageLocalSource(emptyFile))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("empty");

        assertThatThrownBy(() -> engine.packageLocalSource(insideScratchFile))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must not reside inside");

        HlsPackagingProperties hugeMinFreeProps = new HlsPackagingProperties(
                true, "ffmpeg", "ffprobe", 1, 2, Duration.ofMinutes(1), Duration.ofSeconds(10),
                scratchRoot, Long.MAX_VALUE, "veryfast", 6
        );
        LocalFfmpegHlsPackagingEngine lowSpaceEngine = new LocalFfmpegHlsPackagingEngine(
                hugeMinFreeProps,
                new HlsFfmpegExecutionGate(hugeMinFreeProps),
                probe,
                new FfmpegHlsPackager(hugeMinFreeProps, dummyRunner),
                new LocalHlsPackageVerifier(probe)
        );
        assertThatThrownBy(() -> lowSpaceEngine.packageLocalSource(validFile))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("Insufficient usable scratch disk space");
    }

    @Test
    @DisplayName("Hardened verifier rejects query/fragment/backslash/control-char URIs, unsafe directives, trailing post-ENDLIST lines, and symlinks")
    void shouldRejectHardenedManifestAndPathSecurityViolations(@TempDir Path tempDir) throws Exception {
        Path root = Files.createDirectories(tempDir.resolve("pkg"));
        HlsLadderPlannerAndVerifierUnitTest.writeMinimalValid720pPackage(root);
        Path realRoot = root.toRealPath();

        // safeResolveWithinRoot checks
        for (String unsafe : List.of(
                "seg_00000.m4s?token=abc",
                "seg_00000.m4s#frag",
                "sub\\seg.m4s",
                "../secret.m4s",
                "https://evil.example/seg.m4s",
                "/abs/seg.m4s",
                "seg\u0007.m4s"
        )) {
            assertThatThrownBy(() -> LocalHlsPackageVerifier.safeResolveWithinRoot(realRoot, realRoot, unsafe))
                    .isInstanceOf(LocalHlsPackageVerifier.HlsPackageVerificationException.class);
        }

        FfprobeMediaSourceProbe stubProbe = new FfprobeMediaSourceProbe("ffprobe", new BoundedSubprocessRunner(), Duration.ofSeconds(60)) {
            @Override
            public HlsPlaylistProbe probeHlsMediaPlaylist(Path playlistPath) {
                String p = playlistPath.toString().replace('\\', '/');
                if (p.contains("audio/main")) {
                    return new HlsPlaylistProbe(1, "audio", "aac", 0, 0, 48_000, 2, 6.0, 280);
                }
                return new HlsPlaylistProbe(1, "video", "h264", 1280, 720, 0, 0, 6.0, 144);
            }
        };
        LocalHlsPackageVerifier verifier = new LocalHlsPackageVerifier(stubProbe);
        HlsLadderPlanner.HlsPackagingPlan plan = new HlsLadderPlanner().plan(
                sample720pSource(), 6,
                HlsLadderPlanner.Below720Policy.SOURCE_FALLBACK_NO_UPSCALE,
                HlsLadderPlanner.HdrPolicy.REJECT_EXPLICIT
        );

        Path videoPl = root.resolve("video/720p/playlist.m3u8");
        String origVideoPl = Files.readString(videoPl, StandardCharsets.UTF_8);

        // #EXT-X-KEY rejected
        Files.writeString(videoPl, origVideoPl.replace(
                "#EXT-X-MAP:URI=\"init_0.mp4\"",
                "#EXT-X-KEY:METHOD=AES-128,URI=\"key.bin\"\n#EXT-X-MAP:URI=\"init_0.mp4\""
        ));
        assertThatThrownBy(() -> verifier.verify(root, plan))
                .isInstanceOf(LocalHlsPackageVerifier.HlsPackageVerificationException.class)
                .hasMessageContaining("#EXT-X-KEY");

        // #EXT-X-BYTERANGE rejected
        Files.writeString(videoPl, origVideoPl.replace(
                "#EXTINF:6.000000,",
                "#EXT-X-BYTERANGE:100@0\n#EXTINF:6.000000,"
        ));
        assertThatThrownBy(() -> verifier.verify(root, plan))
                .isInstanceOf(LocalHlsPackageVerifier.HlsPackageVerificationException.class)
                .hasMessageContaining("#EXT-X-BYTERANGE");

        // Content after #EXT-X-ENDLIST rejected
        Files.writeString(videoPl, origVideoPl + "\nseg_00000.m4s\n");
        assertThatThrownBy(() -> verifier.verify(root, plan))
                .isInstanceOf(LocalHlsPackageVerifier.HlsPackageVerificationException.class)
                .hasMessageContaining("Unexpected trailing content after #EXT-X-ENDLIST");

        Files.writeString(videoPl, origVideoPl);

        // Symbolic link rejection (enforced whenever OS grants symlink creation)
        Path outsideTarget = Files.writeString(tempDir.resolve("outside.txt"), "secret");
        Path symlinkPath = root.resolve("video/720p/link.m4s");
        boolean symlinkCreated = false;
        try {
            Files.createSymbolicLink(symlinkPath, outsideTarget);
            symlinkCreated = true;
        } catch (IOException | UnsupportedOperationException | SecurityException ignored) {
            // Non-elevated Windows environments may disallow POSIX symlink creation
        }
        if (symlinkCreated) {
            assertThatThrownBy(() -> verifier.verify(root, plan))
                    .isInstanceOf(LocalHlsPackageVerifier.HlsPackageVerificationException.class)
                    .hasMessageContaining("Symbolic link");
        }
    }

    @Test
    @DisplayName("LocalHlsPackagingResult is non-subclassable with private constructor so callers cannot forge cleanup ownership")
    void shouldPreventPublicCallerForgeryOfCleanupResult() {
        Class<?> resultClass = LocalFfmpegHlsPackagingEngine.LocalHlsPackagingResult.class;
        assertThat(Modifier.isPublic(resultClass.getModifiers())).isTrue();
        assertThat(Modifier.isFinal(resultClass.getModifiers())).isTrue();
        assertThat(resultClass.getConstructors()).isEmpty();
        Constructor<?>[] declared = resultClass.getDeclaredConstructors();
        assertThat(declared).hasSize(1);
        assertThat(Modifier.isPrivate(declared[0].getModifiers())).isTrue();
        assertThat(AutoCloseable.class.isAssignableFrom(resultClass)).isTrue();
    }

    @Test
    @DisplayName("Accepts legitimate external scratch paths while rejecting worktree subpaths and relative traversal into worktree")
    void shouldAcceptLegitimateScratchPathsAndRejectWorktreeTraversal(@TempDir Path tempDir) {
        Path externalNested = tempDir.resolve("valid-scratch/nested-level");
        HlsPackagingProperties props = HlsPackagingProperties.localDefaults(externalNested);
        assertThat(props.scratchRootDir()).isEqualTo(externalNested.toAbsolutePath().normalize());

        Path worktreeRoot = Path.of("").toAbsolutePath().normalize();
        Path relativeTraversalIntoRepo = worktreeRoot.resolve("target/sub/../hls-traversal-check");
        assertThatThrownBy(() -> HlsPackagingProperties.localDefaults(relativeTraversalIntoRepo))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("outside repository worktree");
    }

    @Test
    @DisplayName("Rejects scratch paths resolving into repository through a symbolic link before creating any directories")
    void shouldRejectScratchPathResolvingIntoRepositoryThroughSymlink(@TempDir Path tempDir) throws Exception {
        Path repoSubdir = Files.createDirectories(Path.of("").toAbsolutePath().normalize().resolve("target"));
        Path symlinkAlias = tempDir.resolve("repo-symlink-alias");
        try {
            Files.createSymbolicLink(symlinkAlias, repoSubdir);
        } catch (IOException | UnsupportedOperationException | SecurityException e) {
            Assumptions.assumeTrue(false, "POSIX symbolic link creation unavailable on this host: " + e.getMessage());
            return;
        }

        try {
            assertThatThrownBy(() -> HlsPackagingProperties.localDefaults(symlinkAlias))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("outside repository worktree");

            Path nonExistentNestedUnderSymlink = symlinkAlias.resolve("should-never-be-created");
            assertThatThrownBy(() -> HlsPackagingProperties.localDefaults(nonExistentNestedUnderSymlink))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("outside repository worktree");
            assertThat(Files.exists(nonExistentNestedUnderSymlink, LinkOption.NOFOLLOW_LINKS)).isFalse();

            // Verify runtime TOCTOU protection inside ensureValidScratchRoot() as well
            Path toctouAlias = tempDir.resolve("toctou-alias");
            HlsPackagingProperties lateLinkProps = new HlsPackagingProperties(
                    true, "ffmpeg", "ffprobe", 1, 2, Duration.ofMinutes(1), Duration.ofSeconds(10),
                    toctouAlias, 0L, "veryfast", 6
            );
            Path srcDir = Files.createDirectories(tempDir.resolve("src"));
            Path sourceFile = Files.write(srcDir.resolve("episode.mp4"), new byte[]{1, 2, 3, 4});
            Files.createSymbolicLink(toctouAlias, repoSubdir);
            try {
                BoundedSubprocessRunner dummyRunner = new BoundedSubprocessRunner((cmd, dir) -> {
                    throw new AssertionError("Should not run");
                });
                FfprobeMediaSourceProbe probe = new FfprobeMediaSourceProbe(lateLinkProps, dummyRunner) {
                    @Override
                    public HlsLadderPlanner.ProbedVideoSource probeSource(Path src) {
                        return sample720pSource();
                    }
                };
                LocalFfmpegHlsPackagingEngine engine = new LocalFfmpegHlsPackagingEngine(
                        lateLinkProps,
                        new HlsFfmpegExecutionGate(lateLinkProps),
                        probe,
                        new FfmpegHlsPackager(lateLinkProps, dummyRunner),
                        new LocalHlsPackageVerifier(probe)
                );
                assertThatThrownBy(() -> engine.packageLocalSource(sourceFile))
                        .isInstanceOf(IllegalArgumentException.class)
                        .hasMessageContaining("outside repository worktree");
            } finally {
                Files.deleteIfExists(toctouAlias);
            }
        } finally {
            Files.deleteIfExists(symlinkAlias);
        }
    }

    private static void assertOnlySiblingEntriesExist(
            Path scratchRoot,
            Path siblingFile,
            Path siblingDirFile,
            Path unrelatedPrefixedSiblingFile,
            Path sourceFile,
            byte[] expectedSourceBytes
    ) throws IOException {
        assertThat(Files.exists(sourceFile)).isTrue();
        assertThat(Files.readAllBytes(sourceFile)).isEqualTo(expectedSourceBytes);
        assertThat(Files.readString(siblingFile)).isEqualTo("do-not-delete");
        assertThat(Files.readString(siblingDirFile)).isEqualTo("preserve-me");
        assertThat(Files.readString(unrelatedPrefixedSiblingFile)).isEqualTo("unrelated-attempt-keep");
        try (Stream<Path> children = Files.list(scratchRoot)) {
            List<String> names = children.map(p -> p.getFileName().toString()).sorted().toList();
            assertThat(names).containsExactly("hls-pkg-unrelated", "other-attempt", "unrelated-keep.txt");
        }
    }

    private static final class HangingProcess extends Process {
        private final AtomicBoolean destroyedForcibly = new AtomicBoolean(false);
        private final InputStream emptyStream = new ByteArrayInputStream(new byte[0]);

        @Override
        public OutputStream getOutputStream() {
            return OutputStream.nullOutputStream();
        }

        @Override
        public InputStream getInputStream() {
            return emptyStream;
        }

        @Override
        public InputStream getErrorStream() {
            return emptyStream;
        }

        @Override
        public int waitFor() throws InterruptedException {
            Thread.sleep(5_000);
            return 0;
        }

        @Override
        public boolean waitFor(long timeout, TimeUnit unit) throws InterruptedException {
            if (Thread.currentThread().isInterrupted()) {
                throw new InterruptedException("Interrupted wait");
            }
            return false;
        }

        @Override
        public int exitValue() {
            return 0;
        }

        @Override
        public void destroy() {
            destroyedForcibly.set(true);
        }

        @Override
        public Process destroyForcibly() {
            destroyedForcibly.set(true);
            return this;
        }

        @Override
        public boolean isAlive() {
            return !destroyedForcibly.get();
        }

        @Override
        public Stream<ProcessHandle> descendants() {
            return Stream.empty();
        }
    }
}
