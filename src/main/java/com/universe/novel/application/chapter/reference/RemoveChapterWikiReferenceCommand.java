package com.universe.novel.application.chapter.reference;

import java.util.Objects;
import java.util.UUID;

public record RemoveChapterWikiReferenceCommand(
        UUID referenceId,
        UUID chapterId,
        UUID actorId
) {
    public RemoveChapterWikiReferenceCommand {
        Objects.requireNonNull(referenceId, "Reference ID không được để trống.");
        Objects.requireNonNull(actorId, "Người thực hiện không được để trống.");
    }
}
