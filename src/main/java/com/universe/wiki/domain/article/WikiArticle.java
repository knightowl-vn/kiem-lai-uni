package com.universe.wiki.domain.article;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Aggregate Root đại diện cho một bài viết trong Wiki.
 *
 * Quản lý:
 * - thông tin chung của bài viết;
 * - vòng đời DRAFT → PUBLISHED → ARCHIVED;
 * - các quy tắc chỉnh sửa và xuất bản;
 * - aggregate version.
 */
public class WikiArticle {

    private static final int MIN_TITLE_LENGTH =
            2;

    private static final int MAX_TITLE_LENGTH =
            200;

    private static final int MAX_SUMMARY_LENGTH =
            1000;

    private static final int MAX_CONTENT_LENGTH =
            500_000;

    private final UUID id;

    private String title;

    private Slug slug;

    private ArticleType articleType;

    private String summary;

    private String content;

    private ArticleStatus status;

    private final UUID createdBy;

    private UUID updatedBy;

    private UUID publishedBy;

    private UUID archivedBy;

    private final Instant createdAt;

    private Instant updatedAt;

    private Instant publishedAt;

    private Instant archivedAt;

    private long aggregateVersion;

    private WikiArticle(
            UUID id,
            String title,
            Slug slug,
            ArticleType articleType,
            String summary,
            String content,
            ArticleStatus status,
            UUID createdBy,
            UUID updatedBy,
            UUID publishedBy,
            UUID archivedBy,
            Instant createdAt,
            Instant updatedAt,
            Instant publishedAt,
            Instant archivedAt,
            long aggregateVersion
    ) {
        this.id =
                Objects.requireNonNull(
                        id,
                        "Article ID không được để trống."
                );

        this.title =
                validateTitle(title);

        this.slug =
                Objects.requireNonNull(
                        slug,
                        "Slug không được để trống."
                );

        this.articleType =
                Objects.requireNonNull(
                        articleType,
                        "Article type không được để trống."
                );

        this.summary =
                validateSummary(summary);

        this.content =
                validateContent(content);

        this.status =
                Objects.requireNonNull(
                        status,
                        "Article status không được để trống."
                );

        this.createdBy =
                Objects.requireNonNull(
                        createdBy,
                        "Người tạo bài viết không được để trống."
                );

        this.updatedBy =
                updatedBy;

        this.publishedBy =
                publishedBy;

        this.archivedBy =
                archivedBy;

        this.createdAt =
                Objects.requireNonNull(
                        createdAt,
                        "Thời gian tạo bài viết không được để trống."
                );

        this.updatedAt =
                Objects.requireNonNull(
                        updatedAt,
                        "Thời gian cập nhật bài viết không được để trống."
                );

        this.publishedAt =
                publishedAt;

        this.archivedAt =
                archivedAt;

        if (aggregateVersion < 1L) {
            throw new IllegalArgumentException(
                    "Aggregate version phải lớn hơn hoặc bằng 1."
            );
        }

        this.aggregateVersion =
                aggregateVersion;
    }

    /**
     * Tạo bản nháp rỗng.
     *
     * Giữ lại để tương thích với các caller cũ.
     */
    public static WikiArticle createDraft(
            UUID id,
            String title,
            Slug slug,
            ArticleType articleType,
            UUID createdBy,
            Instant now
    ) {
        return createDraft(
                id,
                title,
                slug,
                articleType,
                "",
                "",
                createdBy,
                now
        );
    }
    
    /**
     * Tạo một bài viết mới ở trạng thái DRAFT
     * cùng với nội dung ban đầu.
     */
    public static WikiArticle createDraft(
            UUID id,
            String title,
            Slug slug,
            ArticleType articleType,
            String summary,
            String content,
            UUID createdBy,
            Instant now
    ) {
        Objects.requireNonNull(
                now,
                "Thời gian tạo bài viết không được để trống."
        );

        return new WikiArticle(
                id,
                title,
                slug,
                articleType,
                summary,
                content,
                ArticleStatus.DRAFT,
                createdBy,
                createdBy,
                null,
                null,
                now,
                now,
                null,
                null,
                1L
        );
    }
    
    /**
     * Tạo một bài viết mới và xuất bản ngay.
     *
     * Đây là một thao tác nghiệp vụ duy nhất nên Aggregate
     * bắt đầu ở version 1, không đi qua một DRAFT được lưu.
     */
    public static WikiArticle createPublished(
            UUID id,
            String title,
            Slug slug,
            ArticleType articleType,
            String summary,
            String content,
            UUID createdBy,
            Instant now
    ) {
        UUID normalizedCreatedBy =
                Objects.requireNonNull(
                        createdBy,
                        "Người tạo bài viết không được để trống."
                );

        Instant normalizedNow =
                Objects.requireNonNull(
                        now,
                        "Thời gian tạo bài viết không được để trống."
                );

        WikiArticle article =
                new WikiArticle(
                        id,
                        title,
                        slug,
                        articleType,
                        summary,
                        content,
                        ArticleStatus.PUBLISHED,
                        normalizedCreatedBy,
                        normalizedCreatedBy,
                        normalizedCreatedBy,
                        null,
                        normalizedNow,
                        normalizedNow,
                        normalizedNow,
                        null,
                        1L
                );

        /*
         * Tái sử dụng đúng invariant Publish hiện tại:
         * summary và content bắt buộc phải có.
         */
        article.requirePublishableContent();

        return article;
    }

