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
        assertThat(template).contains("th:href=\"@{/css/shared/interaction-reactions.css}\"");
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
    @DisplayName("Feed list delegates post card rendering to canonical shared fragment, and fragment adheres to strict contracts")
    void postCardFeedLoopContract() throws Exception {
        String template = read("src/main/resources/templates/community/index.html");
        String fragment = read("src/main/resources/templates/community/fragments/post-card.html");

        // Feed list container
        assertThat(template).contains("id=\"communityFeedList\"");
        assertThat(template).contains("data-selected-feed");
        assertThat(template).contains("data-next-cursor");
        assertThat(template).contains("data-next-page");
        assertThat(template).contains("data-has-next");
        assertThat(template).contains("data-authenticated");

        // Delegates to shared fragment
        assertThat(template).contains("th:replace=\"~{community/fragments/post-card :: postCard(${item})}\"");

        // Fragment contracts
        assertThat(fragment).contains("th:fragment=\"postCard(item)\"");
        assertThat(fragment).contains("data-post-id");
        assertThat(fragment).contains("data-author-id");

        // Escaped caption (NO th:utext anywhere)
        assertThat(fragment).contains("th:text=\"${item.caption}\"");
        assertThat(fragment).doesNotContain("th:utext");
        assertThat(template).doesNotContain("th:utext");

        // Avatar fallback and handle linking
        assertThat(fragment).contains("onerror=\"this.onerror=null;this.src='/images/default_avatar.jpg';\"");
        assertThat(fragment).contains("th:href=\"@{'/community/@' + ${item.authorPublicHandle}}\"");
        assertThat(fragment).contains("data-relative-time");
        assertThat(fragment).contains("th:text=\"${#temporals.format(item.createdAt, 'dd/MM/yyyy HH:mm')}\"");
        assertThat(fragment).doesNotContain("th:text=\"${item.createdAt}\"");

        // Reaction and comments affordances
        assertThat(fragment).contains("data-reaction-widget");
        assertThat(fragment).contains("data-reaction-target-type=\"COMMUNITY_POST\"");
        assertThat(fragment).contains("sec:authorize=\"isAuthenticated()\"");
        assertThat(fragment).contains("sec:authorize=\"isAnonymous()\"");
        assertThat(fragment).contains("th:text=\"${item.commentCount}\"");

        // Load more container
        assertThat(template).contains("id=\"communityLoadMoreBtn\"");
        assertThat(template).contains("id=\"feedLoadingSpinner\"");
    }

    @Test
    @DisplayName("Static scripts (theme.js, relative-time.js, interaction-reactions.js, community-post-card.js, community-composer.js, community-feed.js) are imported")
    void scriptImportsContract() throws Exception {
        String template = read("src/main/resources/templates/community/index.html");

        assertThat(template).contains("th:src=\"@{/js/theme.js}\"");
        assertThat(template).contains("th:src=\"@{/js/shared/relative-time.js}\"");
        assertThat(template).contains("th:src=\"@{/js/shared/interaction-reactions.js}\"");
        assertThat(template).contains("th:src=\"@{/js/community/community-post-card.js}\"");
        assertThat(template).contains("th:src=\"@{/js/community/community-composer.js}\"");
        assertThat(template).contains("th:src=\"@{/js/community/community-feed.js}\"");
    }

    @Test
    @DisplayName("JavaScript files exist and adhere to security rules (no unsanitized innerHTML for user data)")
    void javaScriptSourceContract() throws Exception {
        String composerJs = read("src/main/resources/static/js/community/community-composer.js");
        String feedJs = read("src/main/resources/static/js/community/community-feed.js");
        String postCardJs = read("src/main/resources/static/js/community/community-post-card.js");

        // Composer limits
        assertThat(composerJs).contains("MAX_CAPTION_LENGTH = 2000");
        assertThat(composerJs).contains("10 * 1024 * 1024");
        assertThat(composerJs).contains("/api/community/posts");

        // Feed JS uses CommunityPostCard module and handles hydration
        assertThat(feedJs).contains("CommunityPostCard.create");
        assertThat(feedJs).contains("RelativeTime.formatTree");
        assertThat(feedJs).contains("InteractionReactions.hydrate");
        assertThat(feedJs).contains("window.CommunityFeed");
        assertThat(feedJs).contains("/api/community/posts?feed=");

        // Post Card JS uses textContent for all user-supplied data
        assertThat(postCardJs).contains("captionP.textContent = item.caption");
        assertThat(postCardJs).contains("authorNameLink.textContent = displayName");
        assertThat(postCardJs).contains("handleSpan.textContent = '@' + item.authorPublicHandle");
        assertThat(postCardJs).doesNotContain("innerHTML");
        assertThat(postCardJs).contains("COMMUNITY_POST");
    }

    private String read(String relativePath) throws Exception {
        return Files.readString(Path.of(relativePath), StandardCharsets.UTF_8).replace("\r\n", "\n");
    }
}
