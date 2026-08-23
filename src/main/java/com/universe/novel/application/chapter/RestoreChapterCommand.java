package com.universe.novel.application.chapter;

import java.util.UUID;

public record RestoreChapterCommand(
        UUID chapterId,
        UUID actorId
) {
}
