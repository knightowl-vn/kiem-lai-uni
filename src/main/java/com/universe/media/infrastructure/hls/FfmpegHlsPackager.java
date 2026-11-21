package com.universe.media.infrastructure.hls;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeoutException;

/**
 * Infrastructure adapter invoking local {@code ffmpeg} to package an authorized local source
 * into adaptive H.264/AAC fMP4 HLS VOD output with at most one shared audio rendition.
 */
@Component
@ConditionalOnProperty(prefix = "media.hls.packaging", name = "enabled", havingValue = "true")
public class FfmpegHlsPackager {

    public record PackagingExecution(
            Path outputRoot,
            HlsLadderPlanner.HlsPackagingPlan plan,
            List<String> command,
            long elapsedMillis,
            String ffmpegDiagnostics
    ) {
    }

    private final String ffmpegExecutable;
    private final BoundedSubprocessRunner runner;
    private final int ffmpegThreads;
    private final Duration encodeTimeout;
    private final String x264Preset;

    @Autowired
    public FfmpegHlsPackager(HlsPackagingProperties properties, BoundedSubprocessRunner runner) {
        this(
                Objects.requireNonNull(properties, "HlsPackagingProperties cannot be null.").ffmpegExecutable(),
                runner,
                properties.ffmpegThreads(),
                properties.encodeTimeout(),
                properties.x264Preset()
        );
    }

    public FfmpegHlsPackager(
            String ffmpegExecutable,
            BoundedSubprocessRunner runner,
            int ffmpegThreads,
            Duration encodeTimeout,
            String x264Preset
    ) {
        if (ffmpegExecutable == null || ffmpegExecutable.isBlank()) {
            throw new IllegalArgumentException("ffmpegExecutable cannot be blank.");
        }
        this.ffmpegExecutable = ffmpegExecutable.trim();
        this.runner = Objects.requireNonNull(runner, "runner cannot be null.");
        if (ffmpegThreads < 1 || ffmpegThreads > 32) {
            throw new IllegalArgumentException("ffmpegThreads must be in 1..32, got: " + ffmpegThreads);
        }
        this.ffmpegThreads = ffmpegThreads;
        if (encodeTimeout == null || encodeTimeout.isNegative() || encodeTimeout.isZero()) {
            throw new IllegalArgumentException("encodeTimeout must be strictly positive.");
        }
        this.encodeTimeout = encodeTimeout;
        if (x264Preset == null || x264Preset.isBlank()) {
            throw new IllegalArgumentException("x264Preset cannot be blank.");
        }
        this.x264Preset = x264Preset.trim();
    }

    public PackagingExecution packageToDirectory(
            Path sourceFile,
            Path outputRoot,
            HlsLadderPlanner.HlsPackagingPlan plan
    ) throws IOException, InterruptedException, TimeoutException {
        Objects.requireNonNull(sourceFile, "sourceFile cannot be null.");
        Objects.requireNonNull(outputRoot, "outputRoot cannot be null.");
        Objects.requireNonNull(plan, "plan cannot be null.");

        if (!Files.isRegularFile(sourceFile)) {
            throw new IOException("Source MP4 does not exist: " + sourceFile);
        }

        Files.createDirectories(outputRoot);
        for (HlsLadderPlanner.VideoTierSpec tier : plan.videoTiers()) {
            Files.createDirectories(outputRoot.resolve(tier.relativeDir()));
        }
        if (plan.includeAudio()) {
            Files.createDirectories(outputRoot.resolve(plan.audioRelativeDir()));
        }

        List<String> command = buildFfmpegCommand(sourceFile.toAbsolutePath(), plan);
        BoundedSubprocessRunner.ProcessResult result =
                runner.execute(command, outputRoot.toAbsolutePath(), encodeTimeout, 64 * 1024);
        if (result.exitCode() != 0) {
            throw new IOException("FFmpeg HLS packaging failed (exit=" + result.exitCode() + "): " + result.output());
        }

        return new PackagingExecution(
                outputRoot.toAbsolutePath(),
                plan,
                command,
                result.elapsedMillis(),
                result.output()
        );
    }

