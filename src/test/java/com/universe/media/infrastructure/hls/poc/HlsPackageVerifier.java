package com.universe.media.infrastructure.hls.poc;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeoutException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Local verification harness for PoC HLS fMP4 packages.
 * Validates manifest syntax, path containment, single-shared-audio invariants,
 * ISO-BMFF fMP4 init/segment boxes, segment boundary alignment, and {@code ffprobe} stream verification.
 *
 * <p>Produces a local report ONLY; never emits {@code VerifiedHlsPackageReceipt} or marks DB records READY.
 */
final class HlsPackageVerifier {

    static final class HlsVerificationException extends RuntimeException {
        HlsVerificationException(String message) {
            super(message);
        }

        HlsVerificationException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    record VerifiedMediaPlaylistSummary(
            String playlistUri,
            int segmentCount,
            List<Double> extinfDurations,
            String initUri,
            double probedDurationSeconds,
            int probedWidth,
            int probedHeight
    ) {
    }

    record HlsLocalVerificationReport(
            long totalSizeBytes,
            int objectCount,
            int playlistCount,
            int initFileCount,
            Map<String, Integer> videoSegmentCountsByTier,
            int audioSegmentCount,
            String masterManifestSha256,
            Map<String, List<Double>> videoExtinfDurationsByTier,
            List<Double> audioExtinfDurations,
            String audioSummary,
            String renditionsSummaryJson
    ) {
    }

    private static final Pattern URI_ATTR_PATTERN = Pattern.compile("URI=\"([^\"]+)\"");
    private static final Pattern GROUP_ID_ATTR_PATTERN = Pattern.compile("GROUP-ID=\"([^\"]+)\"");
    private static final Pattern AUDIO_ATTR_PATTERN = Pattern.compile("AUDIO=\"([^\"]+)\"");
    private static final Pattern RESOLUTION_ATTR_PATTERN = Pattern.compile("RESOLUTION=(\\d+)x(\\d+)");
    private static final Pattern BANDWIDTH_ATTR_PATTERN = Pattern.compile("BANDWIDTH=(\\d+)");
    private static final Pattern CODECS_ATTR_PATTERN = Pattern.compile("CODECS=\"([^\"]+)\"");
    private static final double MAX_SEGMENT_DURATION_SKEW_SECONDS = 0.25;

    private final MediaSourceProbe probe;

    HlsPackageVerifier() {
        this(new MediaSourceProbe());
    }

    HlsPackageVerifier(MediaSourceProbe probe) {
        this.probe = Objects.requireNonNull(probe, "probe cannot be null");
    }

