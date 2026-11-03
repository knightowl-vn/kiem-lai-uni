package com.universe.screen.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ScreenMetadataValidatorTest {

    @Test
    @DisplayName("normalizeOptionalText: trims whitespace and returns null for blank strings")
    void shouldNormalizeOptionalText() {
        assertThat(ScreenMetadataValidator.normalizeOptionalText(null)).isNull();
        assertThat(ScreenMetadataValidator.normalizeOptionalText("")).isNull();
        assertThat(ScreenMetadataValidator.normalizeOptionalText("   ")).isNull();
        assertThat(ScreenMetadataValidator.normalizeOptionalText("  hello world  ")).isEqualTo("hello world");
    }

    @Test
    @DisplayName("validateAiringStatus: accepts null candidate")
    void shouldAcceptNullAiringStatus() {
        assertThat(ScreenMetadataValidator.validateAiringStatus(ScreenStructure.SERIES, null)).isNull();
        assertThat(ScreenMetadataValidator.validateAiringStatus(ScreenStructure.FEATURE, null)).isNull();
    }

    @Test
    @DisplayName("validateAiringStatus: rejects null structure when candidate is present")
    void shouldRejectNullStructureWhenAiringStatusPresent() {
        assertThatThrownBy(() -> ScreenMetadataValidator.validateAiringStatus(null, ScreenAiringStatus.ONGOING))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Cấu trúc tác phẩm không được để trống");
    }

    @ParameterizedTest
    @EnumSource(value = ScreenAiringStatus.class, names = {"UPCOMING", "ONGOING", "COMPLETED"})
    @DisplayName("validateAiringStatus: accepts series compatible statuses")
    void shouldAcceptSeriesCompatibleStatuses(ScreenAiringStatus status) {
        assertThat(ScreenMetadataValidator.validateAiringStatus(ScreenStructure.SERIES, status)).isEqualTo(status);
    }

    @Test
    @DisplayName("validateAiringStatus: rejects RELEASED for SERIES")
    void shouldRejectReleasedForSeries() {
        assertThatThrownBy(() -> ScreenMetadataValidator.validateAiringStatus(ScreenStructure.SERIES, ScreenAiringStatus.RELEASED))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("không tương thích");
    }

    @ParameterizedTest
    @EnumSource(value = ScreenAiringStatus.class, names = {"UPCOMING", "RELEASED"})
    @DisplayName("validateAiringStatus: accepts feature compatible statuses")
    void shouldAcceptFeatureCompatibleStatuses(ScreenAiringStatus status) {
        assertThat(ScreenMetadataValidator.validateAiringStatus(ScreenStructure.FEATURE, status)).isEqualTo(status);
    }

    @ParameterizedTest
    @EnumSource(value = ScreenAiringStatus.class, names = {"ONGOING", "COMPLETED"})
    @DisplayName("validateAiringStatus: rejects ongoing and completed for FEATURE")
    void shouldRejectOngoingAndCompletedForFeature(ScreenAiringStatus status) {
        assertThatThrownBy(() -> ScreenMetadataValidator.validateAiringStatus(ScreenStructure.FEATURE, status))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("không tương thích");
    }

    @Test
    @DisplayName("validateReleaseYear: accepts valid bounds and null")
    void shouldAcceptValidReleaseYear() {
        assertThat(ScreenMetadataValidator.validateReleaseYear(null)).isNull();
        assertThat(ScreenMetadataValidator.validateReleaseYear(1900)).isEqualTo(1900);
        assertThat(ScreenMetadataValidator.validateReleaseYear(2024)).isEqualTo(2024);
        assertThat(ScreenMetadataValidator.validateReleaseYear(2100)).isEqualTo(2100);
    }

    @Test
    @DisplayName("validateReleaseYear: rejects year below 1900 or above 2100")
    void shouldRejectInvalidReleaseYear() {
        assertThatThrownBy(() -> ScreenMetadataValidator.validateReleaseYear(1899))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("1900 đến 2100");

        assertThatThrownBy(() -> ScreenMetadataValidator.validateReleaseYear(2101))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("1900 đến 2100");
    }

    @Test
    @DisplayName("validateCountry: enforces max length 100")
    void shouldValidateCountryLength() {
        assertThat(ScreenMetadataValidator.validateCountry(null)).isNull();
        assertThat(ScreenMetadataValidator.validateCountry("   ")).isNull();
        assertThat(ScreenMetadataValidator.validateCountry("Việt Nam")).isEqualTo("Việt Nam");
        assertThat(ScreenMetadataValidator.validateCountry("A".repeat(100))).isEqualTo("A".repeat(100));

        assertThatThrownBy(() -> ScreenMetadataValidator.validateCountry("A".repeat(101)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("100 ký tự");
    }

    @Test
    @DisplayName("validateOriginalAuthor: enforces max length 255")
    void shouldValidateOriginalAuthorLength() {
        assertThat(ScreenMetadataValidator.validateOriginalAuthor(null)).isNull();
        assertThat(ScreenMetadataValidator.validateOriginalAuthor("   ")).isNull();
        assertThat(ScreenMetadataValidator.validateOriginalAuthor("Tác giả")).isEqualTo("Tác giả");
        assertThat(ScreenMetadataValidator.validateOriginalAuthor("B".repeat(255))).isEqualTo("B".repeat(255));

        assertThatThrownBy(() -> ScreenMetadataValidator.validateOriginalAuthor("B".repeat(256)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("255 ký tự");
    }

    @Test
    @DisplayName("validateDirector: enforces max length 255")
    void shouldValidateDirectorLength() {
        assertThat(ScreenMetadataValidator.validateDirector(null)).isNull();
        assertThat(ScreenMetadataValidator.validateDirector("   ")).isNull();
        assertThat(ScreenMetadataValidator.validateDirector("Đạo diễn")).isEqualTo("Đạo diễn");
        assertThat(ScreenMetadataValidator.validateDirector("C".repeat(255))).isEqualTo("C".repeat(255));

        assertThatThrownBy(() -> ScreenMetadataValidator.validateDirector("C".repeat(256)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("255 ký tự");
    }

    @Test
    @DisplayName("validateProductionStudio: enforces max length 255")
    void shouldValidateProductionStudioLength() {
        assertThat(ScreenMetadataValidator.validateProductionStudio(null)).isNull();
        assertThat(ScreenMetadataValidator.validateProductionStudio("   ")).isNull();
        assertThat(ScreenMetadataValidator.validateProductionStudio("Studio")).isEqualTo("Studio");
        assertThat(ScreenMetadataValidator.validateProductionStudio("D".repeat(255))).isEqualTo("D".repeat(255));

        assertThatThrownBy(() -> ScreenMetadataValidator.validateProductionStudio("D".repeat(256)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("255 ký tự");
    }
}
