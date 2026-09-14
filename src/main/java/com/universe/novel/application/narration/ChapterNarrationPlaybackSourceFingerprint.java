package com.universe.novel.application.narration;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Versioned, length-delimited SHA-256 identity of the existing build snapshot.
 * Includes assignment and exact binary provenance, not storage locations or filenames.
 */
public final class ChapterNarrationPlaybackSourceFingerprint {
    private ChapterNarrationPlaybackSourceFingerprint() {
    }

    static final String CANONICAL_PREFIX = "chapter-playback-source-v2";

    public static String compute(ChapterNarrationPlaybackBuildSnapshot snapshot) {
        String canonical = canonicalPayload(snapshot);
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is unavailable", ex);
        }
    }

    static String canonicalPayload(ChapterNarrationPlaybackBuildSnapshot snapshot) {
        StringBuilder canonical = new StringBuilder();
        append(canonical, CANONICAL_PREFIX);
        append(canonical, snapshot.chapterId());
        append(canonical, snapshot.managedVoiceId());
        append(canonical, snapshot.sourceContentVersion());
        append(canonical, snapshot.synthesisRevision());
        append(canonical, snapshot.manifestHash());
        append(canonical, snapshot.segments().size());
        for (ChapterNarrationPlaybackSegmentSnapshot segment : snapshot.segments()) {
            append(canonical, segment.segmentId());
            append(canonical, segment.segmentIndex());
            append(canonical, segment.contentHash());
            append(canonical, segment.narrationAudioId());
            append(canonical, segment.narrationAudioVersion());
            append(canonical, segment.mediaAssetId());
            append(canonical, segment.generatedSynthesisRevision());
            append(canonical, segment.encodedContributionSamples());
            append(canonical, segment.encodedSampleRateHz());
            var media = segment.sourceMediaVersion();
            append(canonical, media.assetId());
            append(canonical, media.versionNumber());
            append(canonical, media.contentHash());
            append(canonical, media.mimeType());
            append(canonical, media.sizeBytes());
        }
        return canonical.toString();
    }

    private static void append(StringBuilder output, Object value) {
        if (value == null) {
            output.append("-1:");
        } else {
            String text = value.toString();
            output.append(text.length()).append(':').append(text);
        }
    }
}