    public List<String> buildFfmpegCommand(Path absoluteSourceFile, HlsLadderPlanner.HlsPackagingPlan plan) {
        List<String> cmd = new ArrayList<>();
        cmd.add(ffmpegExecutable);
        cmd.add("-y");
        cmd.add("-hide_banner");
        cmd.add("-v");
        cmd.add("warning");
        cmd.add("-threads");
        cmd.add(Integer.toString(ffmpegThreads));
        cmd.add("-i");
        cmd.add(absoluteSourceFile.toString());

        List<HlsLadderPlanner.VideoTierSpec> tiers = plan.videoTiers();
        StringBuilder filterGraph = new StringBuilder();
        if (tiers.size() > 1) {
            filterGraph.append("[0:v]split=").append(tiers.size());
            for (int i = 0; i < tiers.size(); i++) {
                filterGraph.append("[vin").append(i).append("]");
            }
            filterGraph.append(";");
        }
        for (int i = 0; i < tiers.size(); i++) {
            HlsLadderPlanner.VideoTierSpec tier = tiers.get(i);
            String inPad = tiers.size() > 1 ? "[vin" + i + "]" : "[0:v]";
            if (i > 0) {
                filterGraph.append(";");
            }
            filterGraph.append(inPad)
                    .append("scale=w=").append(tier.width())
                    .append(":h=").append(tier.height())
                    .append(":flags=bicubic,setsar=1[vout").append(i).append("]");
        }

        cmd.add("-filter_complex");
        cmd.add(filterGraph.toString());

        for (int i = 0; i < tiers.size(); i++) {
            cmd.add("-map");
            cmd.add("[vout" + i + "]");
        }
        if (plan.includeAudio()) {
            cmd.add("-map");
            cmd.add("0:a:0");
        }

        cmd.add("-c:v");
        cmd.add("libx264");
        cmd.add("-preset");
        cmd.add(x264Preset);
        cmd.add("-profile:v");
        cmd.add("high");
        cmd.add("-pix_fmt:v");
        cmd.add("yuv420p");
        cmd.add("-sc_threshold:v");
        cmd.add("0");
        cmd.add("-force_key_frames:v");
        cmd.add("expr:gte(t,n_forced*" + plan.segmentDurationSeconds() + ")");

        for (int i = 0; i < tiers.size(); i++) {
            HlsLadderPlanner.VideoTierSpec tier = tiers.get(i);
            cmd.add("-crf:v:" + i);
            cmd.add(Integer.toString(tier.crf()));
            cmd.add("-maxrate:v:" + i);
            cmd.add(tier.maxrateKbps() + "k");
            cmd.add("-bufsize:v:" + i);
            cmd.add(tier.bufsizeKbps() + "k");
            cmd.add("-g:v:" + i);
            cmd.add(Integer.toString(plan.gopSizeFrames()));
            cmd.add("-keyint_min:v:" + i);
            cmd.add(Integer.toString(plan.gopSizeFrames()));
        }

        if (plan.includeAudio()) {
            cmd.add("-c:a:0");
            cmd.add("aac");
            cmd.add("-profile:a:0");
            cmd.add("aac_low");
            cmd.add("-b:a:0");
            cmd.add(plan.audioBitrateKbps() + "k");
            cmd.add("-ac");
            cmd.add(Integer.toString(plan.audioChannels()));
            cmd.add("-ar");
            cmd.add(Integer.toString(plan.audioSampleRateHz()));
        }

        StringBuilder streamMap = new StringBuilder();
        for (int i = 0; i < tiers.size(); i++) {
            if (i > 0) {
                streamMap.append(" ");
            }
            streamMap.append("v:").append(i);
            if (plan.includeAudio()) {
                streamMap.append(",agroup:").append(plan.audioGroupId());
            }
            streamMap.append(",name:").append(tiers.get(i).relativeDir());
        }
        if (plan.includeAudio()) {
            streamMap.append(" a:0,agroup:").append(plan.audioGroupId())
                    .append(",name:").append(plan.audioRelativeDir())
                    .append(",default:yes");
        }

        cmd.add("-f");
        cmd.add("hls");
        cmd.add("-hls_time");
        cmd.add(Integer.toString(plan.segmentDurationSeconds()));
        cmd.add("-hls_playlist_type");
        cmd.add("vod");
        cmd.add("-hls_segment_type");
        cmd.add("fmp4");
        cmd.add("-hls_flags");
        cmd.add("independent_segments");
        cmd.add("-hls_fmp4_init_filename");
        cmd.add("init.mp4");
        cmd.add("-hls_segment_filename");
        cmd.add("%v/seg_%05d.m4s");
        cmd.add("-master_pl_name");
        cmd.add("master.m3u8");
        cmd.add("-var_stream_map");
        cmd.add(streamMap.toString());
        cmd.add("%v/playlist.m3u8");

        return List.copyOf(cmd);
    }
}
