package com.universe.novel.application.chapter;

import java.util.UUID;

public record ReorderChapterCommand(
        UUID chapterId,
        int sortOrder,
        UUID actorId
) {
}