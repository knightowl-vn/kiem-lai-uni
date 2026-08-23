package com.universe.novel.application.chapter;

import java.util.UUID;

public record DeleteDraftChapterCommand(
        UUID chapterId,
        UUID actorId
) {
}
