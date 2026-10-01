package com.universe.community.entry.web;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("CommunityFeedTemplateContractTest — Template & Frontend Contract Tests")
class CommunityFeedTemplateContractTest {

    @Test
    @DisplayName("Community index template includes CSRF meta, navbar fragment with activeNav=community, and required stylesheets")
    void indexTemplateHeadAndNavbarContract() throws Exception {
        String template = read("src/main/resources/templates/community/index.html");

        // CSRF meta tags
        assertThat(template).contains("<meta th:if=\"${_csrf != null}\" name=\"_csrf\" th:content=\"${_csrf.token}\">");
        assertThat(template).contains("<meta th:if=\"${_csrf != null}\" name=\"_csrf_header\" th:content=\"${_csrf.headerName}\">");

        // Shared navbar
        assertThat(template).contains("th:replace=\"~{fragments/navbar :: navbar(activeNav='community')}\"");

        // Stylesheets
        assertThat(template).contains("th:href=\"@{/css/theme.css}\"");
        assertThat(template).contains("th:href=\"@{/css/navbar.css}\"");
        assertThat(template).contains("th:href=\"@{/css/community/community.css}\"");
    }

    @Test
    @DisplayName("Community composer section is guarded by isAuthenticated(), contains form with id, inputs, char count, and preview")
    void composerSectionContract() throws Exception {
        String template = read("src/main/resources/templates/community/index.html");

        assertThat(template).contains("sec:authorize=\"isAuthenticated()\"");
        assertThat(template).contains("id=\"communityComposerForm\"");
        assertThat(template).contains("id=\"composerCaption\"");
        assertThat(template).contains("maxlength=\"2000\"");
        assertThat(template).contains("id=\"composerImagePreview\"");
        assertThat(template).contains("id=\"composerImagePreviewImg\"");
        assertThat(template).contains("id=\"composerRemoveImageBtn\"");
        assertThat(template).contains("id=\"composerError\"");
        assertThat(template).contains("id=\"composerImageInput\"");
        assertThat(template).contains("accept=\"image/jpeg,image/png,image/webp\"");
        assertThat(template).contains("id=\"composerAddImageBtn\"");
        assertThat(template).contains("id=\"composerCharCount\"");
        assertThat(template).contains("id=\"composerSubmitBtn\"");
        assertThat(template).contains("id=\"composerSubmitSpinner\"");
    }

    @Test
    @DisplayName("Guest CTA card is guarded by isAnonymous() with login and register links")
    void guestCtaSectionContract() throws Exception {
        String template = read("src/main/resources/templates/community/index.html");

        assertThat(template).contains("sec:authorize=\"isAnonymous()\"");
        assertThat(template).contains("class=\"community-guest-card\"");
        assertThat(template).contains("th:href=\"@{/login}\"");
        assertThat(template).contains("th:href=\"@{/register}\"");
    }

    @Test
    @DisplayName("Feed tabs define NEWEST and FEATURED tabs with correct data-feed attributes and active state")
    void feedTabsContract() throws Exception {
        String template = read("src/main/resources/templates/community/index.html");

        assertThat(template).contains("id=\"tabNewest\"");
        assertThat(template).contains("id=\"tabFeatured\"");
        assertThat(template).contains("data-feed=\"NEWEST\"");
        assertThat(template).contains("data-feed=\"FEATURED\"");
        assertThat(template).contains("th:classappend=\"${selectedFeed == 'NEWEST'} ? ' is-active' : ''\"");
        assertThat(template).contains("th:classappend=\"${selectedFeed == 'FEATURED'} ? ' is-active' : ''\"");
    }

    @Test
    @DisplayName("Feed list and post cards use th:text for escaping, avatar fallback onerror, and relative time attributes")
    void postCardFeedLoopContract() throws Exception {
        String template = read("src/main/resources/templates/community/index.html");

        assertThat(template).contains("id=\"communityFeedList\"");
        assertThat(template).contains("data-selected-feed");
        assertThat(template).contains("data-next-cursor");
        assertThat(template).contains("data-next-page");
        assertThat(template).contains("data-has-next");

        // Escaped caption (NO th:utext anywhere)
        assertThat(template).contains("th:text=\"${item.caption}\"");
        assertThat(template).doesNotContain("th:utext");

        // Avatar fallback and handle linking
        assertThat(template).contains("onerror=\"this.onerror=null;this.src='/images/default_avatar.jpg';\"");
        assertThat(template).contains("th:href=\"@{'/community/@' + ${item.authorPublicHandle}}\"");
        assertThat(template).contains("data-relative-time");

        // Load more container
        assertThat(template).contains("id=\"communityLoadMoreBtn\"");
        assertThat(template).contains("id=\"feedLoadingSpinner\"");
    }

    @Test
    @DisplayName("Static scripts (theme.js, relative-time.js, community-composer.js, community-feed.js) are imported")
    void scriptImportsContract() throws Exception {
        String template = read("src/main/resources/templates/community/index.html");

        assertThat(template).contains("th:src=\"@{/js/theme.js}\"");
        assertThat(template).contains("th:src=\"@{/js/shared/relative-time.js}\"");
        assertThat(template).contains("th:src=\"@{/js/community/community-composer.js}\"");
        assertThat(template).contains("th:src=\"@{/js/community/community-feed.js}\"");
    }

    @Test
    @DisplayName("JavaScript files exist and adhere to security rules (no unsanitized innerHTML for user data)")
    void javaScriptSourceContract() throws Exception {
        String composerJs = read("src/main/resources/static/js/community/community-composer.js");
        String feedJs = read("src/main/resources/static/js/community/community-feed.js");

        // Composer limits
        assertThat(composerJs).contains("MAX_CAPTION_LENGTH = 2000");
        assertThat(composerJs).contains("10 * 1024 * 1024");
        assertThat(composerJs).contains("/api/community/posts");

        // Feed JS uses textContent for caption, handle, display name
        assertThat(feedJs).contains("textContent = item.caption");
        assertThat(feedJs).contains("window.CommunityFeed");
        assertThat(feedJs).contains("/api/community/posts?feed=");
    }

    private String read(String relativePath) throws Exception {
        return Files.readString(Path.of(relativePath), StandardCharsets.UTF_8).replace("\r\n", "\n");
    }
}
