package com.universe.screen.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class ScreenTitleModelSpeculativeIntegrityTest {

    private static final Set<String> FORBIDDEN_SPECULATIVE_TOKENS = Set.of(
            "previoustitleid",
            "nexttitleid",
            "timelineorder",
            "releaseorder",
            "franchiserole",
            "displaygroup",
            "seasonchain",
            "episodes"
    );

    @Test
    @DisplayName("Locked Domain: ScreenTitle does not introduce speculative relationship or ordering fields/methods")
    void verifyScreenTitleDoesNotContainSpeculativeFieldsOrMethods() {
        Set<String> fieldNames = Arrays.stream(ScreenTitle.class.getDeclaredFields())
                .map(Field::getName)
                .map(String::toLowerCase)
                .collect(Collectors.toSet());

        Set<String> methodNames = Arrays.stream(ScreenTitle.class.getDeclaredMethods())
                .map(Method::getName)
                .map(String::toLowerCase)
                .collect(Collectors.toSet());

        for (String token : FORBIDDEN_SPECULATIVE_TOKENS) {
            assertThat(fieldNames)
                    .as("ScreenTitle must not declare speculative field containing " + token)
                    .noneMatch(name -> name.contains(token));

            assertThat(methodNames)
                    .as("ScreenTitle must not declare speculative method containing " + token)
                    .noneMatch(name -> name.contains(token));
        }
    }

    @Test
    @DisplayName("Locked Domain: ScreenStructure is locked to exactly SERIES and FEATURE")
    void verifyScreenStructureIsLocked() {
        assertThat(ScreenStructure.values())
                .containsExactlyInAnyOrder(ScreenStructure.SERIES, ScreenStructure.FEATURE);
    }

    @Test
    @DisplayName("Locked Domain: ScreenPresentation is locked to exactly ANIMATION_3D and LIVE_ACTION")
    void verifyScreenPresentationIsLocked() {
        assertThat(ScreenPresentation.values())
                .containsExactlyInAnyOrder(ScreenPresentation.ANIMATION_3D, ScreenPresentation.LIVE_ACTION);
    }

    @Test
    @DisplayName("Locked Domain: ScreenPublicationStatus is locked to exactly DRAFT, PUBLISHED, and ARCHIVED")
    void verifyScreenPublicationStatusIsLocked() {
        assertThat(ScreenPublicationStatus.values())
                .containsExactlyInAnyOrder(ScreenPublicationStatus.DRAFT, ScreenPublicationStatus.PUBLISHED, ScreenPublicationStatus.ARCHIVED);
    }
}
