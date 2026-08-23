package com.universe.novel.application.chapter;

import java.util.UUID;

public record UpdateDraftChapterCommand(
        UUID chapterId,
        Integer chapterNumber,
        String title,
        String summary,
        String content,
        UUID actorId
) {
}
