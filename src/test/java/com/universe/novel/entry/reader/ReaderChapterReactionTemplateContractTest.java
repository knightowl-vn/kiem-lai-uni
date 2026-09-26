package com.universe.novel.entry.reader;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class ReaderChapterReactionTemplateContractTest {

    @Test
    @DisplayName("1. chapter.html includes interaction-reactions.css stylesheet in head")
    void chapterReadingPageIncludesReactionStylesheet() throws Exception {
        String chapterPage = read("src/main/resources/templates/novel/chapter.html");

        assertThat(chapterPage).contains("<link rel=\"stylesheet\"\n          th:href=\"@{/css/shared/interaction-reactions.css}\">");
    }

    @Test
    @DisplayName("2. chapter.html renders reaction host between bottom nav and comments with correct attributes and th:if guard")
    void chapterReadingPageRendersReactionHostMarkup() throws Exception {
        String chapterPage = read("src/main/resources/templates/novel/chapter.html");

        // Section exists with th:if guard and aria-label
        assertThat(chapterPage).contains("th:if=\"${reactionSummary != null}\"");
        assertThat(chapterPage).contains("class=\"novel-chapter-reactions\"");
        assertThat(chapterPage).contains("id=\"novelChapterReactions\"");
        assertThat(chapterPage).contains("aria-label=\"Cảm xúc chương\"");

        // Widget host container with required data attributes
        assertThat(chapterPage).contains("<div class=\"kl-reaction-widget\"");
        assertThat(chapterPage).contains("data-reaction-widget");
        assertThat(chapterPage).contains("data-reaction-target-type=\"NOVEL_CHAPTER\"");
        assertThat(chapterPage).contains("data-reaction-target-id=${chapter.id}");
        assertThat(chapterPage).contains("data-reaction-current=");
        assertThat(chapterPage).contains("data-reaction-total=");
        assertThat(chapterPage).contains("data-reaction-count-love=");
        assertThat(chapterPage).contains("data-reaction-count-fire=");
        assertThat(chapterPage).contains("data-reaction-count-haha=");
        assertThat(chapterPage).contains("data-reaction-count-sad=");

        // Placement proof: bottom nav comes before reactions, reactions come before comments
        int bottomNavIdx = chapterPage.indexOf("class=\"novel-chapter-nav novel-chapter-nav--bottom\"");
        int reactionsIdx = chapterPage.indexOf("class=\"novel-chapter-reactions\"");
        int commentsIdx = chapterPage.indexOf("class=\"novel-chapter-comments\"");

        assertThat(bottomNavIdx).isGreaterThan(0);
        assertThat(reactionsIdx).isGreaterThan(bottomNavIdx);
        assertThat(commentsIdx).isGreaterThan(reactionsIdx);
    }

    @Test
    @DisplayName("3. chapter.html includes interaction-reactions.js script at bottom")
    void chapterReadingPageIncludesReactionScript() throws Exception {
        String chapterPage = read("src/main/resources/templates/novel/chapter.html");

        assertThat(chapterPage).contains("<script th:src=\"@{/js/shared/interaction-reactions.js}\"\n        defer></script>");
    }

    private String read(String relativePath) throws Exception {
        return Files.readString(Path.of(relativePath), StandardCharsets.UTF_8).replace("\r\n", "\n");
    }
}
