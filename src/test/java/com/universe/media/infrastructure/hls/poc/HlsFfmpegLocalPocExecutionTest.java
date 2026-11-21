package com.universe.media.infrastructure.hls.poc;

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
 * Local-only real FFmpeg + FFprobe PoC verification suite.
 * Generates bounded synthetic media in the OS temporary directory (strictly outside the Git worktree),
 * packages to fMP4 HLS VOD, validates output manifests/segments/streams, and cleans temporary files.
 *
 * <p><strong>CI Safety Gate:</strong> Disabled by default during normal {@code ./mvnw -B test} / {@code mvn test}
 * runs so CI without FFmpeg never executes external transcoding or 4K encoding. Enable explicitly via:
 * {@code .\mvnw.cmd test "-Dtest=HlsFfmpegLocalPocExecutionTest" "-Dkiemlai.hls.local-poc.enabled=true" "-DredirectTestOutputToFile=true"}.
 */
@Tag("local-ffmpeg-poc")
@EnabledIfSystemProperty(named = HlsFfmpegLocalPocExecutionTest.OPT_IN_PROPERTY, matches = "(?i)true")
class HlsFfmpegLocalPocExecutionTest {

    static final String OPT_IN_PROPERTY = "kiemlai.hls.local-poc.enabled";

    private final BoundedProcessRunner processRunner = new BoundedProcessRunner();
    private final MediaSourceProbe probe = new MediaSourceProbe("ffprobe", processRunner);
    private final HlsLadderPlanner planner = new HlsLadderPlanner();
    private final LocalFfmpegHlsPackager packager = new LocalFfmpegHlsPackager();
    private final HlsPackageVerifier verifier = new HlsPackageVerifier(probe);

    @Test
    @DisplayName("720p synthetic source (24000/1001 fps + stereo audio) produces 720p-only + 1 shared audio")
    void shouldPackageAndVerify720pSource() throws Exception {
        Path work = Files.createTempDirectory("kiemlai-hls2a-720p-");
        try {
            Path src = work.resolve("src_720p.mp4");
            generateSyntheticMp4(work, src, 1280, 720, "24000/1001", 12, true, 2);

            MediaSourceProbe.ProbedSource probed = probe.probeSource(src);
            HlsLadderPlanner.HlsPackagingPlan plan = planner.plan(
                    probed, 6, HlsLadderPlanner.Below720Policy.SOURCE_FALLBACK_NO_UPSCALE, HlsLadderPlanner.HdrPolicy.REJECT_EXPLICIT
            );
            assertThat(plan.videoTiers()).extracting(HlsLadderPlanner.VideoTierSpec::label).containsExactly("720p");

            Path out = work.resolve("hls_out");
            LocalFfmpegHlsPackager.PackagingExecutionResult exec = packager.packageToLocalDirectory(src, out, plan);
            HlsPackageVerifier.HlsLocalVerificationReport report = verifier.verify(out, plan);

            assertThat(report.playlistCount()).isEqualTo(3);
            assertThat(report.initFileCount()).isEqualTo(2);
            assertThat(report.audioSegmentCount()).isGreaterThanOrEqualTo(2);
            assertThat(report.videoSegmentCountsByTier().get("720p")).isEqualTo(2);

            printMetrics("720p-12s-6s", exec, report);
        } finally {
            deleteRecursively(work);
        }
    }

    @Test
    @DisplayName("1080p synthetic source (30000/1001 fps + stereo audio) produces 720p+1080p + 1 shared audio; truncation fails")
    void shouldPackageAndVerify1080pSourceAndRejectTampering() throws Exception {
        Path work = Files.createTempDirectory("kiemlai-hls2a-1080p-");
        try {
            Path src = work.resolve("src_1080p.mp4");
            generateSyntheticMp4(work, src, 1920, 1080, "30000/1001", 12, true, 2);

            MediaSourceProbe.ProbedSource probed = probe.probeSource(src);
            HlsLadderPlanner.HlsPackagingPlan plan = planner.plan(
                    probed, 6, HlsLadderPlanner.Below720Policy.SOURCE_FALLBACK_NO_UPSCALE, HlsLadderPlanner.HdrPolicy.REJECT_EXPLICIT
            );
            assertThat(plan.videoTiers()).extracting(HlsLadderPlanner.VideoTierSpec::label).containsExactly("720p", "1080p");

            Path out = work.resolve("hls_out");
            LocalFfmpegHlsPackager.PackagingExecutionResult exec = packager.packageToLocalDirectory(src, out, plan);
            HlsPackageVerifier.HlsLocalVerificationReport report = verifier.verify(out, plan);

            assertThat(report.playlistCount()).isEqualTo(4);
            assertThat(report.initFileCount()).isEqualTo(3);
            assertThat(report.videoSegmentCountsByTier().get("720p")).isEqualTo(2);
            assertThat(report.videoSegmentCountsByTier().get("1080p")).isEqualTo(2);
            printMetrics("1080p-12s-6s", exec, report);

            // Real truncated fMP4 segment rejection check
            Path first1080Seg = out.resolve("video/1080p/seg_00000.m4s");
            Files.write(first1080Seg, new byte[]{0, 0, 0, 12, 'm', 'o', 'o', 'f'});
            assertThatThrownBy(() -> verifier.verify(out, plan))
                    .isInstanceOf(HlsPackageVerifier.HlsVerificationException.class);
        } finally {
            deleteRecursively(work);
        }
    }

