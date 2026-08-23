package com.universe.novel.application.chapter;

import java.util.UUID;

public record PublishChapterCommand(
        UUID chapterId,
        UUID actorId
) {
}