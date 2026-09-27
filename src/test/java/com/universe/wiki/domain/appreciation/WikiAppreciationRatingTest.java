package com.universe.wiki.domain.appreciation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("WikiAppreciationRating Domain Aggregate Tests")
class WikiAppreciationRatingTest {

    private static final UUID ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID ARTICLE_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID USER_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final Instant CREATED_AT = Instant.parse("2026-09-22T10:00:00Z");

    @ParameterizedTest
    @ValueSource(ints = {2, 3, 4, 5, 6, 7, 8, 9, 10})
    @DisplayName("Khởi tạo thành công với các đơn vị nửa sao hợp lệ 2 đến 10")
    void shouldCreateWithValidValues(int validUnits) {
        WikiAppreciationScore score = WikiAppreciationScore.fromHalfStarUnits(validUnits);
        WikiAppreciationRating rating = WikiAppreciationRating.create(
                ID,
                ARTICLE_ID,
                USER_ID,
                score,
                CREATED_AT
        );

        assertThat(rating).isNotNull();
        assertThat(rating.getId()).isEqualTo(ID);
        assertThat(rating.getWikiArticleId()).isEqualTo(ARTICLE_ID);
        assertThat(rating.getUserId()).isEqualTo(USER_ID);
        assertThat(rating.getScore()).isEqualTo(score);
        assertThat(rating.getValue()).isEqualTo(validUnits);
        assertThat(rating.getCreatedAt()).isEqualTo(CREATED_AT);
        assertThat(rating.getUpdatedAt()).isEqualTo(CREATED_AT);
    }