    @Test
    @DisplayName("4K 2160p synthetic source (bounded 12s) produces 720p+1080p+2160p + 1 shared audio")
    void shouldPackageAndVerify2160p4kSource() throws Exception {
        Path work = Files.createTempDirectory("kiemlai-hls2a-4k-");
        try {
            Path src = work.resolve("src_2160p.mp4");
            generateSyntheticMp4(work, src, 3840, 2160, "24", 12, true, 2);

            MediaSourceProbe.ProbedSource probed = probe.probeSource(src);
            HlsLadderPlanner.HlsPackagingPlan plan = planner.plan(
                    probed, 6, HlsLadderPlanner.Below720Policy.SOURCE_FALLBACK_NO_UPSCALE, HlsLadderPlanner.HdrPolicy.REJECT_EXPLICIT
            );
            assertThat(plan.videoTiers()).extracting(HlsLadderPlanner.VideoTierSpec::label)
                    .containsExactly("720p", "1080p", "2160p");

            Path out = work.resolve("hls_out");
            LocalFfmpegHlsPackager.PackagingExecutionResult exec = packager.packageToLocalDirectory(src, out, plan);
            HlsPackageVerifier.HlsLocalVerificationReport report = verifier.verify(out, plan);

            assertThat(report.playlistCount()).isEqualTo(5);
            assertThat(report.initFileCount()).isEqualTo(4);
            assertThat(report.videoSegmentCountsByTier().get("2160p")).isEqualTo(2);
            printMetrics("2160p-12s-6s", exec, report);
        } finally {
            deleteRecursively(work);
        }
    }

    @Test
    @DisplayName("Below-720p (854x480) fallback packages at native resolution without upscaling + 1 shared audio")
    void shouldPackageBelow720pSourceWithoutUpscaling() throws Exception {
        Path work = Files.createTempDirectory("kiemlai-hls2a-480p-");
        try {
            Path src = work.resolve("src_480p.mp4");
            generateSyntheticMp4(work, src, 854, 480, "24", 12, true, 2);

            MediaSourceProbe.ProbedSource probed = probe.probeSource(src);
            HlsLadderPlanner.HlsPackagingPlan plan = planner.plan(
                    probed, 6, HlsLadderPlanner.Below720Policy.SOURCE_FALLBACK_NO_UPSCALE, HlsLadderPlanner.HdrPolicy.REJECT_EXPLICIT
            );
            assertThat(plan.below720FallbackApplied()).isTrue();
            assertThat(plan.videoTiers()).hasSize(1);
            assertThat(plan.videoTiers().get(0).width()).isEqualTo(854);
            assertThat(plan.videoTiers().get(0).height()).isEqualTo(480);

            Path out = work.resolve("hls_out");
            LocalFfmpegHlsPackager.PackagingExecutionResult exec = packager.packageToLocalDirectory(src, out, plan);
            HlsPackageVerifier.HlsLocalVerificationReport report = verifier.verify(out, plan);

            assertThat(report.playlistCount()).isEqualTo(3);
            assertThat(report.initFileCount()).isEqualTo(2);
            printMetrics("480p-fallback-12s-6s", exec, report);
        } finally {
            deleteRecursively(work);
        }
    }

