package com.universe.wiki.domain.appreciation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

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
    @ValueSource(ints = {1, 2, 3, 4, 5})
    @DisplayName("Khởi tạo thành công với giá trị hợp lệ 1 đến 5 sao")
    void shouldCreateWithValidValues(int validValue) {
        WikiAppreciationRating rating = WikiAppreciationRating.create(
                ID,
                ARTICLE_ID,
                USER_ID,
                validValue,
                CREATED_AT
        );

        assertThat(rating).isNotNull();
        assertThat(rating.getId()).isEqualTo(ID);
        assertThat(rating.getWikiArticleId()).isEqualTo(ARTICLE_ID);
        assertThat(rating.getUserId()).isEqualTo(USER_ID);
        assertThat(rating.getValue()).isEqualTo(validValue);
        assertThat(rating.getCreatedAt()).isEqualTo(CREATED_AT);
        assertThat(rating.getUpdatedAt()).isEqualTo(CREATED_AT);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 6, -1, 100})
    @DisplayName("Từ chối khởi tạo với giá trị ngoài khoảng [1..5]")
    void shouldRejectInvalidValuesOnCreate(int invalidValue) {
        assertThatThrownBy(() -> WikiAppreciationRating.create(
                ID,
                ARTICLE_ID,
                USER_ID,
                invalidValue,
                CREATED_AT
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Giá trị đánh giá phải nằm trong khoảng từ 1 đến 5");
    }

    @Test
    @DisplayName("Từ chối khởi tạo khi có trường định danh hoặc thời gian bị null")
    void shouldRejectNullIdentifiersOnCreate() {
        assertThatThrownBy(() -> WikiAppreciationRating.create(null, ARTICLE_ID, USER_ID, 5, CREATED_AT))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("ID đánh giá không được để trống.");

        assertThatThrownBy(() -> WikiAppreciationRating.create(ID, null, USER_ID, 5, CREATED_AT))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("ID bài viết Wiki không được để trống.");

        assertThatThrownBy(() -> WikiAppreciationRating.create(ID, ARTICLE_ID, null, 5, CREATED_AT))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("ID người dùng không được để trống.");

        assertThatThrownBy(() -> WikiAppreciationRating.create(ID, ARTICLE_ID, USER_ID, 5, null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("Thời gian tạo không được để trống.");
    }

    @Test
    @DisplayName("Khôi phục (rehydrate) hợp lệ từ tầng lưu trữ")
    void shouldRehydrateValidState() {
        Instant updatedAt = CREATED_AT.plusSeconds(300);
        WikiAppreciationRating rehydrated = WikiAppreciationRating.rehydrate(
                ID,
                ARTICLE_ID,
                USER_ID,
                4,
                CREATED_AT,
                updatedAt
        );

        assertThat(rehydrated).isNotNull();
        assertThat(rehydrated.getId()).isEqualTo(ID);
        assertThat(rehydrated.getWikiArticleId()).isEqualTo(ARTICLE_ID);
        assertThat(rehydrated.getUserId()).isEqualTo(USER_ID);
        assertThat(rehydrated.getValue()).isEqualTo(4);
        assertThat(rehydrated.getCreatedAt()).isEqualTo(CREATED_AT);
        assertThat(rehydrated.getUpdatedAt()).isEqualTo(updatedAt);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 6, -5, 99})
    @DisplayName("Từ chối rehydrate với giá trị không hợp lệ từ DB, không tự động sửa sai")
    void shouldRejectInvalidValueOnRehydrate(int invalidValue) {
        Instant updatedAt = CREATED_AT.plusSeconds(300);
        assertThatThrownBy(() -> WikiAppreciationRating.rehydrate(
                ID,
                ARTICLE_ID,
                USER_ID,
                invalidValue,
                CREATED_AT,
                updatedAt
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Giá trị đánh giá phải nằm trong khoảng từ 1 đến 5");
    }

    @Test
    @DisplayName("Từ chối rehydrate khi thiếu trường bắt buộc")
    void shouldRejectNullFieldsOnRehydrate() {
        Instant updatedAt = CREATED_AT.plusSeconds(300);

        assertThatThrownBy(() -> WikiAppreciationRating.rehydrate(null, ARTICLE_ID, USER_ID, 5, CREATED_AT, updatedAt))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("ID đánh giá không được để trống.");

        assertThatThrownBy(() -> WikiAppreciationRating.rehydrate(ID, null, USER_ID, 5, CREATED_AT, updatedAt))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("ID bài viết Wiki không được để trống.");

        assertThatThrownBy(() -> WikiAppreciationRating.rehydrate(ID, ARTICLE_ID, null, 5, CREATED_AT, updatedAt))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("ID người dùng không được để trống.");

        assertThatThrownBy(() -> WikiAppreciationRating.rehydrate(ID, ARTICLE_ID, USER_ID, 5, null, updatedAt))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("Thời gian tạo không được để trống.");

        assertThatThrownBy(() -> WikiAppreciationRating.rehydrate(ID, ARTICLE_ID, USER_ID, 5, CREATED_AT, null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("Thời gian cập nhật không được để trống.");
    }

    @Test
    @DisplayName("Cập nhật giá trị khác (3 -> 5): value đổi, createdAt giữ nguyên, updatedAt cập nhật, trả về true")
    void shouldUpdateValueWhenDifferent() {
        WikiAppreciationRating rating = WikiAppreciationRating.create(
                ID,
                ARTICLE_ID,
                USER_ID,
                3,
                CREATED_AT
        );

        Instant updateTime = CREATED_AT.plusSeconds(600);
        boolean changed = rating.updateValue(5, updateTime);

        assertThat(changed).isTrue();
        assertThat(rating.getValue()).isEqualTo(5);
        assertThat(rating.getCreatedAt()).isEqualTo(CREATED_AT);
        assertThat(rating.getUpdatedAt()).isEqualTo(updateTime);
    }

    @Test
    @DisplayName("Cập nhật cùng giá trị (5 -> 5): no-op, value giữ nguyên, updatedAt giữ nguyên, trả về false")
    void shouldNotUpdateWhenSameValue() {
        WikiAppreciationRating rating = WikiAppreciationRating.create(
                ID,
                ARTICLE_ID,
                USER_ID,
                5,
                CREATED_AT
        );

        Instant updateTime = CREATED_AT.plusSeconds(600);
        boolean changed = rating.updateValue(5, updateTime);

        assertThat(changed).isFalse();
        assertThat(rating.getValue()).isEqualTo(5);
        assertThat(rating.getCreatedAt()).isEqualTo(CREATED_AT);
        assertThat(rating.getUpdatedAt()).isEqualTo(CREATED_AT);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 6, -1, 10})
    @DisplayName("Từ chối cập nhật với giá trị không hợp lệ")
    void shouldRejectInvalidValueOnUpdate(int invalidValue) {
        WikiAppreciationRating rating = WikiAppreciationRating.create(
                ID,
                ARTICLE_ID,
                USER_ID,
                3,
                CREATED_AT
        );

        Instant updateTime = CREATED_AT.plusSeconds(600);
        assertThatThrownBy(() -> rating.updateValue(invalidValue, updateTime))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Giá trị đánh giá phải nằm trong khoảng từ 1 đến 5");

        // Trạng thái không bị thay đổi
        assertThat(rating.getValue()).isEqualTo(3);
        assertThat(rating.getUpdatedAt()).isEqualTo(CREATED_AT);
    }

    @Test
    @DisplayName("Từ chối cập nhật khi updateTime bị null")
    void shouldRejectNullUpdateTimeOnUpdate() {
        WikiAppreciationRating rating = WikiAppreciationRating.create(
                ID,
                ARTICLE_ID,
                USER_ID,
                3,
                CREATED_AT
        );

        assertThatThrownBy(() -> rating.updateValue(4, null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("Thời gian cập nhật không được để trống.");
    }

    @Test
    @DisplayName("Kiểm tra equals và hashCode dựa trên id")
    void shouldImplementEqualsAndHashCodeBasedOnId() {
        WikiAppreciationRating rating1 = WikiAppreciationRating.create(ID, ARTICLE_ID, USER_ID, 3, CREATED_AT);
        WikiAppreciationRating rating2 = WikiAppreciationRating.rehydrate(ID, ARTICLE_ID, USER_ID, 5, CREATED_AT, CREATED_AT.plusSeconds(10));
        WikiAppreciationRating rating3 = WikiAppreciationRating.create(UUID.randomUUID(), ARTICLE_ID, USER_ID, 3, CREATED_AT);

        assertThat(rating1).isEqualTo(rating2);
        assertThat(rating1.hashCode()).isEqualTo(rating2.hashCode());
        assertThat(rating1).isNotEqualTo(rating3);
    }
}
