package com.universe.novel.application.chapter;

import java.util.UUID;

public record CreateChapterCommand(
        UUID volumeId,
        Integer chapterNumber,
        String title,
        String summary,
        String content,
        UUID actorId
) {
}
