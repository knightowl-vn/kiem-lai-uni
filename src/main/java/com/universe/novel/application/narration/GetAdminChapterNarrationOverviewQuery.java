package com.universe.novel.application.narration;

import java.util.UUID;

/**
 * Query command for retrieving the admin narration overview of a chapter.
 */
public record GetAdminChapterNarrationOverviewQuery(
        UUID chapterId,
        UUID voiceId
) {
}
