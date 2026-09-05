package com.universe.novel.application.narration;

import java.util.Locale;
import java.util.UUID;

/**
 * Utility class for resolving safe, provider-neutral audio file names and extensions from MIME types.
 */
public final class NarrationAudioFilenameResolver {

    private NarrationAudioFilenameResolver() {
    }

    /**
     * Derives a safe filename for a narration segment audio asset based on its MIME type.
     *
     * @param segmentId identity of the narration segment
     * @param mediaType MIME type string (e.g. "audio/wav", "audio/mpeg")
     * @return provider-neutral filename such as "segment-{segmentId}.wav" or "segment-{segmentId}.mp3"
     */
    public static String resolveFilename(UUID segmentId, String mediaType) {
        String extension = resolveExtension(mediaType);
        return "segment-" + segmentId + extension;
    }

    /**
     * Resolves a file extension with leading dot corresponding to an audio MIME type.
     *
     * @param mediaType MIME type string
     * @return file extension such as ".wav", ".mp3", ".ogg", ".webm", ".aac", ".m4a", ".flac", or ".audio"
     */
    public static String resolveExtension(String mediaType) {
        if (mediaType == null || mediaType.isBlank()) {
            return ".audio";
        }
        String cleanMime = mediaType.split(";")[0].trim().toLowerCase(Locale.ROOT);
        return switch (cleanMime) {
            case "audio/wav", "audio/x-wav", "audio/wave" -> ".wav";
            case "audio/mpeg", "audio/mp3" -> ".mp3";
            case "audio/ogg", "audio/opus" -> ".ogg";
            case "audio/webm" -> ".webm";
            case "audio/aac" -> ".aac";
            case "audio/mp4", "audio/m4a", "audio/x-m4a" -> ".m4a";
            case "audio/flac", "audio/x-flac" -> ".flac";
            default -> {
                if (cleanMime.startsWith("audio/")) {
                    String sub = cleanMime.substring("audio/".length()).trim();
                    if (sub.startsWith("x-")) {
                        sub = sub.substring(2);
                    }
                    if (sub.matches("^[a-zA-Z0-9]{2,10}$")) {
                        yield "." + sub;
                    }
                }
                yield ".audio";
            }
        };
    }
}
