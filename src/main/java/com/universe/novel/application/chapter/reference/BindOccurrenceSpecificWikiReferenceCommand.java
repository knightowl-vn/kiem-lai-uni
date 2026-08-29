package com.universe.novel.application.chapter.reference;

import java.util.Objects;
import java.util.UUID;

public record BindOccurrenceSpecificWikiReferenceCommand(
        UUID chapterId,
        String term,
        int occurrenceIndex,
        String contextSnippet,
        UUID wikiArticleId,
        UUID actorId
) {
    public BindOccurrenceSpecificWikiReferenceCommand {
        Objects.requireNonNull(chapterId, "Chapter ID không được để trống.");
        Objects.requireNonNull(term, "Thuật ngữ không được để trống.");
        if (occurrenceIndex < 1) {
            throw new IllegalArgumentException("Chỉ số xuất hiện phải lớn hơn hoặc bằng 1.");
        }
        Objects.requireNonNull(wikiArticleId, "Wiki Article ID không được để trống.");
        Objects.requireNonNull(actorId, "Người thực hiện không được để trống.");
    }
}