    /**
     * Khôi phục Aggregate từ dữ liệu persistence.
     *
     * Method này không tạo thêm thay đổi nghiệp vụ.
     */
    public static WikiArticle rehydrate(
            UUID id,
            String title,
            Slug slug,
            ArticleType articleType,
            String summary,
            String content,
            ArticleStatus status,
            UUID createdBy,
            UUID updatedBy,
            UUID publishedBy,
            UUID archivedBy,
            Instant createdAt,
            Instant updatedAt,
            Instant publishedAt,
            Instant archivedAt,
            long aggregateVersion
    ) {
        return new WikiArticle(
                id,
                title,
                slug,
                articleType,
                summary,
                content,
                status,
                createdBy,
                updatedBy,
                publishedBy,
                archivedBy,
                createdAt,
                updatedAt,
                publishedAt,
                archivedAt,
                aggregateVersion
        );
    }

    /**
     * Cập nhật toàn bộ thông tin của bản nháp.
     *
     * Tiêu đề, slug và loại bài chỉ được thay đổi
     * khi bài viết vẫn còn ở trạng thái DRAFT.
     */
    public void updateDraft(
            String title,
            Slug slug,
            ArticleType articleType,
            String summary,
            String content,
            UUID editorId,
            Instant now
    ) {
        requireStatus(
                ArticleStatus.DRAFT,
                "Chỉ được thay đổi tiêu đề, slug và loại bài khi bài viết còn là bản nháp."
        );

        this.title =
                validateTitle(title);

        this.slug =
                Objects.requireNonNull(
                        slug,
                        "Slug không được để trống."
                );

        this.articleType =
                Objects.requireNonNull(
                        articleType,
                        "Article type không được để trống."
                );

        this.summary =
                validateSummary(summary);

        this.content =
                validateContent(content);

        markUpdated(
                editorId,
                now
        );
    }

    /**
     * Cập nhật nội dung của bài viết đã xuất bản.
     *
     * Bài viết vẫn giữ trạng thái PUBLISHED.
     * Tiêu đề, slug và article type không bị thay đổi.
     */
    public void updatePublishedContent(
            String summary,
            String content,
            UUID editorId,
            Instant now
    ) {
        requireStatus(
                ArticleStatus.PUBLISHED,
                "Chỉ bài viết ở trạng thái PUBLISHED mới được cập nhật theo luồng này."
        );

        this.summary =
                validateSummary(summary);

        this.content =
                validateContent(content);

        markUpdated(
                editorId,
                now
        );
    }

    /**
     * Xuất bản bài viết.
     */
    public void publish(
            UUID publisherId,
            Instant now
    ) {
        requireStatus(
                ArticleStatus.DRAFT,
                "Chỉ bài viết ở trạng thái DRAFT mới được xuất bản."
        );

        requirePublishableContent();

        UUID normalizedPublisherId =
                Objects.requireNonNull(
                        publisherId,
                        "Người xuất bản không được để trống."
                );

        Instant normalizedNow =
                Objects.requireNonNull(
                        now,
                        "Thời gian xuất bản không được để trống."
                );

        this.status =
                ArticleStatus.PUBLISHED;

        this.publishedBy =
                normalizedPublisherId;

        this.publishedAt =
                normalizedNow;

        this.updatedBy =
                normalizedPublisherId;

        this.updatedAt =
                normalizedNow;

        increaseVersion();
    }
    
    /**
     * Khôi phục dữ liệu từ một revision cũ thành bản nháp mới.
     *
     * Nội dung được khôi phục không công khai ngay lập tức.
     * Bài viết luôn trở về trạng thái DRAFT để được kiểm tra
     * trước khi xuất bản lại.
     */
    public void restoreAsDraft(
            String title,
            Slug slug,
            ArticleType articleType,
            String summary,
            String content,
            UUID actorId,
            Instant now
    ) {
        /*
         * Chuẩn hóa và kiểm tra toàn bộ dữ liệu trước khi
         * thay đổi trạng thái Aggregate, tránh cập nhật dở dang
         * nếu một tham số không hợp lệ.
         */
        String normalizedTitle =
                validateTitle(title);

        Slug normalizedSlug =
                Objects.requireNonNull(
                        slug,
                        "Slug không được để trống."
                );

        ArticleType normalizedArticleType =
                Objects.requireNonNull(
                        articleType,
                        "Article type không được để trống."
                );

        String normalizedSummary =
                validateSummary(summary);

        String normalizedContent =
                validateContent(content);

        UUID normalizedActorId =
                Objects.requireNonNull(
                        actorId,
                        "Người khôi phục không được để trống."
                );

        Instant normalizedNow =
                Objects.requireNonNull(
                        now,
                        "Thời gian khôi phục không được để trống."
                );

        this.title =
                normalizedTitle;

        this.slug =
                normalizedSlug;

        this.articleType =
                normalizedArticleType;

        this.summary =
                normalizedSummary;

        this.content =
                normalizedContent;

        /*
         * Revision cũ được khôi phục thành bản nháp,
         * không tự động công khai trở lại.
         */
        this.status =
                ArticleStatus.DRAFT;

        /*
         * Xóa metadata của trạng thái publish/archive cũ.
         */
        this.publishedBy =
                null;

        this.publishedAt =
                null;

        this.archivedBy =
                null;

        this.archivedAt =
                null;

        this.updatedBy =
                normalizedActorId;

        this.updatedAt =
                normalizedNow;

        increaseVersion();
    }

