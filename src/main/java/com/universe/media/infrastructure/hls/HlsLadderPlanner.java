package com.universe.media.infrastructure.hls;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Pure framework-independent HLS packaging ladder planner.
 * Enforces:
 * <ul>
 *     <li>Named tiers only: 720p (1280x720), 1080p (1920x1080), 2160p (3840x2160) — no 480p by default.</li>
 *     <li>Zero upscaling with display aspect ratio and orientation preservation.</li>
 *     <li>At most ONE shared AAC-LC audio rendition when source audio exists (video-only otherwise).</li>
 *     <li>Explicit below-720p and HDR/10-bit policies.</li>
 * </ul>
 */
public final class HlsLadderPlanner {

    public enum Below720Policy {
        SOURCE_FALLBACK_NO_UPSCALE,
        REJECT_BELOW_720P
    }

    public enum HdrPolicy {
        REJECT_EXPLICIT
    }

    public static final class UnsupportedHlsSourceException extends IllegalArgumentException {
        public UnsupportedHlsSourceException(String message) {
            super(message);
        }
    }

    public record ProbedVideoSource(
            int rawWidth,
            int rawHeight,
            int displayRotationDegrees,
            String sampleAspectRatio,
            String displayAspectRatio,
            int displayWidth,
            int displayHeight,
            String avgFrameRateRaw,
            String rFrameRateRaw,
            double effectiveFps,
            double durationSeconds,
            String videoCodec,
            String pixelFormat,
            int bitsPerRawSample,
            String colorSpace,
            String colorTransfer,
            String colorPrimaries,
            boolean hasAudio,
            int audioStreamCount,
            String audioCodec,
            int audioChannels,
            int audioSampleRateHz,
            String audioLanguage
    ) {
        public boolean isHdrOr10Bit() {
            if (bitsPerRawSample >= 10) {
                return true;
            }
            String pix = pixelFormat == null ? "" : pixelFormat.toLowerCase(Locale.ROOT);
            if (pix.contains("10") || pix.contains("12") || pix.contains("p010") || pix.contains("p016")) {
                return true;
            }
            String transfer = colorTransfer == null ? "" : colorTransfer.toLowerCase(Locale.ROOT);
            String primaries = colorPrimaries == null ? "" : colorPrimaries.toLowerCase(Locale.ROOT);
            return transfer.contains("smpte2084")
                    || transfer.contains("arib-std-b67")
                    || primaries.contains("bt2020");
        }
    }

    public record VideoTierSpec(
            String label,
            String relativeDir,
            int width,
            int height,
            int crf,
            int maxrateKbps,
            int bufsizeKbps
    ) {
        public String playlistRelativeUri() {
            return relativeDir + "/playlist.m3u8";
        }
    }

    public record HlsPackagingPlan(
            List<VideoTierSpec> videoTiers,
            boolean includeAudio,
            String audioRelativeDir,
            String audioGroupId,
            int audioBitrateKbps,
            int audioChannels,
            int audioSampleRateHz,
            int segmentDurationSeconds,
            int gopSizeFrames,
            boolean below720FallbackApplied
    ) {
        public String audioPlaylistRelativeUri() {
            return audioRelativeDir + "/playlist.m3u8";
        }
    }

    private record NamedTierTemplate(
            String label,
            String relativeDir,
            int boxLongSide,
            int boxShortSide,
            int crf,
            int maxrateKbps,
            int bufsizeKbps
    ) {
    }

    private static final List<NamedTierTemplate> APPROVED_TIERS = List.of(
            new NamedTierTemplate("720p", "video/720p", 1280, 720, 23, 2800, 5600),
            new NamedTierTemplate("1080p", "video/1080p", 1920, 1080, 22, 5000, 10000),
            new NamedTierTemplate("2160p", "video/2160p", 3840, 2160, 22, 14000, 28000)
    );

