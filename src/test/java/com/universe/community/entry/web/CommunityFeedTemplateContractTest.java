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
        assertThat(template).contains("th:href=\"@{/css/shared/comments.css}\"");
        assertThat(template).contains("th:href=\"@{/css/shared/interaction-reactions.css}\"");
    }

    @Test
    @DisplayName("Community composer section is guarded by isAuthenticated(), contains form with id, inputs, char count, and preview")
    void composerSectionContract() throws Exception {
        String template = read("src/main/resources/templates/community/index.html");

        assertThat(template).contains("sec:authorize=\"isAuthenticated()\"");
        assertThat(template).contains("id=\"communityComposerTrigger\"");
        assertThat(template).contains("class=\"community-composer-trigger\"");
        assertThat(template).contains("aria-expanded=\"false\"");
        assertThat(template).contains("aria-controls=\"communityComposerPanel\"");
        assertThat(template).contains("id=\"communityComposerPanel\"");
        assertThat(template).contains("id=\"composerCollapseBtn\"");
        assertThat(template).contains("aria-label=\"Thu gọn trình đăng bài\"");
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
        assertThat(template).contains("th:href=\"@{/login(returnTo=${returnTo != null and !#strings.isEmpty(returnTo) ? returnTo : '/community'})}\"");
        assertThat(template).contains("th:href=\"@{/register}\"");
    }

    @Test
    @DisplayName("Feed sort dropdown defines compact dropdown with NEWEST default, FEATURED option, and old tabs removed")
    void feedSortDropdownContract() throws Exception {
        String template = read("src/main/resources/templates/community/index.html");

        // Old tabs/pills are removed
        assertThat(template).doesNotContain("class=\"community-feed-tabs\"");
        assertThat(template).doesNotContain("class=\"feed-tab-btn\"");
        assertThat(template).doesNotContain("id=\"tabNewest\"");
        assertThat(template).doesNotContain("id=\"tabFeatured\"");

        // Compact sort dropdown container and trigger
        assertThat(template).contains("id=\"communityFeedSortDropdown\"");
        assertThat(template).contains("class=\"kl-sort-dropdown community-feed-sort-dropdown\"");
        assertThat(template).contains("id=\"communityFeedSortTrigger\"");
        assertThat(template).contains("aria-haspopup=\"menu\"");
        assertThat(template).contains("aria-expanded=\"false\"");
        assertThat(template).contains("data-action=\"toggle-feed-sort\"");

        // Dynamic trigger label reflecting current feed (defaulting to Mới nhất)
        assertThat(template).contains("id=\"communityFeedSortLabel\"");
        assertThat(template).contains("th:text=\"${selectedFeed == 'FEATURED' ? 'Nổi bật' : 'Mới nhất'}\"");

        // Menu with NEWEST and FEATURED options
        assertThat(template).contains("id=\"communityFeedSortMenu\"");
        assertThat(template).contains("role=\"menu\"");
        assertThat(template).contains("data-action=\"change-feed-sort\"");
        assertThat(template).contains("data-feed=\"NEWEST\"");
        assertThat(template).contains("data-feed=\"FEATURED\"");
        assertThat(template).contains("role=\"menuitemradio\"");
        assertThat(template).contains("kl-sort-dropdown__check");
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
        assertThat(fragment).contains("th:datetime=\"${item.publishedAt}\"");
        assertThat(fragment).contains("th:text=\"${#temporals.format(item.publishedAt, 'dd/MM/yyyy HH:mm')}\"");
        assertThat(fragment).doesNotContain("th:text=\"${item.createdAt}\"");
        assertThat(fragment).doesNotContain("${item.publishedAt != null ? item.publishedAt : item.createdAt}");

        // Reaction and comments affordances
        assertThat(fragment).contains("data-reaction-widget");
        assertThat(fragment).contains("data-reaction-target-type=\"COMMUNITY_POST\"");
        assertThat(fragment).contains("sec:authorize=\"isAuthenticated()\"");
        assertThat(fragment).contains("sec:authorize=\"isAnonymous()\"");
        assertThat(fragment).contains("th:text=\"${item.commentCount}\"");
        assertThat(fragment).contains("data-action=\"toggle-comments\"");
        assertThat(fragment).contains("data-post-comments");
        assertThat(fragment).contains("class=\"post-comments-container\"");
        assertThat(fragment).contains("class=\"post-metric post-comment-toggle-btn\"");

        // B8.3.3 Public post revision indicator contract
        assertThat(fragment).contains("class=\"post-edited-indicator\"");
        assertThat(fragment).contains("data-action=\"view-revisions\"");
        assertThat(fragment).contains("th:if=\"${item.contentVersion > 0}\"");
        assertThat(fragment).contains("Đã chỉnh sửa");
        assertThat(fragment).doesNotContain("• Đã chỉnh sửa");

        // B8.3.4 Caption permalink affordance & Plain timestamp contract
        assertThat(fragment).contains("<a th:href=\"@{'/community/posts/' + ${item.id}}\" class=\"post-caption\" th:text=\"${item.caption}\">");
        assertThat(fragment).contains("<time class=\"post-time\"");
        assertThat(fragment).doesNotContain("post-time-link");

        // Load more container
        assertThat(template).contains("id=\"communityLoadMoreBtn\"");
        assertThat(template).contains("id=\"feedLoadingSpinner\"");
    }

    @Test
    @DisplayName("Static scripts (theme.js, relative-time.js, comment-presentation.js, interaction-reactions.js, community-comments.js, community-post-card.js, community-composer.js, community-feed.js) are imported")
    void scriptImportsContract() throws Exception {
        String template = read("src/main/resources/templates/community/index.html");

        assertThat(template).contains("th:src=\"@{/js/theme.js}\"");
        assertThat(template).contains("th:src=\"@{/js/shared/relative-time.js}\"");
        assertThat(template).contains("th:src=\"@{/js/shared/comment-presentation.js}\"");
        assertThat(template).contains("th:src=\"@{/js/shared/interaction-reactions.js}\"");
        assertThat(template).contains("th:src=\"@{/js/community/community-comments.js}\"");
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
        assertThat(postCardJs).contains("captionLink.textContent = item.caption");
        assertThat(postCardJs).contains("authorNameLink.textContent = displayName");
        assertThat(postCardJs).contains("handleSpan.textContent = '@' + item.authorPublicHandle");
        assertThat(postCardJs).doesNotContain("captionLink.innerHTML");
        assertThat(postCardJs).doesNotContain("authorNameLink.innerHTML");
        assertThat(postCardJs).doesNotContain("authorNameSpan.innerHTML");
        assertThat(postCardJs).doesNotContain("handleSpan.innerHTML");
        assertThat(postCardJs).contains("COMMUNITY_POST");

        // B8.3.2 Owner Actions contract: Edit + Delete, delete modal, strict 204
        assertThat(postCardJs).contains("data-action=\"edit-post\"");
        assertThat(postCardJs).contains("data-action=\"delete-post\"");
        assertThat(postCardJs).contains("communityDeletePostModal");
        assertThat(postCardJs).contains("response.status === 204");

        // B8.3.3 Public Revision History contract
        assertThat(postCardJs).contains("data-action=\"view-revisions\"");
        assertThat(postCardJs).contains("post-edited-indicator");
        assertThat(postCardJs).contains("Number(item.contentVersion) > 0");
        assertThat(postCardJs).contains("communityRevisionHistoryModal");
        assertThat(postCardJs).contains("prevBox.textContent = ");
        assertThat(postCardJs).contains("nextBox.textContent = ");
        assertThat(postCardJs).doesNotContain("prevBox.innerHTML");
        assertThat(postCardJs).doesNotContain("nextBox.innerHTML");

        // B8.3.4 Caption permalink & Permalink delete redirect contract
        assertThat(postCardJs).contains("captionLink.className = 'post-caption'");
        assertThat(postCardJs).contains("captionLink.href = '/community/posts/' + encodeURIComponent(item.id)");
        assertThat(postCardJs).doesNotContain("post-time-link");
        assertThat(postCardJs).contains("community-permalink-container");
        assertThat(postCardJs).contains("window.location.href = '/community'");
    }

    @Test
    @DisplayName("Author pending posts section conforms to canonical post-card structure with status in footer and no reactions/comments/permalink")
    void authorPendingPostCardContract() throws Exception {
        String template = read("src/main/resources/templates/community/index.html");

        // Container and list
        assertThat(template).contains("id=\"communityOwnPendingSection\"");
        assertThat(template).contains("id=\"communityOwnPendingList\"");

        // Collapsible tray trigger and count badge
        assertThat(template).contains("id=\"communityOwnPendingTrigger\"");
        assertThat(template).contains("class=\"community-pending-tray-trigger\"");
        assertThat(template).contains("aria-expanded=\"false\"");
        assertThat(template).contains("aria-controls=\"communityOwnPendingList\"");
        assertThat(template).contains("id=\"communityOwnPendingCount\"");
        assertThat(template).contains("th:text=\"${#lists.size(ownPendingPosts)}\"");
        assertThat(template).contains("id=\"communityOwnPendingList\" class=\"community-pending-list d-flex flex-column gap-3 mt-3\" hidden");

        // Post-card structure
        assertThat(template).contains("class=\"community-post-card community-post-card--pending\"");
        assertThat(template).contains("class=\"post-header\"");
        assertThat(template).contains("class=\"post-author-info\"");
        assertThat(template).contains("class=\"post-author-avatar\"");
        assertThat(template).contains("class=\"post-author-name\"");
        assertThat(template).contains("class=\"post-time\"");

        // Caption as plain paragraph, NOT a permalink anchor
        assertThat(template).contains("<p class=\"post-caption\" th:text=\"${pendingItem.displayCaption}\">");

        // Image container
        assertThat(template).contains("class=\"post-image-container\"");
        assertThat(template).contains("class=\"post-image\"");

        // Footer with status only
        assertThat(template).contains("<footer class=\"post-footer\">");
        assertThat(template).contains("class=\"post-pending-status\"");
        assertThat(template).contains("⏳ Đang chờ duyệt");
        assertThat(template).contains("⏳ Đang chờ duyệt chỉnh sửa");

        // No header badge group
        assertThat(template).doesNotContain("post-pending-badge-group");
    }

    private String read(String relativePath) throws Exception {
        return Files.readString(Path.of(relativePath), StandardCharsets.UTF_8).replace("\r\n", "\n");
    }
}
