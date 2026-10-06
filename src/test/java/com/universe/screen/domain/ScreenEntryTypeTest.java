package com.universe.screen.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("ScreenEntryType Enum Tests")
class ScreenEntryTypeTest {

    @Test
    @DisplayName("ScreenEntryType contains exactly EPISODE, FEATURE, SPECIAL, and TRAILER")
    void shouldHaveExactScreenEntryTypeValues() {
        assertThat(ScreenEntryType.values())
                .hasSize(4)
                .containsExactlyInAnyOrder(
                        ScreenEntryType.EPISODE,
                        ScreenEntryType.FEATURE,
                        ScreenEntryType.SPECIAL,
                        ScreenEntryType.TRAILER
                );
    }

    @ParameterizedTest(name = "Type {0} compatibility with {1} should be {2}")
    @CsvSource({
            "EPISODE, SERIES, true",
            "EPISODE, FEATURE, false",
            "FEATURE, SERIES, false",
            "FEATURE, FEATURE, true",
            "SPECIAL, SERIES, true",
            "SPECIAL, FEATURE, true",
            "TRAILER, SERIES, true",
            "TRAILER, FEATURE, true"
    })
    @DisplayName("Tests isCompatibleWith helper for each entry type and structure")
    void shouldVerifyCompatibilityHelper(
            ScreenEntryType type,
            ScreenStructure structure,
            boolean expectedCompatibility
    ) {
        assertThat(type.isCompatibleWith(structure)).isEqualTo(expectedCompatibility);
    }
}
