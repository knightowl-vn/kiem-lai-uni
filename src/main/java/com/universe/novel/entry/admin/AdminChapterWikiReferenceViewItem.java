package com.universe.novel.entry.admin;

import com.universe.novel.application.chapter.reference.ChapterWikiReferenceItemDTO;
import com.universe.novel.application.chapter.reference.ChapterWikiReferenceStatus;
import com.universe.novel.application.chapter.reference.PublishedWikiArticleSummary;
import com.universe.novel.domain.reference.ChapterWikiReferenceScope;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * View model phục vụ tầng giao diện Admin, đóng gói ChapterWikiReferenceItemDTO và cung cấp
 * các helper hiển thị / fallback text tiếng Việt an toàn cho giao diện.
 * View model này thuần túy presentation, KHÔNG truy vấn PublishedWikiArticlePort.
 */
public record AdminChapterWikiReferenceViewItem(
        ChapterWikiReferenceItemDTO reference
) {
    public AdminChapterWikiReferenceViewItem {
        Objects.requireNonNull(reference, "ChapterWikiReferenceItemDTO không được để trống.");
    }

    public static AdminChapterWikiReferenceViewItem from(ChapterWikiReferenceItemDTO dto) {
        return new AdminChapterWikiReferenceViewItem(dto);
    }

    public UUID id() {
        return reference.id();
    }

    public UUID chapterId() {
        return reference.chapterId();
    }

    public String term() {
        return reference.term();
    }

    public String normalizedTerm() {
        return reference.normalizedTerm();
    }

    public ChapterWikiReferenceScope referenceScope() {
        return reference.referenceScope();
    }

    public int occurrenceIndex() {
        return reference.occurrenceIndex();
    }

    public String contextSnippet() {
        return reference.contextSnippet();
    }

    public Long boundContentVersion() {
        return reference.boundContentVersion();
    }

    public long currentChapterContentVersion() {
        return reference.currentChapterContentVersion();
    }

    public UUID wikiArticleId() {
        return reference.wikiArticleId();
    }

    public ChapterWikiReferenceStatus status() {
        return reference.status();
    }

    public PublishedWikiArticleSummary wikiArticle() {
        return reference.wikiArticle();
    }

    public UUID createdBy() {
        return reference.createdBy();
    }

    public UUID updatedBy() {
        return reference.updatedBy();
    }

    public Instant createdAt() {
        return reference.createdAt();
    }

    public Instant updatedAt() {
        return reference.updatedAt();
    }

    public boolean isWikiArticleAvailable() {
        return reference.wikiArticle() != null;
    }

    public String targetTitle() {
        return isWikiArticleAvailable() ? reference.wikiArticle().title() : "Bài viết không khả dụng";
    }

    public String targetArticleType() {
        return isWikiArticleAvailable() ? reference.wikiArticle().articleType() : "—";
    }

    public String targetSlug() {
        return isWikiArticleAvailable() ? reference.wikiArticle().slug() : null;
    }
}
