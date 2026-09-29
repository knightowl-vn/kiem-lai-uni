package com.universe.search.contracts.dto;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class SearchScopeTest {

    @Test
    @DisplayName("fromNullable converts valid 'all' and 'ALL' to SearchScope.ALL")
    void fromNullable_whenAll_returnsAll() {
        assertThat(SearchScope.fromNullable("all")).isEqualTo(SearchScope.ALL);
        assertThat(SearchScope.fromNullable("ALL")).isEqualTo(SearchScope.ALL);
        assertThat(SearchScope.fromNullable("  All  ")).isEqualTo(SearchScope.ALL);
    }

    @Test
    @DisplayName("fromNullable converts valid 'wiki' and 'WIKI' to SearchScope.WIKI")
    void fromNullable_whenWiki_returnsWiki() {
        assertThat(SearchScope.fromNullable("wiki")).isEqualTo(SearchScope.WIKI);
        assertThat(SearchScope.fromNullable("WIKI")).isEqualTo(SearchScope.WIKI);
        assertThat(SearchScope.fromNullable("  Wiki  ")).isEqualTo(SearchScope.WIKI);
    }

    @Test
    @DisplayName("fromNullable converts valid 'novel' and 'NOVEL' to SearchScope.NOVEL")
    void fromNullable_whenNovel_returnsNovel() {
        assertThat(SearchScope.fromNullable("novel")).isEqualTo(SearchScope.NOVEL);
        assertThat(SearchScope.fromNullable("NOVEL")).isEqualTo(SearchScope.NOVEL);
        assertThat(SearchScope.fromNullable("  Novel  ")).isEqualTo(SearchScope.NOVEL);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   ", "\t", "\n"})
    @DisplayName("fromNullable falls back to SearchScope.ALL for null, empty, or whitespace strings")
    void fromNullable_whenNullOrBlank_returnsAll(String rawScope) {
        assertThat(SearchScope.fromNullable(rawScope)).isEqualTo(SearchScope.ALL);
    }

    @ParameterizedTest
    @ValueSource(strings = {"invalid", "donghua", "manhua", "community", "user", "123", "random_string"})
    @DisplayName("fromNullable falls back to SearchScope.ALL for unrecognized or unsupported scopes")
    void fromNullable_whenUnrecognized_returnsAll(String rawScope) {
        assertThat(SearchScope.fromNullable(rawScope)).isEqualTo(SearchScope.ALL);
    }

    @Test
    @DisplayName("fromNullable correctly parses scopes under Turkish locale without dotted-i corruption")
    void fromNullable_underTurkishLocale_parsesCorrectly() {
        java.util.Locale originalLocale = java.util.Locale.getDefault();
        try {
            java.util.Locale.setDefault(java.util.Locale.forLanguageTag("tr-TR"));
            assertThat(SearchScope.fromNullable("wiki")).isEqualTo(SearchScope.WIKI);
            assertThat(SearchScope.fromNullable("all")).isEqualTo(SearchScope.ALL);
            assertThat(SearchScope.fromNullable("novel")).isEqualTo(SearchScope.NOVEL);
        } finally {
            java.util.Locale.setDefault(originalLocale);
        }
    }
}
