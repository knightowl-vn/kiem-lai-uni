package com.universe.media.infrastructure.hls.poc;

import com.universe.media.infrastructure.hls.BoundedSubprocessRunner;
import com.universe.media.infrastructure.hls.FfmpegHlsPackager;
import com.universe.media.infrastructure.hls.FfprobeMediaSourceProbe;
import com.universe.media.infrastructure.hls.HlsFfmpegExecutionGate;
import com.universe.media.infrastructure.hls.HlsLadderPlanner;
import com.universe.media.infrastructure.hls.HlsPackagingProperties;
import com.universe.media.infrastructure.hls.LocalFfmpegHlsPackagingEngine;
import com.universe.media.infrastructure.hls.LocalHlsPackageVerifier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Local-only real FFmpeg + FFprobe PoC verification suite exercising the promoted
 * production {@link LocalFfmpegHlsPackagingEngine} in OS temp directories strictly
 * outside the repository worktree.
 *
 * <p><strong>CI Safety Gate:</strong> Disabled by default during normal {@code ./mvnw -B test} / {@code mvn test}
 * runs so CI without FFmpeg never executes external transcoding or 4K encoding. Enable explicitly via:
 * {@code .\mvnw.cmd test "-Dtest=HlsFfmpegLocalPocExecutionTest" "-Dkiemlai.hls.local-poc.enabled=true" "-DredirectTestOutputToFile=true"}.
 */
@Tag("local-ffmpeg-poc")
@EnabledIfSystemProperty(named = HlsFfmpegLocalPocExecutionTest.OPT_IN_PROPERTY, matches = "(?i)true")
public class HlsFfmpegLocalPocExecutionTest {

    public static final String OPT_IN_PROPERTY = "kiemlai.hls.local-poc.enabled";

    private final BoundedSubprocessRunner processRunner = new BoundedSubprocessRunner();

    private EngineFixture createFixture(Path scratchRoot) {
        HlsPackagingProperties properties = HlsPackagingProperties.localDefaults(scratchRoot);
        HlsFfmpegExecutionGate gate = new HlsFfmpegExecutionGate(properties);
        FfprobeMediaSourceProbe probe = new FfprobeMediaSourceProbe(properties, processRunner);
        FfmpegHlsPackager packager = new FfmpegHlsPackager(properties, processRunner);
        LocalHlsPackageVerifier verifier = new LocalHlsPackageVerifier(probe);
        LocalFfmpegHlsPackagingEngine engine =
                new LocalFfmpegHlsPackagingEngine(properties, gate, probe, packager, verifier);
        return new EngineFixture(gate, verifier, engine);
    }

    private record EngineFixture(
            HlsFfmpegExecutionGate gate,
            LocalHlsPackageVerifier verifier,
            LocalFfmpegHlsPackagingEngine engine
    ) {
    }

    @Test
    @DisplayName("720p synthetic source (24000/1001 fps + stereo audio) produces 720p-only + 1 shared audio")
    void shouldPackageAndVerify720pSource() throws Exception {
        Path work = Files.createTempDirectory("kiemlai-hls2b1-720p-");
        try {
            Path srcDir = Files.createDirectories(work.resolve("src"));
            Path scratchRoot = Files.createDirectories(work.resolve("scratch"));
            Path src = srcDir.resolve("src_720p.mp4");
            generateSyntheticMp4(srcDir, src, 1280, 720, "24000/1001", 12, true, 2);

            EngineFixture fixture = createFixture(scratchRoot);
            Path producedDir;
            try (LocalFfmpegHlsPackagingEngine.LocalHlsPackagingResult result = fixture.engine().packageLocalSource(src)) {
                producedDir = result.packageRoot();
                assertThat(result.plan().videoTiers()).extracting(HlsLadderPlanner.VideoTierSpec::label).containsExactly("720p");
                assertThat(result.verificationReport().playlistCount()).isEqualTo(3);
                assertThat(result.verificationReport().initFileCount()).isEqualTo(2);
                assertThat(result.verificationReport().audioSegmentCount()).isGreaterThanOrEqualTo(2);
                assertThat(result.verificationReport().videoSegmentCountsByTier().get("720p")).isEqualTo(2);
                assertThat(fixture.gate().getAvailablePermits()).isEqualTo(1);
                printMetrics("720p-12s-6s", result);
            }
            assertThat(Files.exists(producedDir)).isFalse();
        } finally {
            deleteRecursively(work);
        }
    }