    HlsLocalVerificationReport verify(Path packageRoot, HlsLadderPlanner.HlsPackagingPlan plan)
            throws IOException, InterruptedException, TimeoutException {
        Objects.requireNonNull(packageRoot, "packageRoot cannot be null");
        Objects.requireNonNull(plan, "plan cannot be null");

        Path normalizedRoot = packageRoot.toAbsolutePath().normalize();
        if (!Files.isDirectory(normalizedRoot)) {
            throw new HlsVerificationException("Package root directory does not exist: " + normalizedRoot);
        }

        Path masterPath = normalizedRoot.resolve("master.m3u8");
        if (!Files.isRegularFile(masterPath) || Files.size(masterPath) == 0) {
            throw new HlsVerificationException("Missing or empty master.m3u8 at: " + masterPath);
        }

        byte[] masterBytes = Files.readAllBytes(masterPath);
        String masterSha256 = sha256Hex(masterBytes);
        List<String> masterLines = Files.readAllLines(masterPath, StandardCharsets.UTF_8);
        if (masterLines.isEmpty() || !"#EXTM3U".equals(masterLines.get(0).trim())) {
            throw new HlsVerificationException("master.m3u8 does not start with #EXTM3U");
        }

        int masterVersion = extractHlsVersion(masterLines);
        if (masterVersion < 7) {
            throw new HlsVerificationException("master.m3u8 #EXT-X-VERSION must be >= 7 for fMP4, found: " + masterVersion);
        }

        Set<Path> verifiedObjects = new HashSet<>();
        verifiedObjects.add(masterPath.normalize());

        // Inspect audio groups & video variants
        List<String> audioMediaLines = new ArrayList<>();
        List<String> streamInfLines = new ArrayList<>();
        List<String> streamUriLines = new ArrayList<>();

        for (int i = 0; i < masterLines.size(); i++) {
            String line = masterLines.get(i).trim();
            if (line.startsWith("#EXT-X-MEDIA:")) {
                if (!line.contains("TYPE=AUDIO")) {
                    throw new HlsVerificationException("Unexpected non-AUDIO #EXT-X-MEDIA in PoC master: " + line);
                }
                audioMediaLines.add(line);
            } else if (line.startsWith("#EXT-X-STREAM-INF:")) {
                String uriLine = findNextNonEmptyLine(masterLines, i + 1);
                if (uriLine == null || uriLine.startsWith("#")) {
                    throw new HlsVerificationException("Missing variant URI after #EXT-X-STREAM-INF: " + line);
                }
                streamInfLines.add(line);
                streamUriLines.add(uriLine);
            }
        }

        if (streamInfLines.size() != plan.videoTiers().size()) {
            throw new HlsVerificationException(
                    "Master variant count mismatch: expected " + plan.videoTiers().size() + ", found " + streamInfLines.size()
            );
        }

        // Enforce single shared audio group vs no-audio
        String expectedGroupId = null;
        String audioPlaylistUri = null;
        if (plan.includeAudio()) {
            if (audioMediaLines.size() != 1) {
                throw new HlsVerificationException(
                        "Expected exact 1 shared audio #EXT-X-MEDIA entry, found: " + audioMediaLines.size()
                );
            }
            String audioLine = audioMediaLines.get(0);
            expectedGroupId = requireAttr(GROUP_ID_ATTR_PATTERN, audioLine, "GROUP-ID");
            audioPlaylistUri = requireAttr(URI_ATTR_PATTERN, audioLine, "URI");
            Path audioPlaylistPath = safeResolveWithinRoot(normalizedRoot, normalizedRoot, audioPlaylistUri);
            if (!Files.isRegularFile(audioPlaylistPath)) {
                throw new HlsVerificationException("Referenced audio playlist missing: " + audioPlaylistUri);
            }
            verifiedObjects.add(audioPlaylistPath);
        } else {
            if (!audioMediaLines.isEmpty()) {
                throw new HlsVerificationException("No-audio input must produce 0 audio #EXT-X-MEDIA entries");
            }
        }

        Map<String, HlsLadderPlanner.VideoTierSpec> tierByRes = new HashMap<>();
        for (HlsLadderPlanner.VideoTierSpec tier : plan.videoTiers()) {
            tierByRes.put(tier.width() + "x" + tier.height(), tier);
        }

        Map<String, Integer> videoSegCounts = new LinkedHashMap<>();
        Map<String, List<Double>> videoExtinfByTier = new LinkedHashMap<>();
        Set<Path> seenInitFiles = new HashSet<>();
        Set<Path> seenMediaPlaylists = new HashSet<>();

        int expectedSegmentCount = -1;
        List<Double> referenceDurations = null;

        for (int i = 0; i < streamInfLines.size(); i++) {
            String infLine = streamInfLines.get(i);
            String uri = streamUriLines.get(i);

            Matcher resMatcher = RESOLUTION_ATTR_PATTERN.matcher(infLine);
            if (!resMatcher.find()) {
                throw new HlsVerificationException("Missing RESOLUTION on #EXT-X-STREAM-INF: " + infLine);
            }
            String resKey = resMatcher.group(1) + "x" + resMatcher.group(2);
            HlsLadderPlanner.VideoTierSpec tier = tierByRes.remove(resKey);
            if (tier == null) {
                throw new HlsVerificationException("Unexpected or duplicate variant resolution in master.m3u8: " + resKey);
            }

            Matcher bwMatcher = BANDWIDTH_ATTR_PATTERN.matcher(infLine);
            if (!bwMatcher.find() || Long.parseLong(bwMatcher.group(1)) <= 0L) {
                throw new HlsVerificationException("Invalid or missing BANDWIDTH on variant " + resKey);
            }

            String codecs = requireAttr(CODECS_ATTR_PATTERN, infLine, "CODECS");
            if (!codecs.contains("avc1.")) {
                throw new HlsVerificationException("Expected H.264 avc1 codec on variant " + resKey + ": " + codecs);
            }

            if (plan.includeAudio()) {
                String audioAttr = requireAttr(AUDIO_ATTR_PATTERN, infLine, "AUDIO");
                if (!expectedGroupId.equals(audioAttr)) {
                    throw new HlsVerificationException(
                            "Variant " + resKey + " references audio group '" + audioAttr
                                    + "' instead of single shared group '" + expectedGroupId + "'"
                    );
                }
            } else {
                if (AUDIO_ATTR_PATTERN.matcher(infLine).find() || codecs.contains("mp4a.")) {
                    throw new HlsVerificationException("Video-only variant must not declare audio group/codec: " + infLine);
                }
            }

            Path playlistPath = safeResolveWithinRoot(normalizedRoot, normalizedRoot, uri);
            if (!seenMediaPlaylists.add(playlistPath)) {
                throw new HlsVerificationException("Duplicate media playlist reference: " + uri);
            }
            verifiedObjects.add(playlistPath);

            VerifiedMediaPlaylistSummary summary = verifyMediaPlaylistAndFiles(
                    normalizedRoot,
                    playlistPath,
                    uri,
                    true,
                    tier.width(),
                    tier.height(),
                    0,
                    0,
                    verifiedObjects,
                    seenInitFiles
            );

            videoSegCounts.put(tier.label(), summary.segmentCount());
            videoExtinfByTier.put(tier.label(), summary.extinfDurations());

            if (expectedSegmentCount < 0) {
                expectedSegmentCount = summary.segmentCount();
                referenceDurations = summary.extinfDurations();
            } else {
                if (summary.segmentCount() != expectedSegmentCount) {
                    throw new HlsVerificationException(
                            "Segment count mismatch across video renditions: " + tier.label()
                                    + " has " + summary.segmentCount() + " segments vs " + expectedSegmentCount
                    );
                }
                for (int s = 0; s < summary.extinfDurations().size(); s++) {
                    double diff = Math.abs(summary.extinfDurations().get(s) - referenceDurations.get(s));
                    if (diff > MAX_SEGMENT_DURATION_SKEW_SECONDS) {
                        throw new HlsVerificationException(
                                "Unaligned segment boundary at index " + s + " for " + tier.label()
                                        + ": diff=" + diff + "s > " + MAX_SEGMENT_DURATION_SKEW_SECONDS + "s"
                        );
                    }
                }
            }
        }

        if (!tierByRes.isEmpty()) {
            throw new HlsVerificationException("Missing planned video variants from master.m3u8: " + tierByRes.keySet());
        }

        int audioSegmentCount = 0;
        List<Double> audioDurations = List.of();
        String audioSummary = "NONE";
        if (plan.includeAudio()) {
            Path audioPlaylistPath = safeResolveWithinRoot(normalizedRoot, normalizedRoot, audioPlaylistUri);
            seenMediaPlaylists.add(audioPlaylistPath);
            VerifiedMediaPlaylistSummary audioSummaryRes = verifyMediaPlaylistAndFiles(
                    normalizedRoot,
                    audioPlaylistPath,
                    audioPlaylistUri,
                    false,
                    0,
                    0,
                    plan.audioSampleRateHz(),
                    plan.audioChannels(),
                    verifiedObjects,
                    seenInitFiles
            );
            audioSegmentCount = audioSummaryRes.segmentCount();
            audioDurations = audioSummaryRes.extinfDurations();
            audioSummary = "aac-lc/" + plan.audioSampleRateHz() + "Hz/" + plan.audioChannels() + "ch/segs=" + audioSegmentCount;
        }

        // Verify disk files match referenced objects cleanly (no hidden unreferenced media artifacts)
        long totalBytes = 0L;
        int actualFileCount = 0;
        try (Stream<Path> walk = Files.walk(normalizedRoot)) {
            for (Path p : walk.filter(Files::isRegularFile).toList()) {
                Path norm = p.normalize();
                if (!verifiedObjects.contains(norm)) {
                    throw new HlsVerificationException("Unreferenced stray file in HLS package directory: " + normalizedRoot.relativize(norm));
                }
                totalBytes += Files.size(norm);
                actualFileCount++;
            }
        }

        String renditionsJson = buildCompactRenditionsJson(plan, videoSegCounts, audioSegmentCount);
        return new HlsLocalVerificationReport(
                totalBytes,
                actualFileCount,
                seenMediaPlaylists.size() + 1,
                seenInitFiles.size(),
                videoSegCounts,
                audioSegmentCount,
                masterSha256,
                videoExtinfByTier,
                audioDurations,
                audioSummary,
                renditionsJson
        );
    }

