package com.universe.media.infrastructure.hls.poc;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeoutException;

/**
 * Local-only FFmpeg fMP4 HLS packager generating adaptive video-only tiers plus
 * at most ONE shared AAC-LC audio rendition.
 */
final class LocalFfmpegHlsPackager {

    record PackagingExecutionResult(
            Path outputRoot,
            HlsLadderPlanner.HlsPackagingPlan plan,
            List<String> command,
            long elapsedMillis,
            String ffmpegDiagnostics
    ) {
    }

    private final String ffmpegExecutable;
    private final BoundedProcessRunner runner;
    private final int maxThreads;
    private final Duration timeout;
    private final String x264Preset;

    LocalFfmpegHlsPackager() {
        this("ffmpeg", new BoundedProcessRunner(), Math.min(4, Math.max(1, Runtime.getRuntime().availableProcessors() / 2)),
                Duration.ofMinutes(3), "veryfast");
    }

    LocalFfmpegHlsPackager(
            String ffmpegExecutable,
            BoundedProcessRunner runner,
            int maxThreads,
            Duration timeout,
            String x264Preset
    ) {
        this.ffmpegExecutable = Objects.requireNonNull(ffmpegExecutable, "ffmpegExecutable cannot be null").trim();
        this.runner = Objects.requireNonNull(runner, "runner cannot be null");
        if (maxThreads < 1) {
            throw new IllegalArgumentException("maxThreads must be >= 1");
        }
        this.maxThreads = maxThreads;
        this.timeout = Objects.requireNonNull(timeout, "timeout cannot be null");
        this.x264Preset = Objects.requireNonNull(x264Preset, "x264Preset cannot be null").trim();
    }

    PackagingExecutionResult packageToLocalDirectory(
            Path sourceFile,
            Path outputRoot,
            HlsLadderPlanner.HlsPackagingPlan plan
    ) throws IOException, InterruptedException, TimeoutException {
        Objects.requireNonNull(sourceFile, "sourceFile cannot be null");
        Objects.requireNonNull(outputRoot, "outputRoot cannot be null");
        Objects.requireNonNull(plan, "plan cannot be null");

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
        BoundedProcessRunner.ProcessResult result =
                runner.execute(command, outputRoot.toAbsolutePath(), timeout, 64 * 1024);
        if (result.exitCode() != 0) {
            throw new IOException("FFmpeg HLS packaging failed (exit=" + result.exitCode() + "): " + result.output());
        }

        return new PackagingExecutionResult(
                outputRoot.toAbsolutePath(),
                plan,
                command,
                result.elapsedMillis(),
                result.output()
        );
    }

    List<String> buildFfmpegCommand(Path absoluteSourceFile, HlsLadderPlanner.HlsPackagingPlan plan) {
        List<String> cmd = new ArrayList<>();
        cmd.add(ffmpegExecutable);
        cmd.add("-y");
        cmd.add("-hide_banner");
        cmd.add("-v");
        cmd.add("warning");
        cmd.add("-threads");
        cmd.add(Integer.toString(maxThreads));
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
