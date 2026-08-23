package com.universe.novel.application.chapter;

import java.util.UUID;

public record ArchiveChapterCommand(
        UUID chapterId,
        UUID actorId
) {
}