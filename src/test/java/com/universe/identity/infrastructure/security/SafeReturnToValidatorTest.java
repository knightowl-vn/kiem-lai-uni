package com.universe.identity.infrastructure.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class SafeReturnToValidatorTest {

    private SafeReturnToValidator validator;

    @BeforeEach
    void setUp() {
        validator = new SafeReturnToValidator();
    }

    @Test
    @DisplayName("valid user-facing public paths are accepted without feature allowlist")
    void validUserFacingPublicPathsAreAccepted() {
        assertThat(validator.isValid("/")).isTrue();
        assertThat(validator.isValid("/home")).isTrue();
        assertThat(validator.isValid("/novel")).isTrue();
        assertThat(validator.isValid("/novel/chapters/quyen-1-chuong-1")).isTrue();
        assertThat(validator.isValid("/wiki")).isTrue();
        assertThat(validator.isValid("/wiki/character/tran-binh-an")).isTrue();
        assertThat(validator.isValid("/donghua/example")).isTrue();
        assertThat(validator.isValid("/some-future-user-facing-page")).isTrue();
    }

    @Test
    @DisplayName("valid paths with complex nested query parameters or fragments are accepted")
    void validPathsWithComplexParametersOrFragmentsAreAccepted() {
        String complexTarget = "/novel/chapters/quyen-1-chuong-10"
                + "?discussionBlock=blk-a1b2c3d4e5f67890-3"
                + "&threadId=11111111-1111-1111-1111-111111111111"
                + "&replyTo=22222222-2222-2222-2222-222222222222"
                + "&intent=reply";
        assertThat(validator.isValid(complexTarget)).isTrue();
        assertThat(validator.isValid("/novel/chapters/chuong-1?time=12:30:00#reply-123")).isTrue();
        assertThat(validator.isValid("/wiki/character/tran-binh-an?tab=history#origin")).isTrue();
    }

    @Test
    @DisplayName("null, empty, and blank returnTo targets are rejected")
    void nullAndBlankTargetsAreRejected() {
        assertThat(validator.isValid(null)).isFalse();
        assertThat(validator.isValid("")).isFalse();
        assertThat(validator.isValid("   ")).isFalse();
        assertThat(validator.isValid("\t")).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "https://evil.example",
            "http://evil.example",
            "//evil.example",
            "//evil.example/novel/chapters/1",
            "//evil.example/wiki/character/tran-binh-an",
            "/\\evil.example",
            "\\\\evil.example",
            "javascript:alert(1)",
            "data:text/html,<script>alert(1)</script>",
            "mailto:user@example.com"
    })
    @DisplayName("external, scheme-relative, and dangerous scheme targets are rejected")
    void externalAndDangerousTargetsAreRejected(String target) {
        assertThat(validator.isValid(target)).isFalse();
    }

    @Test
    @DisplayName("CRLF and control characters are rejected to prevent HTTP response splitting")
    void controlCharactersAndCrlfAreRejected() {
        assertThat(validator.isValid("/novel/chapters/1\r\nSet-Cookie:malicious=true")).isFalse();
        assertThat(validator.isValid("/wiki/character/1\nInjected:header")).isFalse();
        assertThat(validator.isValid("/novel/chapters/1\0")).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "/api",
            "/api/test",
            "/api/novel/chapters",
            "/admin",
            "/admin/dashboard",
            "/admin/wiki",
            "/login",
            "/login/oauth2/code/google",
            "/register",
            "/logout",
            "/oauth2",
            "/oauth2/authorization/google",
            "/.well-known/appspecific/com.chrome.devtools.json",
            "/css/app.css",
            "/js/app.js",
            "/images/x.png",
            "/error",
            "/access-denied",
            "/favicon.ico"
    })
    @DisplayName("operational, auth, technical, and static destinations are rejected")
    void operationalAndTechnicalDestinationsAreRejected(String target) {
        assertThat(validator.isValid(target)).isFalse();
    }

    @Test
    @DisplayName("path traversal attempts to access denied destinations are rejected")
    void pathTraversalAttemptsAreRejected() {
        assertThat(validator.isValid("/novel/../admin")).isFalse();
        assertThat(validator.isValid("/home/../api/test")).isFalse();
        assertThat(validator.isValid("/..")).isFalse();
        assertThat(validator.isValid("/../evil")).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "/%5Cevil.example",
            "/%5cevil.example",
            "/%2F%2Fevil.example",
            "/%2f%2fevil.example",
            "/%5c%5cevil.example",
            "/%5C%2Fevil.example",
            "/novel/%5c../admin",
            "/novel/%2e%2e/admin",
            "/home/%2e%2e/api/test",
            "/novel/%2e%2e/%2e%2e/admin",
            "/%2e%2e",
            "/%2e%2e/evil",
            "/%2e%2e/login",
            "/%252e%252e/admin",
            "/%255cevil.example",
            "/%252f%252fevil.example"
    })
    @DisplayName("encoded separator and encoded traversal variants are safely rejected")
    void encodedSeparatorAndTraversalVariantsAreRejected(String target) {
        assertThat(validator.isValid(target)).isFalse();
    }

    @Test
    @DisplayName("valid paths with safe dot normalization or legitimate percent encoding remain valid")
    void safeDotNormalizationAndLegitimateEncodingRemainValid() {
        assertThat(validator.isValid("/novel/%2e/chapters/c1")).isTrue();
        assertThat(validator.isValid("/novel/chapters/%2e%2e/chapters/c1")).isTrue();
        assertThat(validator.isValid("/wiki/character/%E1%BA%A3nh-quan")).isTrue();
    }

    @Test
    @DisplayName("targets exceeding maximum length are rejected")
    void targetsExceedingMaxLengthAreRejected() {
        String longPath = "/novel/" + "a".repeat(2050);
        assertThat(validator.isValid(longPath)).isFalse();
    }
}
