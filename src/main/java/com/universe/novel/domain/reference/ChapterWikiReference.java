package com.universe.novel.domain.reference;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Entity đại diện cho một liên kết tham chiếu Wiki của Chapter trong Novel module.
 *
 * Quản lý:
 * - Liên kết giữa một thuật ngữ (term) trong chương với một bài viết Wiki (scalar UUID);
 * - Phạm vi áp dụng (CHAPTER_WIDE hoặc OCCURRENCE_SPECIFIC);
 * - Vị trí xuất hiện (occurrenceIndex = 0 cho CHAPTER_WIDE, occurrenceIndex >= 1 cho OCCURRENCE_SPECIFIC);
 * - Phiên bản nội dung chương đã ràng buộc (boundContentVersion);
 * - Ngữ cảnh xung quanh (contextSnippet) để phát hiện trôi dạt khi nội dung chương thay đổi.
 */
public class ChapterWikiReference {

    private static final int MAX_CONTEXT_SNIPPET_LENGTH = 255;

    private final UUID id;

    private final UUID chapterId;

    private String term;

    private String normalizedTerm;

    private final ChapterWikiReferenceScope referenceScope;

    private int occurrenceIndex;

    private String contextSnippet;

    private Long boundContentVersion;

    private UUID wikiArticleId;

    private final UUID createdBy;

    private UUID updatedBy;

    private final Instant createdAt;

    private Instant updatedAt;

    private ChapterWikiReference(
            UUID id,
            UUID chapterId,
            String term,
            String normalizedTerm,
            ChapterWikiReferenceScope referenceScope,
            int occurrenceIndex,
            String contextSnippet,
            Long boundContentVersion,
            UUID wikiArticleId,
            UUID createdBy,
            UUID updatedBy,
            Instant createdAt,
            Instant updatedAt
    ) {
        this.id = Objects.requireNonNull(id, "ID liên kết không được để trống.");
        this.chapterId = Objects.requireNonNull(chapterId, "Chapter ID không được để trống.");
        this.term = ChapterWikiReferenceTermNormalizer.normalizeDisplayTerm(term);
        this.normalizedTerm = ChapterWikiReferenceTermNormalizer.normalizeSearchKey(term);
        this.referenceScope = Objects.requireNonNull(referenceScope, "Phạm vi tham chiếu không được để trống.");
        this.wikiArticleId = Objects.requireNonNull(wikiArticleId, "Wiki Article ID không được để trống.");
        this.createdBy = Objects.requireNonNull(createdBy, "Người tạo không được để trống.");
        this.updatedBy = Objects.requireNonNull(updatedBy, "Người cập nhật không được để trống.");
        this.createdAt = Objects.requireNonNull(createdAt, "Thời gian tạo không được để trống.");
        this.updatedAt = Objects.requireNonNull(updatedAt, "Thời gian cập nhật không được để trống.");

        this.contextSnippet = normalizeContextSnippet(contextSnippet);
        this.occurrenceIndex = occurrenceIndex;
        this.boundContentVersion = boundContentVersion;

        validateScopeInvariants();
    }

    /**
     * Tạo liên kết tham chiếu cấp toàn chương (CHAPTER_WIDE).
     *
     * occurrenceIndex luôn là 0.
     * boundContentVersion luôn là null.
     * contextSnippet luôn là null.
     */
    public static ChapterWikiReference createChapterWide(
            UUID id,
            UUID chapterId,
            String term,
            UUID wikiArticleId,
            UUID actorId,
            Instant createdAt
    ) {
        Objects.requireNonNull(actorId, "Người tạo không được để trống.");
        Objects.requireNonNull(createdAt, "Thời gian tạo không được để trống.");

        String displayTerm = ChapterWikiReferenceTermNormalizer.normalizeDisplayTerm(term);
        String searchKey = ChapterWikiReferenceTermNormalizer.normalizeSearchKey(term);

        return new ChapterWikiReference(
                id,
                chapterId,
                displayTerm,
                searchKey,
                ChapterWikiReferenceScope.CHAPTER_WIDE,
                0,
                null,
                null,
                wikiArticleId,
                actorId,
                actorId,
                createdAt,
                createdAt
        );
    }

    /**
     * Tạo liên kết tham chiếu cho một lần xuất hiện cụ thể (OCCURRENCE_SPECIFIC).
     *
     * occurrenceIndex phải lớn hơn hoặc bằng 1.
     * boundContentVersion phải lớn hơn hoặc bằng 1.
     */
    public static ChapterWikiReference createOccurrenceSpecific(
            UUID id,
            UUID chapterId,
            String term,
            int occurrenceIndex,
            String contextSnippet,
            long boundContentVersion,
            UUID wikiArticleId,
            UUID actorId,
            Instant createdAt
    ) {
        Objects.requireNonNull(actorId, "Người tạo không được để trống.");
        Objects.requireNonNull(createdAt, "Thời gian tạo không được để trống.");

        if (occurrenceIndex < 1) {
            throw new IllegalArgumentException("Chỉ số xuất hiện phải lớn hơn hoặc bằng 1.");
        }

        if (boundContentVersion < 1L) {
            throw new IllegalArgumentException("Phiên bản nội dung ràng buộc phải lớn hơn hoặc bằng 1.");
        }

        String displayTerm = ChapterWikiReferenceTermNormalizer.normalizeDisplayTerm(term);
        String searchKey = ChapterWikiReferenceTermNormalizer.normalizeSearchKey(term);

        return new ChapterWikiReference(
                id,
                chapterId,
                displayTerm,
                searchKey,
                ChapterWikiReferenceScope.OCCURRENCE_SPECIFIC,
                occurrenceIndex,
                contextSnippet,
                boundContentVersion,
                wikiArticleId,
                actorId,
                actorId,
                createdAt,
                createdAt
        );
    }