    private VerifiedMediaPlaylistSummary verifyMediaPlaylistAndFiles(
            Path root,
            Path playlistPath,
            String playlistUri,
            boolean isVideo,
            int expectedWidth,
            int expectedHeight,
            int expectedSampleRate,
            int expectedChannels,
            Set<Path> verifiedObjects,
            Set<Path> seenInitFiles
    ) throws IOException, InterruptedException, TimeoutException {
        if (!Files.isRegularFile(playlistPath) || Files.size(playlistPath) == 0) {
            throw new HlsVerificationException("Missing or empty media playlist: " + playlistUri);
        }

        List<String> lines = Files.readAllLines(playlistPath, StandardCharsets.UTF_8);
        if (lines.isEmpty() || !"#EXTM3U".equals(lines.get(0).trim())) {
            throw new HlsVerificationException("Media playlist missing #EXTM3U: " + playlistUri);
        }
        int version = extractHlsVersion(lines);
        if (version < 7) {
            throw new HlsVerificationException("Media playlist #EXT-X-VERSION < 7 in " + playlistUri);
        }
        boolean isVod = lines.stream().map(String::trim).anyMatch("#EXT-X-PLAYLIST-TYPE:VOD"::equals);
        if (!isVod) {
            throw new HlsVerificationException("Media playlist missing #EXT-X-PLAYLIST-TYPE:VOD: " + playlistUri);
        }
        boolean hasEndlist = lines.stream().map(String::trim).anyMatch("#EXT-X-ENDLIST"::equals);
        if (!hasEndlist) {
            throw new HlsVerificationException("Media playlist missing #EXT-X-ENDLIST: " + playlistUri);
        }

        Path playlistDir = playlistPath.getParent();
        String initUri = null;
        List<Double> extinfDurations = new ArrayList<>();
        List<Path> segmentPaths = new ArrayList<>();

        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i).trim();
            if (line.startsWith("#EXT-X-MAP:")) {
                if (initUri != null) {
                    throw new HlsVerificationException("Multiple #EXT-X-MAP directives unexpected in PoC playlist: " + playlistUri);
                }
                initUri = requireAttr(URI_ATTR_PATTERN, line, "URI");
                Path initPath = safeResolveWithinRoot(root, playlistDir, initUri);
                verifyInitSegmentFile(initPath, initUri);
                if (!seenInitFiles.add(initPath)) {
                    throw new HlsVerificationException("Shared/colliding init file across playlists: " + initUri);
                }
                verifiedObjects.add(initPath);
            } else if (line.startsWith("#EXTINF:")) {
                String rawDur = line.substring("#EXTINF:".length()).split(",")[0].trim();
                double dur;
                try {
                    dur = Double.parseDouble(rawDur);
                } catch (NumberFormatException e) {
                    throw new HlsVerificationException("Invalid #EXTINF duration in " + playlistUri + ": " + line, e);
                }
                if (dur <= 0.0) {
                    throw new HlsVerificationException("Non-positive #EXTINF duration in " + playlistUri + ": " + dur);
                }
                String segUri = findNextNonEmptyLine(lines, i + 1);
                if (segUri == null || segUri.startsWith("#")) {
                    throw new HlsVerificationException("Missing segment URI after #EXTINF in " + playlistUri);
                }
                Path segPath = safeResolveWithinRoot(root, playlistDir, segUri);
                verifyMediaSegmentFile(segPath, segUri);
                if (!verifiedObjects.add(segPath)) {
                    throw new HlsVerificationException("Duplicate/colliding segment path referenced: " + segUri);
                }
                extinfDurations.add(dur);
                segmentPaths.add(segPath);
            }
        }

