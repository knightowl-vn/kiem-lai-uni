package com.universe.media.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ImageVariantSpecTest {

    @Test
    @DisplayName("creates valid ImageVariantSpec and derives canonical variant key")
    void shouldCreateValidSpecAndDeriveCanonicalKey() {
        ImageVariantSpec spec = ImageVariantSpec.of(300);

        assertThat(spec.targetWidth()).isEqualTo(300);
        assertThat(spec.variantKey()).isEqualTo("w300");
    }

    @Test
    @DisplayName("derives canonical variant key for large width")
    void shouldDeriveCanonicalKeyForLargeWidth() {
        ImageVariantSpec spec = ImageVariantSpec.of(800);

        assertThat(spec.targetWidth()).isEqualTo(800);
        assertThat(spec.variantKey()).isEqualTo("w800");
    }

    @ParameterizedTest
    @ValueSource(ints = {16, 100, 300, 800, 1920, 3840, 7680})
    @DisplayName("accepts widths within valid range [16, 7680]")
    void shouldAcceptWidthsWithinValidRange(int width) {
        ImageVariantSpec spec = ImageVariantSpec.of(width);

        assertThat(spec.targetWidth()).isEqualTo(width);
        assertThat(spec.variantKey()).isEqualTo("w" + width);
    }

    @ParameterizedTest
    @ValueSource(ints = {-100, -1, 0, 1, 15, 7681, 10000})
    @DisplayName("rejects widths outside valid range [16, 7680]")
    void shouldRejectWidthsOutsideValidRange(int invalidWidth) {
        assertThatThrownBy(() -> ImageVariantSpec.of(invalidWidth))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("targetWidth must be between 16 and 7680 pixels");
    }

    @Test
    @DisplayName("satisfies value object equality and hash code")
    void shouldSatisfyEqualityAndHashCode() {
        ImageVariantSpec spec1 = ImageVariantSpec.of(300);
        ImageVariantSpec spec2 = ImageVariantSpec.of(300);
        ImageVariantSpec spec3 = ImageVariantSpec.of(800);

        assertThat(spec1).isEqualTo(spec2);
        assertThat(spec1.hashCode()).isEqualTo(spec2.hashCode());
        assertThat(spec1).isNotEqualTo(spec3);
    }
}
