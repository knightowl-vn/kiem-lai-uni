package com.universe.novel.application.chapter.reference;

import java.util.Objects;
import java.util.UUID;

public record BindChapterWideWikiReferenceCommand(
        UUID chapterId,
        String term,
        UUID wikiArticleId,
        UUID actorId
) {
    public BindChapterWideWikiReferenceCommand {
        Objects.requireNonNull(chapterId, "Chapter ID không được để trống.");
        Objects.requireNonNull(term, "Thuật ngữ không được để trống.");
        Objects.requireNonNull(wikiArticleId, "Wiki Article ID không được để trống.");
        Objects.requireNonNull(actorId, "Người thực hiện không được để trống.");
    }
}
