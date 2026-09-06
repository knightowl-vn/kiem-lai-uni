package com.universe.novel.application.narration;

import java.util.UUID;

/**
 * Query for retrieving public chapter narration manifest (MS-04.9H.7A).
 */
public record GetPublicChapterNarrationManifestQuery(
        UUID chapterId,
        String voiceKey
) {
}