    /**
     * Lưu trữ bài viết.
     */
    public void archive(
            UUID actorId,
            Instant now
    ) {
        if (status == ArticleStatus.ARCHIVED) {
            throw new IllegalStateException(
                    "Bài viết đã ở trạng thái ARCHIVED."
            );
        }

        UUID normalizedActorId =
                Objects.requireNonNull(
                        actorId,
                        "Người lưu trữ bài viết không được để trống."
                );

        Instant normalizedNow =
                Objects.requireNonNull(
                        now,
                        "Thời gian lưu trữ bài viết không được để trống."
                );

        this.status =
                ArticleStatus.ARCHIVED;

        this.archivedBy =
                normalizedActorId;

        this.archivedAt =
                normalizedNow;

        this.updatedBy =
                normalizedActorId;

        this.updatedAt =
                normalizedNow;

        increaseVersion();
    }

    private void requirePublishableContent() {

        if (content == null
                || content.isBlank()) {

            throw new IllegalStateException(
                    "Bài viết phải có nội dung trước khi xuất bản."
            );
        }
    }

    private void requireStatus(
            ArticleStatus requiredStatus,
            String errorMessage
    ) {
        if (status != requiredStatus) {
            throw new IllegalStateException(
                    errorMessage
            );
        }
    }

    private void markUpdated(
            UUID editorId,
            Instant now
    ) {
        this.updatedBy =
                Objects.requireNonNull(
                        editorId,
                        "Người cập nhật không được để trống."
                );

        this.updatedAt =
                Objects.requireNonNull(
                        now,
                        "Thời gian cập nhật không được để trống."
                );

        increaseVersion();
    }

    private void increaseVersion() {
        this.aggregateVersion++;
    }

    private static String validateTitle(
            String title
    ) {
        if (title == null) {
            throw new IllegalArgumentException(
                    "Tiêu đề bài viết không được để trống."
            );
        }

        String normalizedTitle =
                title.trim();

        if (normalizedTitle.length()
                < MIN_TITLE_LENGTH) {

            throw new IllegalArgumentException(
                    "Tiêu đề bài viết phải có ít nhất "
                            + MIN_TITLE_LENGTH
                            + " ký tự."
            );
        }

        if (normalizedTitle.length()
                > MAX_TITLE_LENGTH) {

            throw new IllegalArgumentException(
                    "Tiêu đề bài viết không được vượt quá "
                            + MAX_TITLE_LENGTH
                            + " ký tự."
            );
        }

        return normalizedTitle;
    }

    private static String validateSummary(
            String summary
    ) {
        if (summary == null) {
            return "";
        }

        String normalizedSummary =
                summary.trim();

        if (normalizedSummary.length()
                > MAX_SUMMARY_LENGTH) {

            throw new IllegalArgumentException(
                    "Phần tóm tắt không được vượt quá "
                            + MAX_SUMMARY_LENGTH
                            + " ký tự."
            );
        }

        return normalizedSummary;
    }

    private static String validateContent(
            String content
    ) {
        if (content == null) {
            return "";
        }

        String normalizedContent =
                content.trim();

        if (normalizedContent.length()
                > MAX_CONTENT_LENGTH) {

            throw new IllegalArgumentException(
                    "Nội dung bài viết không được vượt quá "
                            + MAX_CONTENT_LENGTH
                            + " ký tự."
            );
        }

        return normalizedContent;
    }

    public UUID getId() {
        return id;
    }

    public String getTitle() {
        return title;
    }

    public Slug getSlug() {
        return slug;
    }

    public ArticleType getArticleType() {
        return articleType;
    }

    public String getSummary() {
        return summary;
    }

    public String getContent() {
        return content;
    }

    public ArticleStatus getStatus() {
        return status;
    }

    public UUID getCreatedBy() {
        return createdBy;
    }

    public UUID getUpdatedBy() {
        return updatedBy;
    }

    public UUID getPublishedBy() {
        return publishedBy;
    }

    public UUID getArchivedBy() {
        return archivedBy;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public Instant getPublishedAt() {
        return publishedAt;
    }

    public Instant getArchivedAt() {
        return archivedAt;
    }

    public long getAggregateVersion() {
        return aggregateVersion;
    }
}