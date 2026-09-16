package com.universe.wiki.domain.saved;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Aggregate Root đại diện cho bài viết Wiki được lưu bởi một người dùng đã xác thực.
 *
 * Quản lý:
 * - Định danh bản ghi lưu (id);
 * - Định danh người dùng (userId);
 * - Định danh bài viết Wiki được lưu (articleId);
 * - Thời điểm tạo bản ghi lưu (createdAt).
 *
 * Aggregate thuần túy, bất biến (immutable create/delete lifecycle),
 * không liên kết ORM và không phụ thuộc vào framework.
 */
public class UserSavedWikiArticle {

    private final UUID id;
    private final UUID userId;
    private final UUID articleId;
    private final Instant createdAt;

    private UserSavedWikiArticle(
            UUID id,
            UUID userId,
            UUID articleId,
            Instant createdAt
    ) {
        this.id = Objects.requireNonNull(
                id,
                "ID bài viết Wiki đã lưu không được để trống."
        );
        this.userId = Objects.requireNonNull(
                userId,
                "ID người dùng không được để trống."
        );
        this.articleId = Objects.requireNonNull(
                articleId,
                "ID bài viết không được để trống."
        );
        this.createdAt = Objects.requireNonNull(
                createdAt,
                "Thời gian lưu bài viết không được để trống."
        );
    }

    /**
     * Tạo mới một bản ghi lưu bài viết Wiki cho người dùng.
     */
    public static UserSavedWikiArticle create(
            UUID id,
            UUID userId,
            UUID articleId,
            Instant createdAt
    ) {
        return new UserSavedWikiArticle(
                id,
                userId,
                articleId,
                createdAt
        );
    }

    /**
     * Khôi phục Aggregate từ tầng persistence.
     */
    public static UserSavedWikiArticle rehydrate(
            UUID id,
            UUID userId,
            UUID articleId,
            Instant createdAt
    ) {
        return new UserSavedWikiArticle(
                id,
                userId,
                articleId,
                createdAt
        );
    }

    public UUID getId() {
        return id;
    }

    public UUID getUserId() {
        return userId;
    }

    public UUID getArticleId() {
        return articleId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        UserSavedWikiArticle that = (UserSavedWikiArticle) o;
        return Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }

    @Override
    public String toString() {
        return "UserSavedWikiArticle{" +
                "id=" + id +
                ", userId=" + userId +
                ", articleId=" + articleId +
                ", createdAt=" + createdAt +
                '}';
    }
}