    @Test
    @DisplayName("No-audio 1080p source produces valid video-only fMP4 HLS package with zero audio renditions")
    void shouldPackageVideoOnlyNoAudioSource() throws Exception {
        Path work = Files.createTempDirectory("kiemlai-hls2a-noaudio-");
        try {
            Path src = work.resolve("src_noaudio.mp4");
            generateSyntheticMp4(work, src, 1920, 1080, "24", 12, false, 0);

            MediaSourceProbe.ProbedSource probed = probe.probeSource(src);
            assertThat(probed.hasAudio()).isFalse();

            HlsLadderPlanner.HlsPackagingPlan plan = planner.plan(
                    probed, 6, HlsLadderPlanner.Below720Policy.SOURCE_FALLBACK_NO_UPSCALE, HlsLadderPlanner.HdrPolicy.REJECT_EXPLICIT
            );
            assertThat(plan.includeAudio()).isFalse();

            Path out = work.resolve("hls_out");
            LocalFfmpegHlsPackager.PackagingExecutionResult exec = packager.packageToLocalDirectory(src, out, plan);
            HlsPackageVerifier.HlsLocalVerificationReport report = verifier.verify(out, plan);

            assertThat(report.playlistCount()).isEqualTo(3);
            assertThat(report.initFileCount()).isEqualTo(2);
            assertThat(report.audioSegmentCount()).isZero();
            assertThat(Files.exists(out.resolve("audio"))).isFalse();
            printMetrics("1080p-noaudio-12s-6s", exec, report);
        } finally {
            deleteRecursively(work);
        }
    }

    @Test
    @DisplayName("6-second vs 8-second segment duration comparison on identical 24s 1080p + stereo source")
    void shouldCompare6sAnd8sSegmentDurationsOn1080p24sSource() throws Exception {
        Path work = Files.createTempDirectory("kiemlai-hls2a-cmp-");
        try {
            Path src = work.resolve("src_1080p_24s.mp4");
            generateSyntheticMp4(work, src, 1920, 1080, "24", 24, true, 2);
            MediaSourceProbe.ProbedSource probed = probe.probeSource(src);

            HlsLadderPlanner.HlsPackagingPlan plan6 = planner.plan(
                    probed, 6, HlsLadderPlanner.Below720Policy.SOURCE_FALLBACK_NO_UPSCALE, HlsLadderPlanner.HdrPolicy.REJECT_EXPLICIT
            );
            LocalFfmpegHlsPackager.PackagingExecutionResult exec6 =
                    packager.packageToLocalDirectory(src, work.resolve("out_6s"), plan6);
            HlsPackageVerifier.HlsLocalVerificationReport rep6 = verifier.verify(work.resolve("out_6s"), plan6);

            HlsLadderPlanner.HlsPackagingPlan plan8 = planner.plan(
                    probed, 8, HlsLadderPlanner.Below720Policy.SOURCE_FALLBACK_NO_UPSCALE, HlsLadderPlanner.HdrPolicy.REJECT_EXPLICIT
            );
            LocalFfmpegHlsPackager.PackagingExecutionResult exec8 =
                    packager.packageToLocalDirectory(src, work.resolve("out_8s"), plan8);
            HlsPackageVerifier.HlsLocalVerificationReport rep8 = verifier.verify(work.resolve("out_8s"), plan8);

            assertThat(rep6.videoSegmentCountsByTier().get("1080p")).isEqualTo(4);
            assertThat(rep8.videoSegmentCountsByTier().get("1080p")).isEqualTo(3);
            assertThat(rep8.objectCount()).isLessThan(rep6.objectCount());

            printMetrics("cmp-1080p-24s-6s", exec6, rep6);
            printMetrics("cmp-1080p-24s-8s", exec8, rep8);
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

        BoundedProcessRunner.ProcessResult result =
                processRunner.execute(cmd, workDir, Duration.ofMinutes(2), 64 * 1024);
        if (result.exitCode() != 0) {
            throw new IOException("Failed generating synthetic test source: " + result.output());
        }
    }

    private static void printMetrics(
            String label,
            LocalFfmpegHlsPackager.PackagingExecutionResult exec,
            HlsPackageVerifier.HlsLocalVerificationReport rep
    ) {
        System.out.println(String.format(
                Locale.ROOT,
                "HLS2A_POC_METRIC [%s] elapsedMs=%d totalBytes=%d objectCount=%d playlists=%d inits=%d videoSegs=%s audioSegs=%d videoExtinf=%s audioSummary=%s sha256=%s",
                label,
                exec.elapsedMillis(),
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