    public HlsPackagingPlan plan(
            ProbedVideoSource source,
            int segmentDurationSeconds,
            Below720Policy below720Policy,
            HdrPolicy hdrPolicy
    ) {
        Objects.requireNonNull(source, "source cannot be null.");
        Objects.requireNonNull(below720Policy, "below720Policy cannot be null.");
        Objects.requireNonNull(hdrPolicy, "hdrPolicy cannot be null.");
        if (segmentDurationSeconds < 2 || segmentDurationSeconds > 10) {
            throw new IllegalArgumentException(
                    "segmentDurationSeconds must align with V100 domain bounds (2..10s): " + segmentDurationSeconds
            );
        }

        if (source.isHdrOr10Bit()) {
            throw new UnsupportedHlsSourceException(
                    "Source uses 10-bit/HDR signaling (pix_fmt=" + source.pixelFormat()
                            + ", transfer=" + source.colorTransfer()
                            + ", primaries=" + source.colorPrimaries()
                            + "); explicit HDR/tone-mapping policy is required rather than silent SDR conversion."
            );
        }

        int srcW = source.displayWidth();
        int srcH = source.displayHeight();
        if (srcW < 2 || srcH < 2) {
            throw new UnsupportedHlsSourceException("Invalid source display dimensions: " + srcW + "x" + srcH);
        }
        boolean portrait = srcH > srcW;
        int srcLong = Math.max(srcW, srcH);
        int srcShort = Math.min(srcW, srcH);

        List<VideoTierSpec> selectedTiers = new ArrayList<>();
        for (NamedTierTemplate tier : APPROVED_TIERS) {
            boolean eligible = srcLong >= tier.boxLongSide() || srcShort >= tier.boxShortSide();
            if (!eligible) {
                continue;
            }
            int maxW = portrait ? tier.boxShortSide() : tier.boxLongSide();
            int maxH = portrait ? tier.boxLongSide() : tier.boxShortSide();
            int[] scaled = scaleToBoxWithoutUpscale(srcW, srcH, maxW, maxH);
            selectedTiers.add(new VideoTierSpec(
                    tier.label(),
                    tier.relativeDir(),
                    scaled[0],
                    scaled[1],
                    tier.crf(),
                    tier.maxrateKbps(),
                    tier.bufsizeKbps()
            ));
        }

        boolean below720FallbackApplied = false;
        if (selectedTiers.isEmpty()) {
            if (below720Policy == Below720Policy.REJECT_BELOW_720P) {
                throw new UnsupportedHlsSourceException(
                        "Source resolution " + srcW + "x" + srcH + " is below minimum 720p tier and policy prohibits upscaling."
                );
            }
            int evenW = ensureEvenBounded(srcW, srcW);
            int evenH = ensureEvenBounded(srcH, srcH);
            selectedTiers.add(new VideoTierSpec(
                    "source",
                    "video/source",
                    evenW,
                    evenH,
                    23,
                    1500,
                    3000
            ));
            below720FallbackApplied = true;
        }

        boolean includeAudio = source.hasAudio() && source.audioChannels() > 0;
        int targetChannels = includeAudio ? Math.min(2, Math.max(1, source.audioChannels())) : 0;
        int gopFrames = Math.max(12, (int) Math.round(source.effectiveFps() * segmentDurationSeconds));

        return new HlsPackagingPlan(
                List.copyOf(selectedTiers),
                includeAudio,
                includeAudio ? "audio/main" : null,
                includeAudio ? "aud" : null,
                includeAudio ? 128 : 0,
                targetChannels,
                includeAudio ? 48_000 : 0,
                segmentDurationSeconds,
                gopFrames,
                below720FallbackApplied
        );
    }

    private static int[] scaleToBoxWithoutUpscale(int srcW, int srcH, int maxW, int maxH) {
        double scale = Math.min(1.0, Math.min((double) maxW / srcW, (double) maxH / srcH));
        int rawOutW = (int) Math.round(srcW * scale);
        int rawOutH = (int) Math.round(srcH * scale);
        return new int[]{
                ensureEvenBounded(rawOutW, srcW),
                ensureEvenBounded(rawOutH, srcH)
        };
    }

    private static int ensureEvenBounded(int value, int upperBound) {
        int even = value - (value % 2);
        int evenMax = upperBound - (upperBound % 2);
        int clamped = Math.min(even, evenMax);
        return Math.max(2, clamped);
    }
}
