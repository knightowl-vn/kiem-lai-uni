package com.universe.novel.domain.reader;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Aggregate Root đại diện cho lịch sử đọc một chương của người dùng đã xác thực.
 *
 * Quản lý:
 * - Định danh bản ghi lịch sử (id);
 * - Định danh người dùng (userId);
 * - Định danh chương được đọc (chapterId);
 * - Thời điểm đọc lần đầu tiên (firstReadAt);
 * - Thời điểm đọc gần nhất (lastReadAt).
 *
 * Aggregate thuần túy, không chứa phụ thuộc framework/JPA/Spring.
 */
public class UserChapterReadingHistory {

    private final UUID id;

    private final UUID userId;

    private final UUID chapterId;

    private final Instant firstReadAt;

    private Instant lastReadAt;

    private UserChapterReadingHistory(
            UUID id,
            UUID userId,
            UUID chapterId,
            Instant firstReadAt,
            Instant lastReadAt
    ) {
        this.id = Objects.requireNonNull(
                id,
                "ID lịch sử đọc không được để trống."
        );

        this.userId = Objects.requireNonNull(
                userId,
                "ID người dùng không được để trống."
        );

        this.chapterId = Objects.requireNonNull(
                chapterId,
                "ID chương không được để trống."
        );

        this.firstReadAt = Objects.requireNonNull(
                firstReadAt,
                "Thời gian đọc lần đầu không được để trống."
        );

        this.lastReadAt = Objects.requireNonNull(
                lastReadAt,
                "Thời gian đọc gần nhất không được để trống."
        );
    }

    /**
     * Tạo mới bản ghi lịch sử đọc chương ban đầu cho người dùng.
     * Thiết lập firstReadAt = lastReadAt = now.
     */
    public static UserChapterReadingHistory createInitial(
            UUID id,
            UUID userId,
            UUID chapterId,
            Instant now
    ) {
        Objects.requireNonNull(
                now,
                "Thời gian đọc không được để trống."
        );

        return new UserChapterReadingHistory(
                id,
                userId,
                chapterId,
                now,
                now
        );
    }

    /**
     * Khôi phục Aggregate từ dữ liệu persistence.
     */
    public static UserChapterReadingHistory rehydrate(
            UUID id,
            UUID userId,
            UUID chapterId,
            Instant firstReadAt,
            Instant lastReadAt
    ) {
        return new UserChapterReadingHistory(
                id,
                userId,
                chapterId,
                firstReadAt,
                lastReadAt
        );
    }

    /**
     * Cập nhật thời điểm đọc gần nhất khi người dùng mở lại chương này.
     */
    public void recordRead(Instant now) {
        this.lastReadAt = Objects.requireNonNull(
                now,
                "Thời gian đọc không được để trống."
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

    public Instant getFirstReadAt() {
        return firstReadAt;
    }

    public Instant getLastReadAt() {
        return lastReadAt;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        UserChapterReadingHistory that = (UserChapterReadingHistory) o;
        return Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }

    @Override
    public String toString() {
        return "UserChapterReadingHistory{" +
                "id=" + id +
                ", userId=" + userId +
                ", chapterId=" + chapterId +
                ", firstReadAt=" + firstReadAt +
                ", lastReadAt=" + lastReadAt +
                '}';
    }
}
