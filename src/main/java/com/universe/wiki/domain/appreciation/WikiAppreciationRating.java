package com.universe.wiki.domain.appreciation;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Aggregate Root đại diện cho đánh giá mức độ yêu thích (Appreciation Rating) của một người dùng
 * đối với một bài viết Wiki (Nhân vật hoặc Thế lực/Tông môn).
 *
 * Bất biến nghiệp vụ:
 * - Một người dùng chỉ có tối đa một đánh giá hiện hành trên một bài viết Wiki (1 user + 1 article = at most 1 rating).
 * - Điểm đánh giá (value) là số nguyên từ 1 đến 5 sao: 1 <= value <= 5.
 * - Khởi tạo ban đầu: createdAt == updatedAt.
 * - Cập nhật cùng giá trị (same-value): no-op, không đổi updatedAt, trả về false.
 * - Cập nhật khác giá trị: cập nhật value, updatedAt = now, createdAt giữ nguyên, trả về true.
 *
 * Aggregate thuần túy, không phụ thuộc vào framework hay ORM.
 */
public class WikiAppreciationRating {

    public static final int MIN_VALUE = 1;
    public static final int MAX_VALUE = 5;

    private final UUID id;
    private final UUID wikiArticleId;
    private final UUID userId;
    private int value;
    private final Instant createdAt;
    private Instant updatedAt;

    private WikiAppreciationRating(
            UUID id,
            UUID wikiArticleId,
            UUID userId,
            int value,
            Instant createdAt,
            Instant updatedAt
    ) {
        this.id = Objects.requireNonNull(
                id,
                "ID đánh giá không được để trống."
        );
        this.wikiArticleId = Objects.requireNonNull(
                wikiArticleId,
                "ID bài viết Wiki không được để trống."
        );
        this.userId = Objects.requireNonNull(
                userId,
                "ID người dùng không được để trống."
        );
        validateValue(value);
        this.value = value;
        this.createdAt = Objects.requireNonNull(
                createdAt,
                "Thời gian tạo không được để trống."
        );
        this.updatedAt = Objects.requireNonNull(
                updatedAt,
                "Thời gian cập nhật không được để trống."
        );
    }

    /**
     * Khởi tạo mới một đánh giá mức độ yêu thích.
     * Khi tạo mới: updatedAt được gán bằng createdAt.
     */
    public static WikiAppreciationRating create(
            UUID id,
            UUID wikiArticleId,
            UUID userId,
            int value,
            Instant createdAt
    ) {
        return new WikiAppreciationRating(
                id,
                wikiArticleId,
                userId,
                value,
                createdAt,
                createdAt
        );
    }

    /**
     * Khôi phục (rehydrate) một bản ghi đánh giá từ tầng lưu trữ (persistence).
     * Tuyệt đối không tự động chuẩn hóa hay sửa sai giá trị không hợp lệ.
     */
    public static WikiAppreciationRating rehydrate(
            UUID id,
            UUID wikiArticleId,
            UUID userId,
            int value,
            Instant createdAt,
            Instant updatedAt
    ) {
        return new WikiAppreciationRating(
                id,
                wikiArticleId,
                userId,
                value,
                createdAt,
                updatedAt
        );
    }

    /**
     * Cập nhật giá trị đánh giá.
     *
     * @param newValue giá trị đánh giá mới (1..5)
     * @param now thời điểm cập nhật
     * @return true nếu giá trị thực sự thay đổi, false nếu cùng giá trị (no-op)
     */
    public boolean updateValue(int newValue, Instant now) {
        validateValue(newValue);
        Objects.requireNonNull(now, "Thời gian cập nhật không được để trống.");

        if (this.value == newValue) {
            return false;
        }

        this.value = newValue;
        this.updatedAt = now;
        return true;
    }

    private static void validateValue(int value) {
        if (value < MIN_VALUE || value > MAX_VALUE) {
            throw new IllegalArgumentException(
                    "Giá trị đánh giá phải nằm trong khoảng từ " + MIN_VALUE + " đến " + MAX_VALUE + " sao, nhận được: " + value
            );
        }
    }

    public UUID getId() {
        return id;
    }

    public UUID getWikiArticleId() {
        return wikiArticleId;
    }

    public UUID getUserId() {
        return userId;
    }

    public int getValue() {
        return value;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        WikiAppreciationRating that = (WikiAppreciationRating) o;
        return Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }

    @Override
    public String toString() {
        return "WikiAppreciationRating{" +
                "id=" + id +
                ", wikiArticleId=" + wikiArticleId +
                ", userId=" + userId +
                ", value=" + value +
                ", createdAt=" + createdAt +
                ", updatedAt=" + updatedAt +
                '}';
    }
}