        if (initUri == null) {
            throw new HlsVerificationException("Media playlist missing #EXT-X-MAP fMP4 init segment: " + playlistUri);
        }
        if (segmentPaths.isEmpty()) {
            throw new HlsVerificationException("Media playlist has 0 segments: " + playlistUri);
        }

        // Actual ffprobe verification reading all segments through init+segments
        MediaSourceProbe.HlsPlaylistProbe probed = probe.probeHlsMediaPlaylist(playlistPath);
        if (probed.streamCount() != 1) {
            throw new HlsVerificationException(
                    "Playlist " + playlistUri + " must contain exactly 1 stream, found " + probed.streamCount()
            );
        }
        if (probed.packetCount() <= 0 || probed.durationSeconds() <= 0.0) {
            throw new HlsVerificationException("Playlist " + playlistUri + " failed packet/duration ffprobe check");
        }
        if (isVideo) {
            if (!"video".equals(probed.codecType()) || !"h264".equals(probed.codecName())) {
                throw new HlsVerificationException(
                        "Video playlist " + playlistUri + " probed unexpected stream: "
                                + probed.codecType() + "/" + probed.codecName()
                );
            }
            if (probed.width() != expectedWidth || probed.height() != expectedHeight) {
                throw new HlsVerificationException(
                        "Video playlist " + playlistUri + " dimension mismatch: expected "
                                + expectedWidth + "x" + expectedHeight + ", got " + probed.width() + "x" + probed.height()
                );
            }
        } else {
            if (!"audio".equals(probed.codecType()) || !"aac".equals(probed.codecName())) {
                throw new HlsVerificationException(
                        "Audio playlist " + playlistUri + " probed unexpected stream: "
                                + probed.codecType() + "/" + probed.codecName()
                );
            }
            if (probed.sampleRateHz() != expectedSampleRate || probed.channels() != expectedChannels) {
                throw new HlsVerificationException(
                        "Audio playlist " + playlistUri + " metadata mismatch: "
                                + probed.sampleRateHz() + "Hz/" + probed.channels() + "ch"
                );
            }
        }

