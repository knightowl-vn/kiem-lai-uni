package com.universe.wiki.entry.web;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Public Wiki Discussion Template Contract Tests")
class PublicWikiDiscussionTemplateContractTest {

    @Test
    @DisplayName("Public Wiki detail page (detail.html) contains discussion section, composer, thread feed, and assets")
    void wikiDetailPageIncludesDiscussionContract() throws Exception {
        String detailHtml = read("src/main/resources/templates/wiki/public/detail.html");

        // 1. Discussion Root Section & Safe Attributes
        assertThat(detailHtml).contains("id=\"wikiDiscussion\"");
        assertThat(detailHtml).contains("class=\"wiki-discussion-section\"");
        assertThat(detailHtml).contains("data-article-id=${article.id}");
        assertThat(detailHtml).contains("data-login-url");
        assertThat(detailHtml).contains("data-authenticated");
        assertThat(detailHtml).contains("data-csrf-token");
        assertThat(detailHtml).contains("data-csrf-header");

        // 2. Heading & Count Badge
        assertThat(detailHtml).contains("id=\"wikiDiscussionHeading\"");
        assertThat(detailHtml).contains("Bình luận");
        assertThat(detailHtml).contains("id=\"wikiDiscussionCountBadge\"");

        // 3. Root Composer & Thread List & Load More
        assertThat(detailHtml).contains("id=\"wikiRootComposer\"");
        assertThat(detailHtml).contains("class=\"kl-comment-composer wiki-comment-composer wiki-comment-composer--root\"");
        assertThat(detailHtml).contains("id=\"wikiRootComposerForm\"");
        assertThat(detailHtml).contains("class=\"kl-comment-composer__form wiki-comment-composer-form\"");
        assertThat(detailHtml).contains("id=\"wikiRootComposerInput\"");
        assertThat(detailHtml).contains("class=\"kl-comment-composer__input wiki-comment-textarea\"");
        assertThat(detailHtml).contains("id=\"wikiRootComposerSubmit\"");
        assertThat(detailHtml).contains("class=\"kl-comment-composer__submit wiki-comment-btn wiki-comment-btn--primary\"");
        assertThat(detailHtml).contains("id=\"wikiDiscussionStatus\"");
        assertThat(detailHtml).contains("id=\"wikiDiscussionThreadList\"");
        assertThat(detailHtml).contains("id=\"wikiDiscussionFooter\"");
        assertThat(detailHtml).contains("id=\"wikiDiscussionLoadMoreBtn\"");

        // 4. Asset Links
        assertThat(detailHtml).contains("th:href=\"@{/css/shared/interaction-reactions.css}\"");
        assertThat(detailHtml).contains("th:href=\"@{/css/wiki/wiki-comments.css}\"");
        assertThat(detailHtml).contains("th:src=\"@{/js/shared/interaction-reactions.js}\"");
        assertThat(detailHtml).contains("th:src=\"@{/js/wiki/wiki-comments.js}\"");
    }

    @Test
    @DisplayName("Public Wiki detail page does NOT contain Novel Reader drawer or comment classes")
    void wikiDetailPageDoesNotContainNovelCommentArtifacts() throws Exception {
        String detailHtml = read("src/main/resources/templates/wiki/public/detail.html");

        assertThat(detailHtml).doesNotContain("novel-chapter-comments");
        assertThat(detailHtml).doesNotContain("novel-comment-drawer");
        assertThat(detailHtml).doesNotContain("reader-block-discussion");
        assertThat(detailHtml).doesNotContain("NovelReaderBlockDiscussion");
        assertThat(detailHtml).doesNotContain("novel-comment-card");
    }

    @Test
    @DisplayName("Discussion section is placed after reading layout and before main ends")
    void discussionPlacementContract() throws Exception {
        String detailHtml = read("src/main/resources/templates/wiki/public/detail.html");

        int readingLayoutPos = detailHtml.indexOf("class=\"wiki-public-reading-layout\"");
        int discussionPos = detailHtml.indexOf("id=\"wikiDiscussion\"");
        int mainEndPos = detailHtml.lastIndexOf("</main>");

        assertThat(readingLayoutPos).isGreaterThan(0);
        assertThat(discussionPos).isGreaterThan(readingLayoutPos);
        assertThat(mainEndPos).isGreaterThan(discussionPos);
    }

    private String read(String relativePath) throws Exception {
        return Files.readString(Path.of(relativePath), StandardCharsets.UTF_8).replace("\r\n", "\n");
    }
}
