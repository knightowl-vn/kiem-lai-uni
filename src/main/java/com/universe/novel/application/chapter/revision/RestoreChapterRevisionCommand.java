package com.universe.novel.application.chapter.revision;

import java.util.UUID;

public record RestoreChapterRevisionCommand(
        UUID chapterId,
        long sourceRevisionNumber,
        long expectedAggregateVersion,
        UUID actorId,
        String editSummary
) {

    public RestoreChapterRevisionCommand(
            UUID chapterId,
            long sourceRevisionNumber,
            long expectedAggregateVersion,
            UUID actorId
    ) {
        this(
                chapterId,
                sourceRevisionNumber,
                expectedAggregateVersion,
                actorId,
                null
        );
    }
}