        return new VerifiedMediaPlaylistSummary(
                playlistUri,
                segmentPaths.size(),
                List.copyOf(extinfDurations),
                initUri,
                probed.durationSeconds(),
                probed.width(),
                probed.height()
        );
    }

    static Path safeResolveWithinRoot(Path packageRoot, Path baseDir, String rawUri) {
        if (rawUri == null || rawUri.isBlank()) {
            throw new HlsVerificationException("Blank URI reference rejected");
        }
        String trimmed = rawUri.trim();
        if (trimmed.contains("\\") || trimmed.startsWith("/") || trimmed.contains("://") || trimmed.contains(":")
                || trimmed.contains("..")) {
            throw new HlsVerificationException("Unsafe/absolute/traversal URI escaping package root rejected: " + trimmed);
        }
        Path resolved = baseDir.resolve(trimmed).normalize();
        if (!resolved.startsWith(packageRoot)) {
            throw new HlsVerificationException("Path traversal escaping package root rejected: " + trimmed);
        }
        return resolved;
    }

    private static void verifyInitSegmentFile(Path initPath, String uri) throws IOException {
        if (!Files.isRegularFile(initPath) || Files.size(initPath) < 16) {
            throw new HlsVerificationException("Missing or truncated fMP4 init segment: " + uri);
        }
        List<String> topBoxes = readTopLevelBmffBoxes(initPath);
        if (!topBoxes.contains("ftyp") || !topBoxes.contains("moov")) {
            throw new HlsVerificationException("Init segment " + uri + " missing required ftyp/moov fMP4 boxes: " + topBoxes);
        }
    }

    private static void verifyMediaSegmentFile(Path segPath, String uri) throws IOException {
        if (!Files.isRegularFile(segPath) || Files.size(segPath) < 16) {
            throw new HlsVerificationException("Missing or truncated fMP4 media segment: " + uri);
        }
        List<String> topBoxes = readTopLevelBmffBoxes(segPath);
        if (!topBoxes.contains("moof") || !topBoxes.contains("mdat")) {
            throw new HlsVerificationException("Media segment " + uri + " missing required moof/mdat fMP4 boxes: " + topBoxes);
        }
    }

    private static List<String> readTopLevelBmffBoxes(Path file) throws IOException {
        List<String> boxes = new ArrayList<>();
        ByteBuffer header = ByteBuffer.allocate(8);
        try (SeekableByteChannel ch = Files.newByteChannel(file)) {
            long fileSize = ch.size();
            long pos = 0L;
            while (pos + 8 <= fileSize) {
                header.clear();
                ch.position(pos);
                int read = ch.read(header);
                if (read < 8) {
                    throw new HlsVerificationException("Truncated BMFF box header in: " + file.getFileName());
                }
                header.flip();
                long boxSize = Integer.toUnsignedLong(header.getInt());
                byte[] typeBytes = new byte[4];
                header.get(typeBytes);
                String type = new String(typeBytes, StandardCharsets.US_ASCII);
                if (boxSize < 8 || pos + boxSize > fileSize) {
                    throw new HlsVerificationException(
                            "Corrupt or truncated BMFF box '" + type + "' (declared=" + boxSize
                                    + ", remaining=" + (fileSize - pos) + ") in: " + file.getFileName()
                    );
                }
                boxes.add(type);
                pos += boxSize;
            }
            if (pos != fileSize) {
                throw new HlsVerificationException("Trailing unframed bytes in fMP4 file: " + file.getFileName());
            }
        }
        return boxes;
    }

    private static int extractHlsVersion(List<String> lines) {
        for (String raw : lines) {
            String line = raw.trim();
            if (line.startsWith("#EXT-X-VERSION:")) {
                try {
                    return Integer.parseInt(line.substring("#EXT-X-VERSION:".length()).trim());
                } catch (NumberFormatException e) {
                    throw new HlsVerificationException("Malformed #EXT-X-VERSION: " + line, e);
                }
            }
        }
        return 0;
    }

    private static String findNextNonEmptyLine(List<String> lines, int startIdx) {
        for (int i = startIdx; i < lines.size(); i++) {
            String trimmed = lines.get(i).trim();
            if (!trimmed.isEmpty()) {
                return trimmed;
            }
        }
        return null;
    }

    private static String requireAttr(Pattern pattern, String line, String attrName) {
        Matcher m = pattern.matcher(line);
        if (!m.find() || m.group(1).isBlank()) {
            throw new HlsVerificationException("Missing required attribute '" + attrName + "' in line: " + line);
        }
        return m.group(1);
    }

    private static String sha256Hex(byte[] bytes) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(bytes)).toLowerCase(Locale.ROOT);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static String buildCompactRenditionsJson(
            HlsLadderPlanner.HlsPackagingPlan plan,
            Map<String, Integer> videoSegCounts,
            int audioSegmentCount
    ) {
        StringBuilder sb = new StringBuilder("{\"video\":[");
        List<HlsLadderPlanner.VideoTierSpec> tiers = plan.videoTiers();
        for (int i = 0; i < tiers.size(); i++) {
            HlsLadderPlanner.VideoTierSpec t = tiers.get(i);
            if (i > 0) {
                sb.append(",");
            }
            sb.append("{\"label\":\"").append(t.label())
                    .append("\",\"w\":").append(t.width())
                    .append(",\"h\":").append(t.height())
                    .append(",\"segs\":").append(videoSegCounts.getOrDefault(t.label(), 0))
                    .append("}");
        }
        sb.append("],\"audio\":");
        if (plan.includeAudio()) {
            sb.append("{\"codec\":\"aac-lc\",\"kbps\":").append(plan.audioBitrateKbps())
                    .append(",\"ch\":").append(plan.audioChannels())
                    .append(",\"hz\":").append(plan.audioSampleRateHz())
                    .append(",\"segs\":").append(audioSegmentCount)
                    .append("}");
        } else {
            sb.append("null");
        }
        sb.append("}");
        return sb.toString();
    }
}
