package com.universe.media.infrastructure.hls;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
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
 * Local verifier for generated fMP4 HLS VOD packages.
 *
 * <p>Validates manifest structure, VOD termination, single-shared-audio invariants,
 * strict package-root path containment, symlink escape rejection, ISO-BMFF fMP4 framing,
 * {@code ffprobe} codec/dimension checks, and per-variant segment boundary alignment.
 *
 * <p><strong>Boundary:</strong> Produces a {@link LocalHlsVerificationReport} only.
 * Never constructs a {@code VerifiedHlsPackageReceipt} or marks persisted packages READY.
 */
@Component
@ConditionalOnProperty(prefix = "media.hls.packaging", name = "enabled", havingValue = "true")
public class LocalHlsPackageVerifier {

    public static final class HlsPackageVerificationException extends RuntimeException {
        public HlsPackageVerificationException(String message) {
            super(message);
        }

        public HlsPackageVerificationException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private record VerifiedMediaPlaylistSummary(
            String playlistUri,
            int segmentCount,
            List<Double> extinfDurations,
            String initUri
    ) {
    }

    public record LocalHlsVerificationReport(
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

    private final FfprobeMediaSourceProbe probe;

    @Autowired
    public LocalHlsPackageVerifier(FfprobeMediaSourceProbe probe) {
        this.probe = Objects.requireNonNull(probe, "FfprobeMediaSourceProbe cannot be null.");
    }

    public LocalHlsVerificationReport verify(Path packageRoot, HlsLadderPlanner.HlsPackagingPlan plan)
            throws IOException, InterruptedException, TimeoutException {
        Objects.requireNonNull(packageRoot, "packageRoot cannot be null.");
        Objects.requireNonNull(plan, "plan cannot be null.");

        Path normalizedRoot = packageRoot.toAbsolutePath().normalize();
        if (!Files.isDirectory(normalizedRoot, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(normalizedRoot)) {
            throw new HlsPackageVerificationException("Invalid or symbolic-link package root directory: " + normalizedRoot);
        }
        Path realRoot = normalizedRoot.toRealPath();

        Path masterPath = safeResolveWithinRoot(realRoot, realRoot, "master.m3u8");
        if (!Files.isRegularFile(masterPath, LinkOption.NOFOLLOW_LINKS) || Files.size(masterPath) == 0) {
            throw new HlsPackageVerificationException("Missing or empty master.m3u8 at: " + masterPath);
        }

        byte[] masterBytes = Files.readAllBytes(masterPath);
        String masterSha256 = sha256Hex(masterBytes);
        List<String> masterLines = Files.readAllLines(masterPath, StandardCharsets.UTF_8);
        if (masterLines.isEmpty() || !"#EXTM3U".equals(masterLines.get(0).trim())) {
            throw new HlsPackageVerificationException("master.m3u8 does not start with #EXTM3U.");
        }

        int masterVersion = extractHlsVersion(masterLines);
        if (masterVersion < 7) {
            throw new HlsPackageVerificationException("master.m3u8 #EXT-X-VERSION must be >= 7 for fMP4, found: " + masterVersion);
        }

        Set<Path> verifiedObjects = new HashSet<>();
        verifiedObjects.add(masterPath.toRealPath());

        List<String> audioMediaLines = new ArrayList<>();
        List<String> streamInfLines = new ArrayList<>();
        List<String> streamUriLines = new ArrayList<>();

        for (int i = 0; i < masterLines.size(); i++) {
            String line = masterLines.get(i).trim();
            rejectUnsafeManifestDirective(line, "master.m3u8");
            if (line.startsWith("#EXT-X-MEDIA:")) {
                if (!line.contains("TYPE=AUDIO")) {
                    throw new HlsPackageVerificationException("Unexpected non-AUDIO #EXT-X-MEDIA in master.m3u8: " + line);
                }
                audioMediaLines.add(line);
            } else if (line.startsWith("#EXT-X-STREAM-INF:")) {
                String uriLine = findNextNonEmptyLine(masterLines, i + 1);
                if (uriLine == null || uriLine.startsWith("#")) {
                    throw new HlsPackageVerificationException("Missing variant URI after #EXT-X-STREAM-INF: " + line);
                }
                streamInfLines.add(line);
                streamUriLines.add(uriLine);
            }
        }

        if (streamInfLines.size() != plan.videoTiers().size()) {
            throw new HlsPackageVerificationException(
                    "Master variant count mismatch: expected " + plan.videoTiers().size() + ", found " + streamInfLines.size()
            );
        }

        String expectedGroupId = null;
        String audioPlaylistUri = null;
        if (plan.includeAudio()) {
            if (audioMediaLines.size() != 1) {
                throw new HlsPackageVerificationException(
                        "Expected exact 1 shared audio #EXT-X-MEDIA entry, found: " + audioMediaLines.size()
                );
            }
            String audioLine = audioMediaLines.get(0);
            expectedGroupId = requireAttr(GROUP_ID_ATTR_PATTERN, audioLine, "GROUP-ID");
            audioPlaylistUri = requireAttr(URI_ATTR_PATTERN, audioLine, "URI");
            Path audioPlaylistPath = safeResolveWithinRoot(realRoot, realRoot, audioPlaylistUri);
            if (!Files.isRegularFile(audioPlaylistPath, LinkOption.NOFOLLOW_LINKS)) {
                throw new HlsPackageVerificationException("Referenced audio playlist missing: " + audioPlaylistUri);
            }
            verifiedObjects.add(audioPlaylistPath);
        } else if (!audioMediaLines.isEmpty()) {
            throw new HlsPackageVerificationException("No-audio input must produce 0 audio #EXT-X-MEDIA entries.");
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
                throw new HlsPackageVerificationException("Missing RESOLUTION on #EXT-X-STREAM-INF: " + infLine);
            }
            String resKey = resMatcher.group(1) + "x" + resMatcher.group(2);
            HlsLadderPlanner.VideoTierSpec tier = tierByRes.remove(resKey);
            if (tier == null) {
                throw new HlsPackageVerificationException("Unexpected or duplicate variant resolution in master.m3u8: " + resKey);
            }

            Matcher bwMatcher = BANDWIDTH_ATTR_PATTERN.matcher(infLine);
            if (!bwMatcher.find() || Long.parseLong(bwMatcher.group(1)) <= 0L) {
                throw new HlsPackageVerificationException("Invalid or missing BANDWIDTH on variant " + resKey);
            }

            String codecs = requireAttr(CODECS_ATTR_PATTERN, infLine, "CODECS");
            if (!codecs.contains("avc1.")) {
                throw new HlsPackageVerificationException("Expected H.264 avc1 codec on variant " + resKey + ": " + codecs);
            }

            if (plan.includeAudio()) {
                String audioAttr = requireAttr(AUDIO_ATTR_PATTERN, infLine, "AUDIO");
                if (!expectedGroupId.equals(audioAttr)) {
                    throw new HlsPackageVerificationException(
                            "Variant " + resKey + " references audio group '" + audioAttr
                                    + "' instead of single shared group '" + expectedGroupId + "'"
                    );
                }
            } else if (AUDIO_ATTR_PATTERN.matcher(infLine).find() || codecs.contains("mp4a.")) {
                throw new HlsPackageVerificationException("Video-only variant must not declare audio group/codec: " + infLine);
            }

            Path playlistPath = safeResolveWithinRoot(realRoot, realRoot, uri);
            if (!seenMediaPlaylists.add(playlistPath)) {
                throw new HlsPackageVerificationException("Duplicate media playlist reference: " + uri);
            }
            verifiedObjects.add(playlistPath);

            VerifiedMediaPlaylistSummary summary = verifyMediaPlaylistAndFiles(
                    realRoot,
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
                    throw new HlsPackageVerificationException(
                            "Segment count mismatch across video renditions: " + tier.label()
                                    + " has " + summary.segmentCount() + " segments vs " + expectedSegmentCount
                    );
                }
                for (int s = 0; s < summary.extinfDurations().size(); s++) {
                    double diff = Math.abs(summary.extinfDurations().get(s) - referenceDurations.get(s));
                    if (diff > MAX_SEGMENT_DURATION_SKEW_SECONDS) {
                        throw new HlsPackageVerificationException(
                                "Unaligned segment boundary at index " + s + " for " + tier.label()
                                        + ": diff=" + diff + "s > " + MAX_SEGMENT_DURATION_SKEW_SECONDS + "s"
                        );
                    }
                }
            }
        }

        if (!tierByRes.isEmpty()) {
            throw new HlsPackageVerificationException("Missing planned video variants from master.m3u8: " + tierByRes.keySet());
        }

        int audioSegmentCount = 0;
        List<Double> audioDurations = List.of();
        String audioSummary = "NONE";
        if (plan.includeAudio()) {
            Path audioPlaylistPath = safeResolveWithinRoot(realRoot, realRoot, audioPlaylistUri);
            seenMediaPlaylists.add(audioPlaylistPath);
            VerifiedMediaPlaylistSummary audioSummaryRes = verifyMediaPlaylistAndFiles(
                    realRoot,
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

        long totalBytes = 0L;
        int actualFileCount = 0;
        try (Stream<Path> walk = Files.walk(realRoot)) {
            for (Path p : walk.toList()) {
                if (Files.isSymbolicLink(p)) {
                    throw new HlsPackageVerificationException("Symbolic link forbidden inside HLS package root: " + realRoot.relativize(p));
                }
                if (Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS)) {
                    Path realEntry = p.toRealPath();
                    if (!realEntry.startsWith(realRoot)) {
                        throw new HlsPackageVerificationException("Entry resolves outside package root: " + p);
                    }
                    if (!verifiedObjects.contains(realEntry)) {
                        throw new HlsPackageVerificationException("Unreferenced stray file in HLS package directory: " + realRoot.relativize(realEntry));
                    }
                    totalBytes += Files.size(realEntry);
                    actualFileCount++;
                }
            }
        }

        String renditionsJson = buildCompactRenditionsJson(plan, videoSegCounts, audioSegmentCount);
        return new LocalHlsVerificationReport(
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
            Path realRoot,
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
        if (!Files.isRegularFile(playlistPath, LinkOption.NOFOLLOW_LINKS) || Files.size(playlistPath) == 0) {
            throw new HlsPackageVerificationException("Missing or empty media playlist: " + playlistUri);
        }

        List<String> lines = Files.readAllLines(playlistPath, StandardCharsets.UTF_8);
        if (lines.isEmpty() || !"#EXTM3U".equals(lines.get(0).trim())) {
            throw new HlsPackageVerificationException("Media playlist missing #EXTM3U: " + playlistUri);
        }
        int version = extractHlsVersion(lines);
        if (version < 7) {
            throw new HlsPackageVerificationException("Media playlist #EXT-X-VERSION < 7 in " + playlistUri);
        }
        boolean isVod = lines.stream().map(String::trim).anyMatch("#EXT-X-PLAYLIST-TYPE:VOD"::equals);
        if (!isVod) {
            throw new HlsPackageVerificationException("Media playlist missing #EXT-X-PLAYLIST-TYPE:VOD: " + playlistUri);
        }

        Path playlistDir = playlistPath.getParent();
        String initUri = null;
        boolean seenEndlist = false;
        List<Double> extinfDurations = new ArrayList<>();
        List<Path> segmentPaths = new ArrayList<>();

        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i).trim();
            if (line.isEmpty()) {
                continue;
            }
            if (seenEndlist) {
                throw new HlsPackageVerificationException("Unexpected trailing content after #EXT-X-ENDLIST in " + playlistUri);
            }
            rejectUnsafeManifestDirective(line, playlistUri);

            if ("#EXT-X-ENDLIST".equals(line)) {
                seenEndlist = true;
            } else if (line.startsWith("#EXT-X-MAP:")) {
                if (initUri != null) {
                    throw new HlsPackageVerificationException("Multiple #EXT-X-MAP directives unexpected in playlist: " + playlistUri);
                }
                initUri = requireAttr(URI_ATTR_PATTERN, line, "URI");
                Path initPath = safeResolveWithinRoot(realRoot, playlistDir, initUri);
                verifyInitSegmentFile(initPath, initUri);
                if (!seenInitFiles.add(initPath)) {
                    throw new HlsPackageVerificationException("Shared/colliding init file across playlists: " + initUri);
                }
                verifiedObjects.add(initPath);
            } else if (line.startsWith("#EXTINF:")) {
                String rawDur = line.substring("#EXTINF:".length()).split(",")[0].trim();
                double dur;
                try {
                    dur = Double.parseDouble(rawDur);
                } catch (NumberFormatException e) {
                    throw new HlsPackageVerificationException("Invalid #EXTINF duration in " + playlistUri + ": " + line, e);
                }
                if (dur <= 0.0) {
                    throw new HlsPackageVerificationException("Non-positive #EXTINF duration in " + playlistUri + ": " + dur);
                }
                String segUri = findNextNonEmptyLine(lines, i + 1);
                if (segUri == null || segUri.startsWith("#")) {
                    throw new HlsPackageVerificationException("Missing segment URI after #EXTINF in " + playlistUri);
                }
                Path segPath = safeResolveWithinRoot(realRoot, playlistDir, segUri);
                verifyMediaSegmentFile(segPath, segUri);
                if (!verifiedObjects.add(segPath)) {
                    throw new HlsPackageVerificationException("Duplicate/colliding segment path referenced: " + segUri);
                }
                extinfDurations.add(dur);
                segmentPaths.add(segPath);
            }
        }

        if (!seenEndlist) {
            throw new HlsPackageVerificationException("Media playlist missing #EXT-X-ENDLIST: " + playlistUri);
        }
        if (initUri == null) {
            throw new HlsPackageVerificationException("Media playlist missing #EXT-X-MAP fMP4 init segment: " + playlistUri);
        }
        if (segmentPaths.isEmpty()) {
            throw new HlsPackageVerificationException("Media playlist has 0 segments: " + playlistUri);
        }

        FfprobeMediaSourceProbe.HlsPlaylistProbe probed = probe.probeHlsMediaPlaylist(playlistPath);
        if (probed.streamCount() != 1) {
            throw new HlsPackageVerificationException(
                    "Playlist " + playlistUri + " must contain exactly 1 stream, found " + probed.streamCount()
            );
        }
        if (probed.packetCount() <= 0 || probed.durationSeconds() <= 0.0) {
            throw new HlsPackageVerificationException("Playlist " + playlistUri + " failed packet/duration ffprobe check.");
        }
        if (isVideo) {
            if (!"video".equals(probed.codecType()) || !"h264".equals(probed.codecName())) {
                throw new HlsPackageVerificationException(
                        "Video playlist " + playlistUri + " probed unexpected stream: "
                                + probed.codecType() + "/" + probed.codecName()
                );
            }
            if (probed.width() != expectedWidth || probed.height() != expectedHeight) {
                throw new HlsPackageVerificationException(
                        "Video playlist " + playlistUri + " dimension mismatch: expected "
                                + expectedWidth + "x" + expectedHeight + ", got " + probed.width() + "x" + probed.height()
                );
            }
        } else {
            if (!"audio".equals(probed.codecType()) || !"aac".equals(probed.codecName())) {
                throw new HlsPackageVerificationException(
                        "Audio playlist " + playlistUri + " probed unexpected stream: "
                                + probed.codecType() + "/" + probed.codecName()
                );
            }
            if (probed.sampleRateHz() != expectedSampleRate || probed.channels() != expectedChannels) {
                throw new HlsPackageVerificationException(
                        "Audio playlist " + playlistUri + " metadata mismatch: "
                                + probed.sampleRateHz() + "Hz/" + probed.channels() + "ch"
                );
            }
        }

        return new VerifiedMediaPlaylistSummary(
                playlistUri,
                segmentPaths.size(),
                List.copyOf(extinfDurations),
                initUri
        );
    }

