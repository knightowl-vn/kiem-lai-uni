package com.universe.novel.application.chapter;

import java.util.UUID;

public record MoveChapterCommand(
        UUID chapterId,
        UUID targetVolumeId,
        int targetSortOrder,
        UUID actorId
) {
}