    @Test
    @DisplayName("1080p synthetic source (30000/1001 fps + stereo audio) produces 720p+1080p + 1 shared audio; truncation fails")
    void shouldPackageAndVerify1080pSourceAndRejectTampering() throws Exception {
        Path work = Files.createTempDirectory("kiemlai-hls2b1-1080p-");
        try {
            Path srcDir = Files.createDirectories(work.resolve("src"));
            Path scratchRoot = Files.createDirectories(work.resolve("scratch"));
            Path src = srcDir.resolve("src_1080p.mp4");
            generateSyntheticMp4(srcDir, src, 1920, 1080, "30000/1001", 12, true, 2);

            EngineFixture fixture = createFixture(scratchRoot);
            try (LocalFfmpegHlsPackagingEngine.LocalHlsPackagingResult result = fixture.engine().packageLocalSource(src)) {
                assertThat(result.plan().videoTiers())
                        .extracting(HlsLadderPlanner.VideoTierSpec::label)
                        .containsExactly("720p", "1080p");
                assertThat(result.verificationReport().playlistCount()).isEqualTo(4);
                assertThat(result.verificationReport().initFileCount()).isEqualTo(3);
                assertThat(result.verificationReport().videoSegmentCountsByTier().get("720p")).isEqualTo(2);
                assertThat(result.verificationReport().videoSegmentCountsByTier().get("1080p")).isEqualTo(2);
                printMetrics("1080p-12s-6s", result);

                // Real truncated fMP4 segment rejection check
                Path first1080Seg = result.packageRoot().resolve("video/1080p/seg_00000.m4s");
                Files.write(first1080Seg, new byte[]{0, 0, 0, 12, 'm', 'o', 'o', 'f'});
                assertThatThrownBy(() -> fixture.verifier().verify(result.packageRoot(), result.plan()))
                        .isInstanceOf(LocalHlsPackageVerifier.HlsPackageVerificationException.class);
            }
        } finally {
            deleteRecursively(work);
        }
    }

    @Test
    @DisplayName("4K 2160p synthetic source (bounded 12s) produces 720p+1080p+2160p + 1 shared audio")
    void shouldPackageAndVerify2160p4kSource() throws Exception {
        Path work = Files.createTempDirectory("kiemlai-hls2b1-4k-");
        try {
            Path srcDir = Files.createDirectories(work.resolve("src"));
            Path scratchRoot = Files.createDirectories(work.resolve("scratch"));
            Path src = srcDir.resolve("src_2160p.mp4");
            generateSyntheticMp4(srcDir, src, 3840, 2160, "24", 12, true, 2);

            EngineFixture fixture = createFixture(scratchRoot);
            try (LocalFfmpegHlsPackagingEngine.LocalHlsPackagingResult result = fixture.engine().packageLocalSource(src)) {
                assertThat(result.plan().videoTiers())
                        .extracting(HlsLadderPlanner.VideoTierSpec::label)
                        .containsExactly("720p", "1080p", "2160p");
                assertThat(result.verificationReport().playlistCount()).isEqualTo(5);
                assertThat(result.verificationReport().initFileCount()).isEqualTo(4);
                assertThat(result.verificationReport().videoSegmentCountsByTier().get("2160p")).isEqualTo(2);
                printMetrics("2160p-12s-6s", result);
            }
        } finally {
            deleteRecursively(work);
        }
    }

    @Test
    @DisplayName("Below-720p (854x480) fallback packages at native resolution without upscaling + 1 shared audio")
    void shouldPackageBelow720pSourceWithoutUpscaling() throws Exception {
        Path work = Files.createTempDirectory("kiemlai-hls2b1-480p-");
        try {
            Path srcDir = Files.createDirectories(work.resolve("src"));
            Path scratchRoot = Files.createDirectories(work.resolve("scratch"));
            Path src = srcDir.resolve("src_480p.mp4");
            generateSyntheticMp4(srcDir, src, 854, 480, "24", 12, true, 2);

            EngineFixture fixture = createFixture(scratchRoot);
            try (LocalFfmpegHlsPackagingEngine.LocalHlsPackagingResult result = fixture.engine().packageLocalSource(src)) {
                assertThat(result.plan().below720FallbackApplied()).isTrue();
                assertThat(result.plan().videoTiers()).hasSize(1);
                assertThat(result.plan().videoTiers().get(0).width()).isEqualTo(854);
                assertThat(result.plan().videoTiers().get(0).height()).isEqualTo(480);
                assertThat(result.verificationReport().playlistCount()).isEqualTo(3);
                assertThat(result.verificationReport().initFileCount()).isEqualTo(2);
                printMetrics("480p-fallback-12s-6s", result);
            }
        } finally {
            deleteRecursively(work);
        }
    }

    @Test
    @DisplayName("No-audio 1080p source produces valid video-only fMP4 HLS package with zero audio renditions")
    void shouldPackageVideoOnlyNoAudioSource() throws Exception {
        Path work = Files.createTempDirectory("kiemlai-hls2b1-noaudio-");
        try {
            Path srcDir = Files.createDirectories(work.resolve("src"));
            Path scratchRoot = Files.createDirectories(work.resolve("scratch"));
            Path src = srcDir.resolve("src_noaudio.mp4");
            generateSyntheticMp4(srcDir, src, 1920, 1080, "24", 12, false, 0);

            EngineFixture fixture = createFixture(scratchRoot);
            try (LocalFfmpegHlsPackagingEngine.LocalHlsPackagingResult result = fixture.engine().packageLocalSource(src)) {
                assertThat(result.probedSource().hasAudio()).isFalse();
                assertThat(result.plan().includeAudio()).isFalse();
                assertThat(result.verificationReport().playlistCount()).isEqualTo(3);
                assertThat(result.verificationReport().initFileCount()).isEqualTo(2);
                assertThat(result.verificationReport().audioSegmentCount()).isZero();
                assertThat(Files.exists(result.packageRoot().resolve("audio"))).isFalse();
                printMetrics("1080p-noaudio-12s-6s", result);
            }
        } finally {
            deleteRecursively(work);
        }
    }