    /**
     * Khôi phục thực thể từ tầng persistence.
     */
    public static ChapterWikiReference rehydrate(
            UUID id,
            UUID chapterId,
            String term,
            String normalizedTerm,
            ChapterWikiReferenceScope referenceScope,
            int occurrenceIndex,
            String contextSnippet,
            Long boundContentVersion,
            UUID wikiArticleId,
            UUID createdBy,
            UUID updatedBy,
            Instant createdAt,
            Instant updatedAt
    ) {
        return new ChapterWikiReference(
                id,
                chapterId,
                term,
                normalizedTerm,
                referenceScope,
                occurrenceIndex,
                contextSnippet,
                boundContentVersion,
                wikiArticleId,
                createdBy,
                updatedBy,
                createdAt,
                updatedAt
        );
    }

    /**
     * Cập nhật bài viết Wiki đích.
     */
    public void updateTargetArticle(UUID newWikiArticleId, UUID actorId, Instant updatedAt) {
        UUID normalizedTarget = Objects.requireNonNull(newWikiArticleId, "Wiki Article ID không được để trống.");
        UUID normalizedActor = Objects.requireNonNull(actorId, "Người cập nhật không được để trống.");
        Instant normalizedTime = Objects.requireNonNull(updatedAt, "Thời gian cập nhật không được để trống.");

        if (Objects.equals(this.wikiArticleId, normalizedTarget)) {
            return;
        }

        this.wikiArticleId = normalizedTarget;
        this.updatedBy = normalizedActor;
        this.updatedAt = normalizedTime;
    }

    /**
     * Cập nhật thông tin ngữ cảnh / vị trí xuất hiện cho liên kết OCCURRENCE_SPECIFIC.
     */
    public void updateOccurrenceContext(
            int newOccurrenceIndex,
            String newContextSnippet,
            long newBoundContentVersion,
            UUID actorId,
            Instant updatedAt
    ) {
        if (this.referenceScope != ChapterWikiReferenceScope.OCCURRENCE_SPECIFIC) {
            throw new IllegalStateException("Chỉ được cập nhật vị trí xuất hiện cho liên kết OCCURRENCE_SPECIFIC.");
        }

        if (newOccurrenceIndex < 1) {
            throw new IllegalArgumentException("Chỉ số xuất hiện phải lớn hơn hoặc bằng 1.");
        }

        if (newBoundContentVersion < 1L) {
            throw new IllegalArgumentException("Phiên bản nội dung ràng buộc phải lớn hơn hoặc bằng 1.");
        }

        String normalizedSnippet = normalizeContextSnippet(newContextSnippet);
        UUID normalizedActor = Objects.requireNonNull(actorId, "Người cập nhật không được để trống.");
        Instant normalizedTime = Objects.requireNonNull(updatedAt, "Thời gian cập nhật không được để trống.");

        if (this.occurrenceIndex == newOccurrenceIndex
                && Objects.equals(this.contextSnippet, normalizedSnippet)
                && Objects.equals(this.boundContentVersion, newBoundContentVersion)) {
            return;
        }

        this.occurrenceIndex = newOccurrenceIndex;
        this.contextSnippet = normalizedSnippet;
        this.boundContentVersion = newBoundContentVersion;
        this.updatedBy = normalizedActor;
        this.updatedAt = normalizedTime;
    }

    private void validateScopeInvariants() {
        switch (referenceScope) {
            case CHAPTER_WIDE -> {
                if (occurrenceIndex != 0) {
                    throw new IllegalArgumentException("Liên kết CHAPTER_WIDE phải có occurrenceIndex = 0.");
                }
                if (boundContentVersion != null) {
                    throw new IllegalArgumentException("Liên kết CHAPTER_WIDE không được có boundContentVersion.");
                }
            }
            case OCCURRENCE_SPECIFIC -> {
                if (occurrenceIndex < 1) {
                    throw new IllegalArgumentException("Liên kết OCCURRENCE_SPECIFIC phải có occurrenceIndex >= 1.");
                }
                if (boundContentVersion == null || boundContentVersion < 1L) {
                    throw new IllegalArgumentException("Liên kết OCCURRENCE_SPECIFIC phải có boundContentVersion >= 1.");
                }
            }
        }
    }

    private static String normalizeContextSnippet(String snippet) {
        if (snippet == null || snippet.isBlank()) {
            return null;
        }

        String trimmed = snippet.trim();
        if (trimmed.length() > MAX_CONTEXT_SNIPPET_LENGTH) {
            throw new IllegalArgumentException(
                    "Ngữ cảnh xung quanh không được vượt quá " + MAX_CONTEXT_SNIPPET_LENGTH + " ký tự."
            );
        }

        return trimmed;
    }

    public UUID getId() {
        return id;
    }

    public UUID getChapterId() {
        return chapterId;
    }

    public String getTerm() {
        return term;
    }

    public String getNormalizedTerm() {
        return normalizedTerm;
    }

    public ChapterWikiReferenceScope getReferenceScope() {
        return referenceScope;
    }

    public int getOccurrenceIndex() {
        return occurrenceIndex;
    }

    public String getContextSnippet() {
        return contextSnippet;
    }

    public Long getBoundContentVersion() {
        return boundContentVersion;
    }

    public UUID getWikiArticleId() {
        return wikiArticleId;
    }

    public UUID getCreatedBy() {
        return createdBy;
    }

    public UUID getUpdatedBy() {
        return updatedBy;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
