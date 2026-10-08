package com.universe.screen.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class ScreenFranchiseModelSpeculativeIntegrityTest {

    private static final Set<String> FORBIDDEN_SPECULATIVE_TOKENS = Set.of(
            "previoustitleid",
            "nexttitleid",
            "timelineorder",
            "releaseorder",
            "franchiserole",
            "displaygroup",
            "seasonchain"
    );

    @Test
    @DisplayName("Locked Domain: ScreenFranchise does not introduce speculative relationship models")
    void verifyScreenFranchiseDoesNotContainSpeculativeFieldsOrMethods() {
        assertNoSpeculativeMembers(ScreenFranchise.class);
    }

    @Test
    @DisplayName("Locked Domain: ScreenFranchiseMembership does not introduce speculative relationship models")
    void verifyScreenFranchiseMembershipDoesNotContainSpeculativeFieldsOrMethods() {
        assertNoSpeculativeMembers(ScreenFranchiseMembership.class);
    }

    private void assertNoSpeculativeMembers(Class<?> clazz) {
        Set<String> fieldNames = Arrays.stream(clazz.getDeclaredFields())
                .map(Field::getName)
                .map(String::toLowerCase)
                .collect(Collectors.toSet());

        Set<String> methodNames = Arrays.stream(clazz.getDeclaredMethods())
                .map(Method::getName)
                .map(String::toLowerCase)
                .collect(Collectors.toSet());

        for (String token : FORBIDDEN_SPECULATIVE_TOKENS) {
            assertThat(fieldNames)
                    .as("Class " + clazz.getSimpleName() + " must not declare speculative field containing " + token)
                    .noneMatch(name -> name.contains(token));

            assertThat(methodNames)
                    .as("Class " + clazz.getSimpleName() + " must not declare speculative method containing " + token)
                    .noneMatch(name -> name.contains(token));
        }
    }
}
