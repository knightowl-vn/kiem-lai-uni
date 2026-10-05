package com.universe.community.entry.web;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("CommunityPostPermalinkTemplateContractTest — Permalink Template Contracts")
class CommunityPostPermalinkTemplateContractTest {

    @Test
    @DisplayName("Community permalink template includes CSRF meta, current-user-id, navbar, and canonical stylesheets")
    void permalinkTemplateHeadAndNavbarContract() throws Exception {
        String template = read("src/main/resources/templates/community/post.html");

        // CSRF meta tags for AJAX / reactions
        assertThat(template).contains("<meta th:if=\"${_csrf != null}\" name=\"_csrf\" th:content=\"${_csrf.token}\">");
        assertThat(template).contains("<meta th:if=\"${_csrf != null}\" name=\"_csrf_header\" th:content=\"${_csrf.headerName}\">");

        // Current user ID meta
        assertThat(template).contains("<meta th:if=\"${currentUser != null}\" name=\"current-user-id\" th:content=\"${currentUser.id}\">");

        // Shared navbar
        assertThat(template).contains("th:replace=\"~{fragments/navbar :: navbar(activeNav='community')}\"");

        // Stylesheets
        assertThat(template).contains("th:href=\"@{/css/theme.css}\"");
        assertThat(template).contains("th:href=\"@{/css/navbar.css}\"");
        assertThat(template).contains("th:href=\"@{/css/community/community.css}\"");
        assertThat(template).contains("th:href=\"@{/css/shared/comments.css}\"");
        assertThat(template).contains("th:href=\"@{/css/shared/interaction-reactions.css}\"");
        assertThat(template).contains("https://cdnjs.cloudflare.com/ajax/libs/font-awesome/");

        // Zero duplicate inline <style> block
        assertThat(template).doesNotContain("<style>");
    }

    @Test
    @DisplayName("Community permalink template renders the single post using shared postCard fragment")
    void permalinkUsesSharedPostCardFragment() throws Exception {
        String template = read("src/main/resources/templates/community/post.html");

        // Permalink container and data attributes
        assertThat(template).contains("class=\"community-permalink-container\"");
        assertThat(template).contains("class=\"community-permalink-content\"");
        assertThat(template).contains("data-authenticated");
        assertThat(template).contains("data-current-user-id");

        // Exactly one shared postCard fragment call
        assertThat(template).contains("th:replace=\"~{community/fragments/post-card :: postCard(${item})}\"");

        // Zero th:utext
        assertThat(template).doesNotContain("th:utext");
    }

    @Test
    @DisplayName("Community permalink template includes required scripts and excludes feed and composer scripts")
    void permalinkScriptContract() throws Exception {
        String template = read("src/main/resources/templates/community/post.html");

        // Required shared and community scripts
        assertThat(template).contains("th:src=\"@{/js/theme.js}\"");
        assertThat(template).contains("th:src=\"@{/js/shared/relative-time.js}\"");
        assertThat(template).contains("th:src=\"@{/js/shared/comment-presentation.js}\"");
        assertThat(template).contains("th:src=\"@{/js/shared/interaction-reactions.js}\"");
        assertThat(template).contains("th:src=\"@{/js/community/community-comments.js}\"");
        assertThat(template).contains("th:src=\"@{/js/community/community-post-card.js}\"");

        // Proved NOT needed scripts
        assertThat(template).doesNotContain("community-feed.js");
        assertThat(template).doesNotContain("community-composer.js");
    }

    @Test
    @DisplayName("Shared post-card fragment renders caption as permalink anchor and timestamp as plain time")
    void sharedPostCardCaptionPermalinkContract() throws Exception {
        String fragment = read("src/main/resources/templates/community/fragments/post-card.html");

        // Plain time element without anchor
        assertThat(fragment).contains("<time class=\"post-time\"");
        assertThat(fragment).doesNotContain("class=\"post-time-link\"");

        // Caption rendered as anchor to /community/posts/{item.id} with th:text
        assertThat(fragment).contains("<a th:href=\"@{'/community/posts/' + ${item.id}}\" class=\"post-caption\" th:text=\"${item.caption}\">");
        assertThat(fragment).doesNotContain("<p class=\"post-caption\"");
    }

    private String read(String relativePath) throws Exception {
        return Files.readString(Path.of(relativePath), StandardCharsets.UTF_8);
    }
}
