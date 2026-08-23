package com.universe.novel.application.chapter;

import java.util.UUID;

public record UnpublishChapterCommand(
        UUID chapterId,
        UUID actorId
) {
}
