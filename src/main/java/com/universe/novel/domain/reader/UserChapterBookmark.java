package com.universe.novel.domain.reader;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Aggregate Root đại diện cho dấu trang chương của một người dùng đã xác thực.
 *
 * Quản lý:
 * - Định danh dấu trang (id);
 * - Định danh người dùng (userId);
 * - Định danh chương được đánh dấu (chapterId);
 * - Thời điểm tạo dấu trang (createdAt).
 *
 * Aggregate thuần túy, bất biến (immutable create/delete lifecycle),
 * không liên kết ORM và không chứa logic tiến độ đọc hay lịch sử.
 */
public class UserChapterBookmark {

    private final UUID id;

    private final UUID userId;

    private final UUID chapterId;

    private final Instant createdAt;

    private UserChapterBookmark(
            UUID id,
            UUID userId,
            UUID chapterId,
            Instant createdAt
    ) {
        this.id =
                Objects.requireNonNull(
                        id,
                        "ID dấu trang chương không được để trống."
                );

        this.userId =
                Objects.requireNonNull(
                        userId,
                        "ID người dùng không được để trống."
                );

        this.chapterId =
                Objects.requireNonNull(
                        chapterId,
                        "ID chương không được để trống."
                );

        this.createdAt =
                Objects.requireNonNull(
                        createdAt,
                        "Thời gian tạo dấu trang không được để trống."
                );
    }

    /**
     * Tạo mới một dấu trang chương cho người dùng.
     */
    public static UserChapterBookmark create(
            UUID id,
            UUID userId,
            UUID chapterId,
            Instant now
    ) {
        return new UserChapterBookmark(
                id,
                userId,
                chapterId,
                now
        );
    }

    /**
     * Khôi phục Aggregate từ dữ liệu persistence.
     */
    public static UserChapterBookmark rehydrate(
            UUID id,
            UUID userId,
            UUID chapterId,
            Instant createdAt
    ) {
        return new UserChapterBookmark(
                id,
                userId,
                chapterId,
                createdAt
        );
    }

    public UUID getId() {
        return id;
    }

    public UUID getUserId() {
        return userId;
    }

    public UUID getChapterId() {
        return chapterId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        UserChapterBookmark that = (UserChapterBookmark) o;
        return Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }

    @Override
    public String toString() {
        return "UserChapterBookmark{" +
                "id=" + id +
                ", userId=" + userId +
                ", chapterId=" + chapterId +
                ", createdAt=" + createdAt +
                '}';
    }
}
