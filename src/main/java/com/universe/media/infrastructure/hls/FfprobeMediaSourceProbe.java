package com.universe.media.infrastructure.hls;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeoutException;

/**
 * Infrastructure adapter invoking {@code ffprobe} to inspect source MP4 metadata
 * and verify generated HLS media playlists.
 */
@Component
@ConditionalOnProperty(prefix = "media.hls.packaging", name = "enabled", havingValue = "true")
public class FfprobeMediaSourceProbe {

    public record HlsPlaylistProbe(
            int streamCount,
            String codecType,
            String codecName,
            int width,
            int height,
            int sampleRateHz,
            int channels,
            double durationSeconds,
            int packetCount
    ) {
    }

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final int MAX_JSON_BYTES = 512 * 1024;

    private final String ffprobeExecutable;
    private final BoundedSubprocessRunner runner;
    private final Duration timeout;

    @Autowired
    public FfprobeMediaSourceProbe(HlsPackagingProperties properties, BoundedSubprocessRunner runner) {
        this(
                Objects.requireNonNull(properties, "HlsPackagingProperties cannot be null.").ffprobeExecutable(),
                runner,
                properties.probeTimeout()
        );
    }

    public FfprobeMediaSourceProbe(String ffprobeExecutable, BoundedSubprocessRunner runner, Duration timeout) {
        if (ffprobeExecutable == null || ffprobeExecutable.isBlank()) {
            throw new IllegalArgumentException("ffprobeExecutable cannot be blank.");
        }
        this.ffprobeExecutable = ffprobeExecutable.trim();
        this.runner = Objects.requireNonNull(runner, "runner cannot be null.");
        if (timeout == null || timeout.isNegative() || timeout.isZero()) {
            throw new IllegalArgumentException("timeout must be strictly positive.");
        }
        this.timeout = timeout;
    }

    public HlsLadderPlanner.ProbedVideoSource probeSource(Path sourceFile)
            throws IOException, InterruptedException, TimeoutException {
        Objects.requireNonNull(sourceFile, "sourceFile cannot be null.");
        if (!Files.isRegularFile(sourceFile)) {
            throw new IOException("Source file does not exist or is not a regular file: " + sourceFile);
        }

        List<String> command = List.of(
                ffprobeExecutable,
                "-hide_banner",
                "-v", "error",
                "-show_format",
                "-show_streams",
                "-of", "json",
                sourceFile.toAbsolutePath().toString()
        );

        BoundedSubprocessRunner.ProcessResult result =
                runner.execute(command, sourceFile.toAbsolutePath().getParent(), timeout, MAX_JSON_BYTES);
        if (result.exitCode() != 0) {
            throw new IOException("ffprobe failed (exit=" + result.exitCode() + "): " + result.output());
        }

        JsonNode root = OBJECT_MAPPER.readTree(result.output());
        JsonNode streams = root.path("streams");
        if (!streams.isArray()) {
            throw new IOException("ffprobe JSON missing streams array.");
        }

        JsonNode videoStream = null;
        JsonNode primaryAudioStream = null;
        int audioCount = 0;

        for (JsonNode stream : streams) {
            String type = stream.path("codec_type").asText("");
            boolean attachedPic = stream.path("disposition").path("attached_pic").asInt(0) == 1;
            if ("video".equals(type) && !attachedPic && videoStream == null) {
                videoStream = stream;
            } else if ("audio".equals(type)) {
                audioCount++;
                if (primaryAudioStream == null) {
                    primaryAudioStream = stream;
                }
            }
        }

        if (videoStream == null) {
            throw new IOException("Source contains no decodable video stream: " + sourceFile);
        }

        int rawWidth = videoStream.path("width").asInt(0);
        int rawHeight = videoStream.path("height").asInt(0);
        if (rawWidth <= 0 || rawHeight <= 0) {
            throw new IOException("Invalid probed video dimensions: " + rawWidth + "x" + rawHeight);
        }

        int rotation = extractRotationDegrees(videoStream);
        String sar = videoStream.path("sample_aspect_ratio").asText("1:1");
        String dar = videoStream.path("display_aspect_ratio").asText("");

        int[] sarRatio = parseRatio(sar);
        double unrotatedW = rawWidth * ((double) sarRatio[0] / sarRatio[1]);
        double unrotatedH = rawHeight;

        int normalizedRot = ((rotation % 360) + 360) % 360;
        boolean swapAxes = normalizedRot == 90 || normalizedRot == 270;
        int displayW = roundEven((int) Math.round(swapAxes ? unrotatedH : unrotatedW));
        int displayH = roundEven((int) Math.round(swapAxes ? unrotatedW : unrotatedH));

        String avgFpsRaw = videoStream.path("avg_frame_rate").asText("0/0");
        String rFpsRaw = videoStream.path("r_frame_rate").asText("0/0");
        double effectiveFps = parsePositiveFps(avgFpsRaw);
        if (effectiveFps <= 0.0) {
            effectiveFps = parsePositiveFps(rFpsRaw);
        }
        if (effectiveFps <= 0.0) {
            effectiveFps = 24.0;
        }

        double durationSeconds = parseDouble(root.path("format").path("duration").asText(""), 0.0);
        if (durationSeconds <= 0.0) {
            durationSeconds = parseDouble(videoStream.path("duration").asText(""), 0.0);
        }

        int bitsPerRawSample = parseInt(videoStream.path("bits_per_raw_sample").asText(""), 8);

        boolean hasAudio = primaryAudioStream != null;
        String audioCodec = hasAudio ? primaryAudioStream.path("codec_name").asText("") : null;
        int audioChannels = hasAudio ? primaryAudioStream.path("channels").asInt(0) : 0;
        int audioSampleRate = hasAudio ? parseInt(primaryAudioStream.path("sample_rate").asText(""), 0) : 0;
        String audioLanguage = hasAudio ? primaryAudioStream.path("tags").path("language").asText("und") : null;

        return new HlsLadderPlanner.ProbedVideoSource(
                rawWidth,
                rawHeight,
                normalizedRot,
                sar,
                dar,
                Math.max(2, displayW),
                Math.max(2, displayH),
                avgFpsRaw,
                rFpsRaw,
                effectiveFps,
                durationSeconds,
                videoStream.path("codec_name").asText(""),
                videoStream.path("pix_fmt").asText(""),
                bitsPerRawSample,
                videoStream.path("color_space").asText(""),
                videoStream.path("color_transfer").asText(""),
                videoStream.path("color_primaries").asText(""),
                hasAudio,
                audioCount,
                audioCodec,
                audioChannels,
                audioSampleRate,
                audioLanguage
        );
    }

