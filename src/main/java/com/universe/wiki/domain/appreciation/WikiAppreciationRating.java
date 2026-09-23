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
 * - Điểm đánh giá được quản lý chặt chẽ qua Value Object {@link WikiAppreciationScore}.
 * - Khởi tạo ban đầu: createdAt == updatedAt.
 * - Cập nhật cùng giá trị (same-value): no-op, không đổi updatedAt, trả về false.
 * - Cập nhật khác giá trị: cập nhật score, updatedAt = now, createdAt giữ nguyên, trả về true.
 *
 * Aggregate thuần túy, không phụ thuộc vào framework hay ORM.
 */
public class WikiAppreciationRating {

    public static final int MIN_VALUE = WikiAppreciationScore.MIN_HALF_STAR_UNITS;
    public static final int MAX_VALUE = WikiAppreciationScore.MAX_HALF_STAR_UNITS;

    private final UUID id;
    private final UUID wikiArticleId;
    private final UUID userId;
    private WikiAppreciationScore score;
    private final Instant createdAt;
    private Instant updatedAt;

    private WikiAppreciationRating(
            UUID id,
            UUID wikiArticleId,
            UUID userId,
            WikiAppreciationScore score,
            Instant createdAt,
            Instant updatedAt
    ) {
        this.id = Objects.requireNonNull(id, "ID đánh giá không được để trống.");
        this.wikiArticleId = Objects.requireNonNull(wikiArticleId, "ID bài viết Wiki không được để trống.");
        this.userId = Objects.requireNonNull(userId, "ID người dùng không được để trống.");
        this.score = Objects.requireNonNull(score, "WikiAppreciationScore không được để trống.");
        this.createdAt = Objects.requireNonNull(createdAt, "Thời gian tạo không được để trống.");
        this.updatedAt = Objects.requireNonNull(updatedAt, "Thời gian cập nhật không được để trống.");
    }

    /**
     * Khởi tạo mới một đánh giá mức độ yêu thích với WikiAppreciationScore.
     */
    public static WikiAppreciationRating create(
            UUID id,
            UUID wikiArticleId,
            UUID userId,
            WikiAppreciationScore score,
            Instant createdAt
    ) {
        return new WikiAppreciationRating(
                id,
                wikiArticleId,
                userId,
                score,
                createdAt,
                createdAt
        );
    }

    /**
     * Khôi phục (rehydrate) một bản ghi đánh giá từ tầng lưu trữ (persistence).
     */
    public static WikiAppreciationRating rehydrate(
            UUID id,
            UUID wikiArticleId,
            UUID userId,
            WikiAppreciationScore score,
            Instant createdAt,
            Instant updatedAt
    ) {
        return new WikiAppreciationRating(
                id,
                wikiArticleId,
                userId,
                score,
                createdAt,
                updatedAt
        );
    }

    /**
     * Cập nhật điểm đánh giá mức độ yêu thích.
     *
     * @param newScore điểm đánh giá mới
     * @param now thời điểm cập nhật
     * @return true nếu điểm thực sự thay đổi, false nếu cùng giá trị (no-op)
     */
    public boolean updateScore(WikiAppreciationScore newScore, Instant now) {
        Objects.requireNonNull(newScore, "WikiAppreciationScore không được để trống.");
        Objects.requireNonNull(now, "Thời gian cập nhật không được để trống.");

        if (Objects.equals(this.score, newScore)) {
            return false;
        }

        this.score = newScore;
        this.updatedAt = now;
        return true;
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

    public WikiAppreciationScore getScore() {
        return score;
    }

    public int getValue() {
        return score.halfStarUnits();
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
                ", score=" + score +
                ", createdAt=" + createdAt +
                ", updatedAt=" + updatedAt +
                '}';
    }
}
