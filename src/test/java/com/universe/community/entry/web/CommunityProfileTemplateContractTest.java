package com.universe.community.entry.web;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("CommunityProfileTemplateContractTest — Author Profile Template & Parity Contracts")
class CommunityProfileTemplateContractTest {

    @Test
    @DisplayName("Community profile template includes CSRF meta, navbar fragment with activeNav=community, and canonical stylesheets")
    void profileTemplateHeadAndNavbarContract() throws Exception {
        String template = read("src/main/resources/templates/community/profile.html");

        // CSRF meta tags for AJAX / reactions
        assertThat(template).contains("<meta th:if=\"${_csrf != null}\" name=\"_csrf\" th:content=\"${_csrf.token}\">");
        assertThat(template).contains("<meta th:if=\"${_csrf != null}\" name=\"_csrf_header\" th:content=\"${_csrf.headerName}\">");

        // Shared navbar
        assertThat(template).contains("th:replace=\"~{fragments/navbar :: navbar(activeNav='community')}\"");

        // Stylesheets (No inline <style> ownership, uses community.css + interaction-reactions.css)
        assertThat(template).contains("th:href=\"@{/css/theme.css}\"");
        assertThat(template).contains("th:href=\"@{/css/navbar.css}\"");
        assertThat(template).contains("th:href=\"@{/css/community/community.css}\"");
        assertThat(template).contains("th:href=\"@{/css/shared/interaction-reactions.css}\"");
        assertThat(template).contains("https://cdnjs.cloudflare.com/ajax/libs/font-awesome/");

        // Zero duplicate inline <style> block
        assertThat(template).doesNotContain("<style>");
        assertThat(template).doesNotContain(".community-post-card {");
    }

    @Test
    @DisplayName("Community profile template renders authored posts using the canonical postCard fragment")
    void profileAuthoredPostsUseSharedFragmentContract() throws Exception {
        String template = read("src/main/resources/templates/community/profile.html");

        // Container attributes
        assertThat(template).contains("class=\"community-profile-container\"");
        assertThat(template).contains("data-authenticated");

        // Uses the exact same postCard fragment as index.html
        assertThat(template).contains("th:replace=\"~{community/fragments/post-card :: postCard(${post})}\"");

        // Zero th:utext
        assertThat(template).doesNotContain("th:utext");

        // Preserves empty authored-feed state
        assertThat(template).contains("th:if=\"${profile.posts.items.isEmpty()}\"");
        assertThat(template).contains("Chưa có bài viết nào từ tác giả này.");
    }

    @Test
    @DisplayName("Community profile template imports required runtime scripts (theme.js, relative-time.js, interaction-reactions.js, community-post-card.js)")
    void profileScriptImportsContract() throws Exception {
        String template = read("src/main/resources/templates/community/profile.html");

        assertThat(template).contains("th:src=\"@{/js/theme.js}\"");
        assertThat(template).contains("th:src=\"@{/js/shared/relative-time.js}\"");
        assertThat(template).contains("th:src=\"@{/js/shared/interaction-reactions.js}\"");
        assertThat(template).contains("th:src=\"@{/js/community/community-post-card.js}\"");
    }

    @Test
    @DisplayName("Profile header displays safe public profile information without private account leaks")
    void profileHeaderPrivacyContract() throws Exception {
        String template = read("src/main/resources/templates/community/profile.html");

        // Public fields
        assertThat(template).contains("th:text=\"${profile.displayName}\"");
        assertThat(template).contains("'@' + ${profile.publicHandle}");
        assertThat(template).contains("th:text=\"${profile.bio}\"");
        assertThat(template).contains("onerror=\"this.onerror=null;this.src='/images/default_avatar.jpg';\"");

        // Strict privacy: no internal credentials, roles, email, or sensitive provider metadata
        assertThat(template).doesNotContain("profile.email");
        assertThat(template).doesNotContain("profile.password");
        assertThat(template).doesNotContain("profile.roles");
        assertThat(template).doesNotContain("profile.provider");
    }

    private String read(String relativePath) throws Exception {
        return Files.readString(Path.of(relativePath), StandardCharsets.UTF_8).replace("\r\n", "\n");
    }
}
