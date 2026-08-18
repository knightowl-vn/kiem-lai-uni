package com.universe.novel.application.chapter;

import java.util.UUID;

public record CreateChapterCommand(
        UUID volumeId,
        Integer chapterNumber,
        int sortOrder,
        String title,
        String slug,
        String summary,
        String content,
        UUID actorId
) {
}