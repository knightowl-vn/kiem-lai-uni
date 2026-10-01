package com.universe.community.entry.web;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("CommunityFeedClientContractTest — JavaScript Client Contracts for Feed & Composer")
class CommunityFeedClientContractTest {

    @Test
    @DisplayName("community-composer.js enforces character limits, image constraints, CSRF injection, FormData transport, double-submit guard, and XSS-safe error handling")
    void communityComposerClientContract() throws Exception {
        String js = read("src/main/resources/static/js/community/community-composer.js");

        // 1. Validation limits
        assertThat(js).contains("MAX_CAPTION_LENGTH = 2000");
        assertThat(js).contains("MAX_IMAGE_SIZE_BYTES = 10 * 1024 * 1024");
        assertThat(js).contains("ALLOWED_IMAGE_TYPES = ['image/jpeg', 'image/png', 'image/webp']");

        // 2. Transport & Endpoint
        assertThat(js).contains("fetch('/api/community/posts'");
        assertThat(js).contains("method: 'POST'");
        assertThat(js).contains("new FormData()");
        assertThat(js).contains("formData.append('caption', caption)");
        assertThat(js).contains("formData.append('image', file)");

        // 3. No manual multipart/form-data header (lets browser generate boundary)
        assertThat(js).doesNotContain("'Content-Type': 'multipart/form-data'");
        assertThat(js).doesNotContain("\"Content-Type\": \"multipart/form-data\"");

        // 4. CSRF meta extraction & header injection
        assertThat(js).contains("document.querySelector('meta[name=\"_csrf\"]')");
        assertThat(js).contains("document.querySelector('meta[name=\"_csrf_header\"]')");
        assertThat(js).contains("headers[headerName] = token");

        // 5. Double-submit guard & UI state locking
        assertThat(js).contains("let isSubmitting = false");
        assertThat(js).contains("if (isSubmitting) {");
        assertThat(js).contains("setSubmitting(true)");
        assertThat(js).contains("submitBtn.disabled = loading");
        assertThat(js).contains("submitSpinner.removeAttribute('hidden')");
        assertThat(js).contains("submitSpinner.setAttribute('hidden', '')");

        // 6. Feed refresh on success
        assertThat(js).contains("window.CommunityFeed.refreshFeed('NEWEST')");
        assertThat(js).contains("resetComposer()");

        // 7. XSS-safe textContent for error rendering
        assertThat(js).contains("errorAlert.textContent = msg");
        assertThat(js).doesNotContain("errorAlert.innerHTML =");
    }

    @Test
    @DisplayName("community-feed.js enforces NEWEST/FEATURED switching, isolated cursor/page pagination, safe DOM post card rendering, and global refresh hook")
    void communityFeedClientContract() throws Exception {
        String js = read("src/main/resources/static/js/community/community-feed.js");

        // 1. Feed tab switching & browser URL pushState
        assertThat(js).contains("switchFeed('NEWEST')");
        assertThat(js).contains("switchFeed('FEATURED')");
        assertThat(js).contains("window.history.pushState({ feed: feedType }, '', newUrl)");
        assertThat(js).contains("'/community?feed=' + feedType");

        // 2. Feed API fetch URL
        assertThat(js).contains("'/api/community/posts?feed=' + encodeURIComponent(currentFeed) + '&size=' + PAGE_SIZE");

        // 3. Isolated pagination parameters (cursor for NEWEST, page for FEATURED)
        assertThat(js).contains("currentFeed === 'NEWEST' && nextCursor");
        assertThat(js).contains("url += '&cursor=' + encodeURIComponent(nextCursor)");
        assertThat(js).contains("currentFeed === 'FEATURED' && nextPage != null");
        assertThat(js).contains("url += '&page=' + encodeURIComponent(nextPage)");

        // 4. Global hook for composer integration
        assertThat(js).contains("window.CommunityFeed = {");
        assertThat(js).contains("refreshFeed: function (feedType)");

        // 5. Safe DOM post card construction with textContent (zero XSS on dynamic content)
        assertThat(js).contains("article.className = 'community-post-card'");
        assertThat(js).contains("authorNameLink.textContent = displayName");
        assertThat(js).contains("handleSpan.textContent = '@' + item.authorPublicHandle");
        assertThat(js).contains("captionP.textContent = item.caption");
        assertThat(js).contains("likeCountSpan.textContent = item.reactionCount || 0");
        assertThat(js).contains("commentCountSpan.textContent = item.commentCount || 0");

        // 6. Fallback avatar onerror & handle link encoding
        assertThat(js).contains("defaultAvatar = '/images/default_avatar.jpg'");
        assertThat(js).contains("this.src = defaultAvatar");
        assertThat(js).contains("'/community/@' + encodeURIComponent(item.authorPublicHandle)");

        // 7. Relative time attribute integration
        assertThat(js).contains("timeEl.setAttribute('data-relative-time', '')");
        assertThat(js).contains("window.RelativeTime.formatTree(card)");

        // 8. Load-more & spinner state toggling
        assertThat(js).contains("loadMorePosts()");
        assertThat(js).contains("spinner.removeAttribute('hidden')");
        assertThat(js).contains("spinner.setAttribute('hidden', '')");
        assertThat(js).contains("loadMoreBtn.removeAttribute('hidden')");
        assertThat(js).contains("loadMoreBtn.setAttribute('hidden', '')");
    }

    private String read(String relativePath) throws Exception {
        return Files.readString(Path.of(relativePath), StandardCharsets.UTF_8).replace("\r\n", "\n");
    }
}
