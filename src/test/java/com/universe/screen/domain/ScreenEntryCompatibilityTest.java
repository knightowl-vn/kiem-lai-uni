package com.universe.screen.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("ScreenEntryCompatibility Pure Domain Policy Tests")
class ScreenEntryCompatibilityTest {

    @ParameterizedTest(name = "Structure {0} with EntryType {1} isCompatible = {2}")
    @CsvSource({
            "SERIES, EPISODE, true",
            "SERIES, SPECIAL, true",
            "SERIES, TRAILER, true",
            "SERIES, FEATURE, false",
            "FEATURE, FEATURE, true",
            "FEATURE, SPECIAL, true",
            "FEATURE, TRAILER, true",
            "FEATURE, EPISODE, false"
    })
    @DisplayName("Tests complete compatibility truth matrix between ScreenStructure and ScreenEntryType")
    void shouldEvaluateCompatibilityMatrix(
            ScreenStructure structure,
            ScreenEntryType entryType,
            boolean expectedCompatibility
    ) {
        assertThat(ScreenEntryCompatibility.isCompatible(structure, entryType))
                .isEqualTo(expectedCompatibility);
    }

    @Test
    @DisplayName("SERIES: validates EPISODE, SPECIAL, and TRAILER successfully")
    void shouldValidateSeriesAllowedEntryTypes() {
        assertThatCode(() -> ScreenEntryCompatibility.validateCompatibility(ScreenStructure.SERIES, ScreenEntryType.EPISODE))
                .doesNotThrowAnyException();
        assertThatCode(() -> ScreenEntryCompatibility.validateCompatibility(ScreenStructure.SERIES, ScreenEntryType.SPECIAL))
                .doesNotThrowAnyException();
        assertThatCode(() -> ScreenEntryCompatibility.validateCompatibility(ScreenStructure.SERIES, ScreenEntryType.TRAILER))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("SERIES: rejects FEATURE entry type")
    void shouldRejectFeatureInSeries() {
        assertThatThrownBy(() -> ScreenEntryCompatibility.validateCompatibility(ScreenStructure.SERIES, ScreenEntryType.FEATURE))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ScreenEntryType FEATURE is incompatible with ScreenStructure SERIES");
    }

    @Test
    @DisplayName("FEATURE: validates FEATURE, SPECIAL, and TRAILER successfully")
    void shouldValidateFeatureAllowedEntryTypes() {
        assertThatCode(() -> ScreenEntryCompatibility.validateCompatibility(ScreenStructure.FEATURE, ScreenEntryType.FEATURE))
                .doesNotThrowAnyException();
        assertThatCode(() -> ScreenEntryCompatibility.validateCompatibility(ScreenStructure.FEATURE, ScreenEntryType.SPECIAL))
                .doesNotThrowAnyException();
        assertThatCode(() -> ScreenEntryCompatibility.validateCompatibility(ScreenStructure.FEATURE, ScreenEntryType.TRAILER))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("FEATURE: rejects EPISODE entry type (movie must not be modeled as fake episode)")
    void shouldRejectEpisodeInFeature() {
        assertThatThrownBy(() -> ScreenEntryCompatibility.validateCompatibility(ScreenStructure.FEATURE, ScreenEntryType.EPISODE))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ScreenEntryType EPISODE is incompatible with ScreenStructure FEATURE");
    }

    @Test
    @DisplayName("Rejects null arguments in compatibility checks")
    void shouldRejectNullArguments() {
        assertThatThrownBy(() -> ScreenEntryCompatibility.isCompatible(null, ScreenEntryType.EPISODE))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("ScreenStructure cannot be null");

        assertThatThrownBy(() -> ScreenEntryCompatibility.isCompatible(ScreenStructure.SERIES, null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("ScreenEntryType cannot be null");

        assertThatThrownBy(() -> ScreenEntryCompatibility.validateCompatibility(null, ScreenEntryType.EPISODE))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("ScreenStructure cannot be null");

        assertThatThrownBy(() -> ScreenEntryCompatibility.validateCompatibility(ScreenStructure.SERIES, null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("ScreenEntryType cannot be null");
    }
}
