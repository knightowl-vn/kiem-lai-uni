package com.universe.novel.application.narration;

/**
 * Passive command for uploading one caller-owned H.9D2 encoded chapter playback resource.
 */
public record UploadChapterNarrationPlaybackMediaCommand(
        ChapterAudioEncodedResource resource,
        String originalFilename
) {
}
