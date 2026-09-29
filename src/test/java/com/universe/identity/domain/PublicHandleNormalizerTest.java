package com.universe.identity.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class PublicHandleNormalizerTest {

    private static final UUID TEST_USER_ID = UUID.fromString("12345678-1234-1234-1234-123456789abc");

    @Test
    @DisplayName("Chuẩn hóa display name tiếng Việt có dấu")
    void shouldNormalizeVietnameseDisplayName() {
        String candidate = PublicHandleNormalizer.generateCandidate("Nguyễn Văn A", TEST_USER_ID);
        assertThat(candidate).isEqualTo("nguyen_van_a");
        assertThat(PublicHandleNormalizer.isValidHandle(candidate)).isTrue();
    }

    @Test
    @DisplayName("Loại bỏ ký tự @ ở đầu nếu có")
    void shouldStripAtPrefix() {
        String candidate = PublicHandleNormalizer.generateCandidate("@Athena_Dev", TEST_USER_ID);
        assertThat(candidate).isEqualTo("athena_dev");
        assertThat(PublicHandleNormalizer.isValidHandle(candidate)).isTrue();
    }

    @Test
    @DisplayName("Thay thế ký tự đặc biệt và thu gọn gạch dưới liên tiếp")
    void shouldReplaceSpecialCharsAndCollapseUnderscores() {
        String candidate = PublicHandleNormalizer.generateCandidate("Hello---World...2026!!!", TEST_USER_ID);
        assertThat(candidate).isEqualTo("hello_world_2026");
        assertThat(PublicHandleNormalizer.isValidHandle(candidate)).isTrue();
    }

    @Test
    @DisplayName("Cắt độ dài tối đa 40 ký tự")
    void shouldTruncateTo40Chars() {
        String longName = "this_is_a_very_long_display_name_that_exceeds_forty_characters_limit";
        String candidate = PublicHandleNormalizer.generateCandidate(longName, TEST_USER_ID);
        assertThat(candidate.length()).isLessThanOrEqualTo(40);
        assertThat(candidate).isEqualTo("this_is_a_very_long_display_name_that_ex");
        assertThat(PublicHandleNormalizer.isValidHandle(candidate)).isTrue();
    }

    @Test
    @DisplayName("Fallback khi display name rỗng hoặc chỉ toàn ký tự đặc biệt/emoji")
    void shouldFallbackWhenDisplayNameIsEmptyOrOnlySpecialCharacters() {
        String candidate1 = PublicHandleNormalizer.generateCandidate("   ", TEST_USER_ID);
        assertThat(candidate1).startsWith("user_");
        assertThat(PublicHandleNormalizer.isValidHandle(candidate1)).isTrue();

        String candidate2 = PublicHandleNormalizer.generateCandidate("😊🔥🚀", TEST_USER_ID);
        assertThat(candidate2).startsWith("user_");
        assertThat(PublicHandleNormalizer.isValidHandle(candidate2)).isTrue();

        String candidate3 = PublicHandleNormalizer.generateCandidate(null, TEST_USER_ID);
        assertThat(candidate3).startsWith("user_");
        assertThat(PublicHandleNormalizer.isValidHandle(candidate3)).isTrue();
    }

    @Test
    @DisplayName("Fallback khi display name chuẩn hóa ngắn hơn 3 ký tự")
    void shouldFallbackWhenNormalizedLengthLessThan3() {
        String candidate = PublicHandleNormalizer.generateCandidate("A!", TEST_USER_ID);
        assertThat(candidate).startsWith("user_");
        assertThat(PublicHandleNormalizer.isValidHandle(candidate)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"abc", "user_123", "athena_master", "u_12345678123412341234123456789abc"})
    @DisplayName("Kiểm tra handle hợp lệ")
    void shouldValidateValidHandles(String handle) {
        assertThat(PublicHandleNormalizer.isValidHandle(handle)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "a", "ab", "user@name", "user-name", "User_123", "user.name", "user name"})
    @DisplayName("Kiểm tra handle không hợp lệ")
    void shouldRejectInvalidHandles(String handle) {
        assertThat(PublicHandleNormalizer.isValidHandle(handle)).isFalse();
    }
}