    static Path safeResolveWithinRoot(Path realPackageRoot, Path baseDir, String rawUri) throws IOException {
        if (rawUri == null || rawUri.isBlank()) {
            throw new HlsPackageVerificationException("Blank URI reference rejected.");
        }
        String trimmed = rawUri.trim();
        for (int i = 0; i < trimmed.length(); i++) {
            char c = trimmed.charAt(i);
            if (c <= 0x1F || c == 0x7F) {
                throw new HlsPackageVerificationException("Control character in URI rejected: " + trimmed);
            }
        }
        if (trimmed.contains("\\")
                || trimmed.startsWith("/")
                || trimmed.contains("://")
                || trimmed.contains(":")
                || trimmed.contains("?")
                || trimmed.contains("#")
                || trimmed.contains("..")) {
            throw new HlsPackageVerificationException("Unsafe/absolute/traversal URI escaping package root rejected: " + trimmed);
        }

        Path resolved = baseDir.resolve(trimmed).normalize();
        if (!resolved.startsWith(realPackageRoot)) {
            throw new HlsPackageVerificationException("Path traversal escaping package root rejected: " + trimmed);
        }

        // Verify no symbolic link occurs along any path component under package root
        Path rel = realPackageRoot.relativize(resolved);
        Path cursor = realPackageRoot;
        for (Path part : rel) {
            cursor = cursor.resolve(part);
            if (Files.isSymbolicLink(cursor)) {
                throw new HlsPackageVerificationException("Symbolic link path segment rejected: " + trimmed);
            }
        }

        if (Files.exists(resolved, LinkOption.NOFOLLOW_LINKS)) {
            Path realResolved = resolved.toRealPath();
            if (!realResolved.startsWith(realPackageRoot)) {
                throw new HlsPackageVerificationException("Resolved real path escapes package root: " + trimmed);
            }
            return realResolved;
        }
        return resolved;
    }

