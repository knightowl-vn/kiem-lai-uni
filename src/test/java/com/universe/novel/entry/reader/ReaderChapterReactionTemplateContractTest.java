package com.universe.novel.entry.reader;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class ReaderChapterReactionTemplateContractTest {

    @Test
    @DisplayName("1. chapter.html includes interaction-reactions.css stylesheet in head for comment reactions")
    void chapterReadingPageIncludesReactionStylesheet() throws Exception {
        String chapterPage = read("src/main/resources/templates/novel/chapter.html");

        assertThat(chapterPage).contains("<link rel=\"stylesheet\"\n          th:href=\"@{/css/shared/interaction-reactions.css}\">");
    }

    @Test
    @DisplayName("2. chapter.html does NOT render chapter reactions; bottom nav flows directly to comments")
    void chapterReadingPageFlowsDirectlyFromBottomNavToCommentsWithoutChapterReactions() throws Exception {
        String chapterPage = read("src/main/resources/templates/novel/chapter.html");

        // Chapter reactions removed entirely
        assertThat(chapterPage).doesNotContain("id=\"novelChapterReactions\"");
        assertThat(chapterPage).doesNotContain("class=\"novel-chapter-reactions\"");
        assertThat(chapterPage).doesNotContain("data-reaction-target-type=\"NOVEL_CHAPTER\"");
        assertThat(chapterPage).doesNotContain("chapterReaction");

        // Placement proof: bottom nav comes before comments
        int bottomNavIdx = chapterPage.indexOf("class=\"novel-chapter-nav novel-chapter-nav--bottom\"");
        int commentsIdx = chapterPage.indexOf("id=\"novelChapterComments\"");

        assertThat(bottomNavIdx).isGreaterThan(0);
        assertThat(commentsIdx).isGreaterThan(bottomNavIdx);
    }

    @Test
    @DisplayName("3. chapter.html includes interaction-reactions.js script at bottom for comment reactions")
    void chapterReadingPageIncludesReactionScript() throws Exception {
        String chapterPage = read("src/main/resources/templates/novel/chapter.html");

        assertThat(chapterPage).contains("<script th:src=\"@{/js/shared/interaction-reactions.js}\"\n        defer></script>");
    }

    @Test
    @DisplayName("4. chapter.html renders shared kl-comment-composer classes on root comment composer")
    void chapterReadingPageIncludesSharedComposerClasses() throws Exception {
        String chapterPage = read("src/main/resources/templates/novel/chapter.html");

        assertThat(chapterPage).contains("class=\"kl-comment-composer novel-chapter-comment-composer-wrapper\"");
        assertThat(chapterPage).contains("class=\"kl-comment-composer__form novel-chapter-comment-composer\"");
        assertThat(chapterPage).contains("class=\"kl-comment-composer__label novel-chapter-comment-composer-label\"");
        assertThat(chapterPage).contains("class=\"kl-comment-composer__input novel-chapter-comment-composer-input\"");
        assertThat(chapterPage).contains("class=\"kl-comment-composer__footer\"");
        assertThat(chapterPage).contains("class=\"kl-comment-composer__status novel-chapter-comment-composer-status\"");
        assertThat(chapterPage).contains("class=\"kl-comment-composer__actions novel-chapter-comment-composer-actions\"");
        assertThat(chapterPage).contains("class=\"kl-comment-composer__submit novel-chapter-comment-composer-submit\"");
    }

    private String read(String relativePath) throws Exception {
        return Files.readString(Path.of(relativePath), StandardCharsets.UTF_8).replace("\r\n", "\n");
    }
}
