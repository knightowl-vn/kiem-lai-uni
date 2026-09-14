package com.universe.novel.application.narration;

/**
 * Passive command for uploading one caller-owned chapter playback binary resource.
 */
public record UploadChapterNarrationPlaybackMediaCommand(
        ChapterAudioBinaryResource resource,
        String originalFilename
) {
}