    private static void rejectUnsafeManifestDirective(String line, String playlistUri) {
        if (line.startsWith("#EXT-X-KEY:")
                || line.startsWith("#EXT-X-SESSION-KEY:")
                || line.startsWith("#EXT-X-BYTERANGE:")
                || line.startsWith("#EXT-X-PART:")
                || line.startsWith("#EXT-X-PRELOAD-HINT:")) {
            throw new HlsPackageVerificationException(
                    "Unsupported/untrusted HLS directive in local VOD package (" + playlistUri + "): " + line
            );
        }
    }

    private static void verifyInitSegmentFile(Path initPath, String uri) throws IOException {
        if (!Files.isRegularFile(initPath, LinkOption.NOFOLLOW_LINKS) || Files.size(initPath) < 16) {
            throw new HlsPackageVerificationException("Missing or truncated fMP4 init segment: " + uri);
        }
        List<String> topBoxes = readTopLevelBmffBoxes(initPath);
        if (!topBoxes.contains("ftyp") || !topBoxes.contains("moov")) {
            throw new HlsPackageVerificationException("Init segment " + uri + " missing required ftyp/moov fMP4 boxes: " + topBoxes);
        }
    }

    private static void verifyMediaSegmentFile(Path segPath, String uri) throws IOException {
        if (!Files.isRegularFile(segPath, LinkOption.NOFOLLOW_LINKS) || Files.size(segPath) < 16) {
            throw new HlsPackageVerificationException("Missing or truncated fMP4 media segment: " + uri);
        }
        List<String> topBoxes = readTopLevelBmffBoxes(segPath);
        if (!topBoxes.contains("moof") || !topBoxes.contains("mdat")) {
            throw new HlsPackageVerificationException("Media segment " + uri + " missing required moof/mdat fMP4 boxes: " + topBoxes);
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
                    throw new HlsPackageVerificationException("Truncated BMFF box header in: " + file.getFileName());
                }
                header.flip();
                long boxSize = Integer.toUnsignedLong(header.getInt());
                byte[] typeBytes = new byte[4];
                header.get(typeBytes);
                String type = new String(typeBytes, StandardCharsets.US_ASCII);
                if (boxSize < 8 || pos + boxSize > fileSize) {
                    throw new HlsPackageVerificationException(
                            "Corrupt or truncated BMFF box '" + type + "' (declared=" + boxSize
                                    + ", remaining=" + (fileSize - pos) + ") in: " + file.getFileName()
                    );
                }
                boxes.add(type);
                pos += boxSize;
            }
            if (pos != fileSize) {
                throw new HlsPackageVerificationException("Trailing unframed bytes in fMP4 file: " + file.getFileName());
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
                    throw new HlsPackageVerificationException("Malformed #EXT-X-VERSION: " + line, e);
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
            throw new HlsPackageVerificationException("Missing required attribute '" + attrName + "' in line: " + line);
        }
        return m.group(1);
    }

    private static String sha256Hex(byte[] bytes) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(bytes)).toLowerCase(Locale.ROOT);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable.", e);
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