    @Test
    @DisplayName("6-second vs 8-second segment duration comparison on identical 24s 1080p + stereo source")
    void shouldCompare6sAnd8sSegmentDurationsOn1080p24sSource() throws Exception {
        Path work = Files.createTempDirectory("kiemlai-hls2b1-cmp-");
        try {
            Path srcDir = Files.createDirectories(work.resolve("src"));
            Path scratchRoot = Files.createDirectories(work.resolve("scratch"));
            Path src = srcDir.resolve("src_1080p_24s.mp4");
            generateSyntheticMp4(srcDir, src, 1920, 1080, "24", 24, true, 2);

            EngineFixture fixture = createFixture(scratchRoot);
            try (LocalFfmpegHlsPackagingEngine.LocalHlsPackagingResult res6 = fixture.engine().packageLocalSource(
                    src, 6, HlsLadderPlanner.Below720Policy.SOURCE_FALLBACK_NO_UPSCALE, HlsLadderPlanner.HdrPolicy.REJECT_EXPLICIT
            );
                 LocalFfmpegHlsPackagingEngine.LocalHlsPackagingResult res8 = fixture.engine().packageLocalSource(
                         src, 8, HlsLadderPlanner.Below720Policy.SOURCE_FALLBACK_NO_UPSCALE, HlsLadderPlanner.HdrPolicy.REJECT_EXPLICIT
                 )) {
                assertThat(res6.verificationReport().videoSegmentCountsByTier().get("1080p")).isEqualTo(4);
                assertThat(res8.verificationReport().videoSegmentCountsByTier().get("1080p")).isEqualTo(3);
                assertThat(res8.verificationReport().objectCount()).isLessThan(res6.verificationReport().objectCount());

                printMetrics("cmp-1080p-24s-6s", res6);
                printMetrics("cmp-1080p-24s-8s", res8);
            }
        } finally {
            deleteRecursively(work);
        }
    }

    private void generateSyntheticMp4(
            Path workDir,
            Path outputFile,
            int width,
            int height,
            String fpsExpr,
            int durationSeconds,
            boolean withAudio,
            int audioChannels
    ) throws Exception {
        List<String> cmd = withAudio
                ? List.of(
                "ffmpeg", "-y", "-hide_banner", "-v", "error", "-threads", "4",
                "-f", "lavfi", "-i", "testsrc2=size=" + width + "x" + height + ":rate=" + fpsExpr + ":duration=" + durationSeconds,
                "-f", "lavfi", "-i", "sine=frequency=440:sample_rate=48000:duration=" + durationSeconds,
                "-c:v", "libx264", "-preset", "ultrafast", "-pix_fmt", "yuv420p",
                "-c:a", "aac", "-ac", Integer.toString(audioChannels),
                outputFile.toAbsolutePath().toString()
        )
                : List.of(
                "ffmpeg", "-y", "-hide_banner", "-v", "error", "-threads", "4",
                "-f", "lavfi", "-i", "testsrc2=size=" + width + "x" + height + ":rate=" + fpsExpr + ":duration=" + durationSeconds,
                "-c:v", "libx264", "-preset", "ultrafast", "-pix_fmt", "yuv420p",
                outputFile.toAbsolutePath().toString()
        );

        BoundedSubprocessRunner.ProcessResult result =
                processRunner.execute(cmd, workDir, Duration.ofMinutes(2), 64 * 1024);
        if (result.exitCode() != 0) {
            throw new IOException("Failed generating synthetic test source: " + result.output());
        }
    }

    private static void printMetrics(String label, LocalFfmpegHlsPackagingEngine.LocalHlsPackagingResult result) {
        LocalHlsPackageVerifier.LocalHlsVerificationReport rep = result.verificationReport();
        System.out.println(String.format(
                Locale.ROOT,
                "HLS2B1_POC_METRIC [%s] elapsedMs=%d totalBytes=%d objectCount=%d playlists=%d inits=%d videoSegs=%s audioSegs=%d videoExtinf=%s audioSummary=%s sha256=%s",
                label,
                result.encodeElapsedMillis(),
                rep.totalSizeBytes(),
                rep.objectCount(),
                rep.playlistCount(),
                rep.initFileCount(),
                rep.videoSegmentCountsByTier(),
                rep.audioSegmentCount(),
                rep.videoExtinfDurationsByTier(),
                rep.audioSummary(),
                rep.masterManifestSha256()
        ));
    }

    private static void deleteRecursively(Path dir) throws IOException {
        if (!Files.exists(dir)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(dir)) {
            for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(p);
            }
        }
    }
}