    @Test
    @DisplayName("Từ chối khởi tạo khi có trường định danh, score hoặc thời gian bị null")
    void shouldRejectNullFieldsOnCreate() {
        WikiAppreciationScore score = WikiAppreciationScore.fromHalfStarUnits(10);

        assertThatThrownBy(() -> WikiAppreciationRating.create(null, ARTICLE_ID, USER_ID, score, CREATED_AT))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("ID đánh giá không được để trống.");

        assertThatThrownBy(() -> WikiAppreciationRating.create(ID, null, USER_ID, score, CREATED_AT))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("ID bài viết Wiki không được để trống.");

        assertThatThrownBy(() -> WikiAppreciationRating.create(ID, ARTICLE_ID, null, score, CREATED_AT))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("ID người dùng không được để trống.");

        assertThatThrownBy(() -> WikiAppreciationRating.create(ID, ARTICLE_ID, USER_ID, null, CREATED_AT))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("WikiAppreciationScore không được để trống.");

        assertThatThrownBy(() -> WikiAppreciationRating.create(ID, ARTICLE_ID, USER_ID, score, null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("Thời gian tạo không được để trống.");
    }

    @Test
    @DisplayName("Khôi phục (rehydrate) hợp lệ từ tầng lưu trữ")
    void shouldRehydrateValidState() {
        Instant updatedAt = CREATED_AT.plusSeconds(300);
        WikiAppreciationScore score = WikiAppreciationScore.fromStars(new BigDecimal("4.5"));
        WikiAppreciationRating rehydrated = WikiAppreciationRating.rehydrate(
                ID,
                ARTICLE_ID,
                USER_ID,
                score,
                CREATED_AT,
                updatedAt
        );

        assertThat(rehydrated).isNotNull();
        assertThat(rehydrated.getId()).isEqualTo(ID);
        assertThat(rehydrated.getWikiArticleId()).isEqualTo(ARTICLE_ID);
        assertThat(rehydrated.getUserId()).isEqualTo(USER_ID);
        assertThat(rehydrated.getScore()).isEqualTo(score);
        assertThat(rehydrated.getValue()).isEqualTo(9);
        assertThat(rehydrated.getCreatedAt()).isEqualTo(CREATED_AT);
        assertThat(rehydrated.getUpdatedAt()).isEqualTo(updatedAt);
    }

    @Test
    @DisplayName("Từ chối rehydrate khi thiếu trường bắt buộc")
    void shouldRejectNullFieldsOnRehydrate() {
        Instant updatedAt = CREATED_AT.plusSeconds(300);
        WikiAppreciationScore score = WikiAppreciationScore.fromHalfStarUnits(10);

        assertThatThrownBy(() -> WikiAppreciationRating.rehydrate(null, ARTICLE_ID, USER_ID, score, CREATED_AT, updatedAt))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("ID đánh giá không được để trống.");

        assertThatThrownBy(() -> WikiAppreciationRating.rehydrate(ID, null, USER_ID, score, CREATED_AT, updatedAt))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("ID bài viết Wiki không được để trống.");

        assertThatThrownBy(() -> WikiAppreciationRating.rehydrate(ID, ARTICLE_ID, null, score, CREATED_AT, updatedAt))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("ID người dùng không được để trống.");

        assertThatThrownBy(() -> WikiAppreciationRating.rehydrate(ID, ARTICLE_ID, USER_ID, null, CREATED_AT, updatedAt))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("WikiAppreciationScore không được để trống.");

        assertThatThrownBy(() -> WikiAppreciationRating.rehydrate(ID, ARTICLE_ID, USER_ID, score, null, updatedAt))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("Thời gian tạo không được để trống.");

        assertThatThrownBy(() -> WikiAppreciationRating.rehydrate(ID, ARTICLE_ID, USER_ID, score, CREATED_AT, null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("Thời gian cập nhật không được để trống.");
    }

    @Test
    @DisplayName("Cập nhật điểm khác (3.5 -> 4.5): score đổi, createdAt giữ nguyên, updatedAt cập nhật, trả về true")
    void shouldUpdateScoreWhenDifferent() {
        WikiAppreciationScore initialScore = WikiAppreciationScore.fromStars(new BigDecimal("3.5"));
        WikiAppreciationRating rating = WikiAppreciationRating.create(
                ID,
                ARTICLE_ID,
                USER_ID,
                initialScore,
                CREATED_AT
        );

        Instant updateTime = CREATED_AT.plusSeconds(600);
        WikiAppreciationScore newScore = WikiAppreciationScore.fromStars(new BigDecimal("4.5"));
        boolean changed = rating.updateScore(newScore, updateTime);

        assertThat(changed).isTrue();
        assertThat(rating.getScore()).isEqualTo(newScore);
        assertThat(rating.getValue()).isEqualTo(9);
        assertThat(rating.getCreatedAt()).isEqualTo(CREATED_AT);
        assertThat(rating.getUpdatedAt()).isEqualTo(updateTime);
    }

    @Test
    @DisplayName("Cập nhật cùng điểm (4.5 -> 4.5): no-op, score giữ nguyên, updatedAt giữ nguyên, trả về false")
    void shouldNotUpdateWhenSameScore() {
        WikiAppreciationScore score = WikiAppreciationScore.fromStars(new BigDecimal("4.5"));
        WikiAppreciationRating rating = WikiAppreciationRating.create(
                ID,
                ARTICLE_ID,
                USER_ID,
                score,
                CREATED_AT
        );

        Instant updateTime = CREATED_AT.plusSeconds(600);
        WikiAppreciationScore sameScore = WikiAppreciationScore.fromHalfStarUnits(9);
        boolean changed = rating.updateScore(sameScore, updateTime);

        assertThat(changed).isFalse();
        assertThat(rating.getScore()).isEqualTo(score);
        assertThat(rating.getCreatedAt()).isEqualTo(CREATED_AT);
        assertThat(rating.getUpdatedAt()).isEqualTo(CREATED_AT);
    }

    @Test
    @DisplayName("Từ chối cập nhật khi score hoặc updateTime bị null")
    void shouldRejectNullArgumentsOnUpdate() {
        WikiAppreciationScore initialScore = WikiAppreciationScore.fromHalfStarUnits(6);
        WikiAppreciationRating rating = WikiAppreciationRating.create(
                ID,
                ARTICLE_ID,
                USER_ID,
                initialScore,
                CREATED_AT
        );

        Instant updateTime = CREATED_AT.plusSeconds(600);
        assertThatThrownBy(() -> rating.updateScore(null, updateTime))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("WikiAppreciationScore không được để trống.");

        assertThatThrownBy(() -> rating.updateScore(WikiAppreciationScore.fromHalfStarUnits(8), null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("Thời gian cập nhật không được để trống.");
    }

    @Test
    @DisplayName("Kiểm tra equals và hashCode dựa trên id")
    void shouldImplementEqualsAndHashCodeBasedOnId() {
        WikiAppreciationScore score1 = WikiAppreciationScore.fromHalfStarUnits(6);
        WikiAppreciationScore score2 = WikiAppreciationScore.fromHalfStarUnits(10);

        WikiAppreciationRating rating1 = WikiAppreciationRating.create(ID, ARTICLE_ID, USER_ID, score1, CREATED_AT);
        WikiAppreciationRating rating2 = WikiAppreciationRating.rehydrate(ID, ARTICLE_ID, USER_ID, score2, CREATED_AT, CREATED_AT.plusSeconds(10));
        WikiAppreciationRating rating3 = WikiAppreciationRating.create(UUID.randomUUID(), ARTICLE_ID, USER_ID, score1, CREATED_AT);

        assertThat(rating1).isEqualTo(rating2);
        assertThat(rating1.hashCode()).isEqualTo(rating2.hashCode());
        assertThat(rating1).isNotEqualTo(rating3);
    }
}