    public HlsPlaylistProbe probeHlsMediaPlaylist(Path playlistPath)
            throws IOException, InterruptedException, TimeoutException {
        Objects.requireNonNull(playlistPath, "playlistPath cannot be null.");
        if (!Files.isRegularFile(playlistPath)) {
            throw new IOException("Playlist file does not exist: " + playlistPath);
        }

        List<String> command = List.of(
                ffprobeExecutable,
                "-hide_banner",
                "-v", "error",
                "-allowed_extensions", "ALL",
                "-count_packets",
                "-show_format",
                "-show_streams",
                "-of", "json",
                playlistPath.toAbsolutePath().toString()
        );

        BoundedSubprocessRunner.ProcessResult result =
                runner.execute(command, playlistPath.toAbsolutePath().getParent(), timeout, MAX_JSON_BYTES);
        if (result.exitCode() != 0) {
            throw new IOException("ffprobe playlist verification failed (exit=" + result.exitCode() + "): " + result.output());
        }

        JsonNode root = OBJECT_MAPPER.readTree(result.output());
        JsonNode streams = root.path("streams");
        int streamCount = streams.isArray() ? streams.size() : 0;
        if (streamCount == 0) {
            throw new IOException("ffprobe found zero streams in HLS playlist: " + playlistPath);
        }
        JsonNode s0 = streams.path(0);
        int packetCount = parseInt(s0.path("nb_read_packets").asText(""), -1);
        double duration = parseDouble(root.path("format").path("duration").asText(""), 0.0);

        return new HlsPlaylistProbe(
                streamCount,
                s0.path("codec_type").asText(""),
                s0.path("codec_name").asText(""),
                s0.path("width").asInt(0),
                s0.path("height").asInt(0),
                parseInt(s0.path("sample_rate").asText(""), 0),
                s0.path("channels").asInt(0),
                duration,
                packetCount
        );
    }

    private static int extractRotationDegrees(JsonNode stream) {
        String tagRotate = stream.path("tags").path("rotate").asText("");
        if (!tagRotate.isBlank()) {
            try {
                return Integer.parseInt(tagRotate.trim());
            } catch (NumberFormatException ignored) {
            }
        }
        JsonNode sideData = stream.path("side_data_list");
        if (sideData.isArray()) {
            for (JsonNode entry : sideData) {
                if (entry.has("rotation")) {
                    return entry.path("rotation").asInt(0);
                }
            }
        }
        return 0;
    }

    private static int[] parseRatio(String ratio) {
        if (ratio == null || ratio.isBlank() || !ratio.contains(":")) {
            return new int[]{1, 1};
        }
        String[] parts = ratio.split(":");
        if (parts.length != 2) {
            return new int[]{1, 1};
        }
        int num = parseInt(parts[0].trim(), 0);
        int den = parseInt(parts[1].trim(), 0);
        if (num <= 0 || den <= 0) {
            return new int[]{1, 1};
        }
        return new int[]{num, den};
    }

    private static double parsePositiveFps(String fraction) {
        if (fraction == null || fraction.isBlank()) {
            return 0.0;
        }
        String[] parts = fraction.split("/");
        if (parts.length == 2) {
            double num = parseDouble(parts[0].trim(), 0.0);
            double den = parseDouble(parts[1].trim(), 0.0);
            return (num > 0.0 && den > 0.0) ? (num / den) : 0.0;
        }
        return parseDouble(fraction.trim(), 0.0);
    }

    private static int parseInt(String text, int fallback) {
        try {
            return Integer.parseInt(text.trim());
        } catch (Exception e) {
            return fallback;
        }
    }

    private static double parseDouble(String text, double fallback) {
        try {
            return Double.parseDouble(text.trim());
        } catch (Exception e) {
            return fallback;
        }
    }

    private static int roundEven(int value) {
        return value % 2 == 0 ? value : value - 1;
    }
}
