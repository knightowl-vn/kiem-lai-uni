package com.universe.novel.application.locator;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class ChapterNumberQueryParserTest {

    @Test
    @DisplayName("Parses pure numeric query")
    void shouldParsePureNumber() {
        Optional<Integer> result = ChapterNumberQueryParser.parseChapterNumber("123");
        assertThat(result).contains(123);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "chương 123",
            "Chương 123",
            "CHƯƠNG 123",
            "  chương   123  ",
            "chương 0123"
    })
    @DisplayName("Parses Vietnamese prefixed chapter numbers")
    void shouldParseVietnamesePrefix(String input) {
        Optional<Integer> result = ChapterNumberQueryParser.parseChapterNumber(input);
        assertThat(result).contains(123);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "chuong 123",
            "Chuong 123",
            "CHUONG 123",
            "  chuong   123  "
    })
    @DisplayName("Parses accentless prefixed chapter numbers")
    void shouldParseAccentlessPrefix(String input) {
        Optional<Integer> result = ChapterNumberQueryParser.parseChapterNumber(input);
        assertThat(result).contains(123);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "chapter 123",
            "Chapter 123",
            "CHAPTER 123",
            "  chapter   123  "
    })
    @DisplayName("Parses English prefixed chapter numbers")
    void shouldParseEnglishPrefix(String input) {
        Optional<Integer> result = ChapterNumberQueryParser.parseChapterNumber(input);
        assertThat(result).contains(123);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "0",
            "chương 0",
            "chuong 0",
            "chapter 0",
            "-5",
            "chương -10"
    })
    @DisplayName("Rejects zero and negative numbers")
    void shouldRejectNonPositiveNumbers(String input) {
        Optional<Integer> result = ChapterNumberQueryParser.parseChapterNumber(input);
        assertThat(result).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "chương",
            "chuong",
            "chapter",
            "chương 123abc",
            "chuong 123 456",
            "123 chuong",
            "123 chương",
            "đoạn 123",
            "hồi 123",
            "tiết 123",
            "bài 123",
            "trần bình an 123",
            "chương một",
            "chương nhất"
    })
    @DisplayName("Rejects non-chapter number queries or arbitrary text with numbers")
    void shouldRejectInvalidPatterns(String input) {
        Optional<Integer> result = ChapterNumberQueryParser.parseChapterNumber(input);
        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("Handles null and empty string safely")
    void shouldHandleNullAndEmpty() {
        assertThat(ChapterNumberQueryParser.parseChapterNumber(null)).isEmpty();
        assertThat(ChapterNumberQueryParser.parseChapterNumber("")).isEmpty();
        assertThat(ChapterNumberQueryParser.parseChapterNumber("    ")).isEmpty();
    }

    @Test
    @DisplayName("Handles query length truncation up to 200 characters")
    void shouldHandleLengthConstraint() {
        String longNumber = "1" + "0".repeat(300);
        Optional<Integer> result = ChapterNumberQueryParser.parseChapterNumber(longNumber);
        assertThat(result).isEmpty();
    }
}
