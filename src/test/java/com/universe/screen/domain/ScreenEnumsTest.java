package com.universe.screen.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Screen Structure and Presentation Enums Tests")
class ScreenEnumsTest {

    @Test
    @DisplayName("ScreenStructure contains exactly SERIES and FEATURE")
    void shouldHaveExactScreenStructureValues() {
        assertThat(ScreenStructure.values())
                .containsExactlyInAnyOrder(
                        ScreenStructure.SERIES,
                        ScreenStructure.FEATURE
                );
    }

    @Test
    @DisplayName("ScreenPresentation contains exactly ANIMATION_3D and LIVE_ACTION")
    void shouldHaveExactScreenPresentationValues() {
        assertThat(ScreenPresentation.values())
                .containsExactlyInAnyOrder(
                        ScreenPresentation.ANIMATION_3D,
                        ScreenPresentation.LIVE_ACTION
                );
    }

    @Test
    @DisplayName("ScreenPublicationStatus contains exactly DRAFT, PUBLISHED, and ARCHIVED")
    void shouldHaveExactScreenPublicationStatusValues() {
        assertThat(ScreenPublicationStatus.values())
                .containsExactlyInAnyOrder(
                        ScreenPublicationStatus.DRAFT,
                        ScreenPublicationStatus.PUBLISHED,
                        ScreenPublicationStatus.ARCHIVED
                );
    }

    @Test
    @DisplayName("ScreenTimelineMode contains exactly INHERIT, OVERRIDE, and DISABLED")
    void shouldHaveExactScreenTimelineModeValues() {
        assertThat(ScreenTimelineMode.values())
                .containsExactlyInAnyOrder(
                        ScreenTimelineMode.INHERIT,
                        ScreenTimelineMode.OVERRIDE,
                        ScreenTimelineMode.DISABLED
                );
    }
}
