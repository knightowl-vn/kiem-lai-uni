package com.universe.wiki.entry.web;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("PublicWikiDetailContributorCardTemplateTest — Public Contributor Recognition Template Contract")
class PublicWikiDetailContributorCardTemplateTest {

    private String read(String relativePath) throws Exception {
        return Files.readString(Path.of(relativePath), StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("detail.html contains the desktop sidebar card, mobile card, and modal dialog with required contract")
    void detailPageContainsContributorCardContract() throws Exception {
        String template = read("src/main/resources/templates/wiki/public/detail.html");

        // 1. Condition: hidden when empty
        assertThat(template).contains("th:if=\"${publicContributors != null and !#lists.isEmpty(publicContributors)}\"");

        // 2. Desktop Sidebar Card
        assertThat(template).contains("class=\"wiki-public-contributors-sidebar-card\"");
        assertThat(template).contains("id=\"wikiDesktopContributorsHeading\"");
        assertThat(template).contains("data-wiki-contributors-modal-trigger");

        // 3. Mobile Card
        assertThat(template).contains("class=\"wiki-public-contributors-mobile-card\"");
        assertThat(template).contains("id=\"wikiMobileContributorsHeading\"");

        // 4. Modal Dialog
        assertThat(template).contains("id=\"wikiContributorsModal\"");
        assertThat(template).contains("class=\"wiki-contributors-modal\"");
        assertThat(template).contains("data-wiki-contributors-modal-close");
        assertThat(template).contains("th:each=\"contributor : ${publicContributors}\"");

        // 5. Contributor display name and avatar rendering
        assertThat(template).contains("th:text=\"${contributor.displayName}\"");
        assertThat(template).contains("th:if=\"${contributor.avatarUrl != null and !contributor.avatarUrl.isBlank()}\"");
        assertThat(template).contains("th:src=\"${contributor.avatarUrl}\"");
        assertThat(template).contains("th:alt=\"${contributor.displayName}\"");
        assertThat(template).contains("th:unless=\"${contributor.avatarUrl != null and !contributor.avatarUrl.isBlank()}\"");

        // 6. Bounded count badge (supports 50+ when candidate limit reached and 50 visible)
        assertThat(template).contains("th:text=\"${(publicContributorsLimitReached != null and publicContributorsLimitReached and #lists.size(publicContributors) == 50) ? '50+' : #lists.size(publicContributors)}\"");

        // 7. Modal limit notice driven by candidateLimitReached
        assertThat(template).contains("th:if=\"${publicContributorsLimitReached != null and publicContributorsLimitReached}\"");
        assertThat(template).contains("Hiển thị tối đa 50 người đóng góp gần nhất có hồ sơ công khai.");

        // 8. Active credit count label
        assertThat(template).contains("th:text=\"${contributor.activeCreditCount == 1 ? '1 đóng góp được ghi nhận' : (contributor.activeCreditCount + ' đóng góp được ghi nhận')}\"");

        // 9. Privacy invariants: template must NEVER contain moderation metadata or public profile links
        assertThat(template).doesNotContain("contributor.creditedBy");
        assertThat(template).doesNotContain("contributor.creditNote");
        assertThat(template).doesNotContain("contributor.revokedBy");
        assertThat(template).doesNotContain("contributor.revocationReason");
        assertThat(template).doesNotContain("contributor.resolutionOutcome");
        assertThat(template).doesNotContain("contributor.userId");
        assertThat(template).doesNotContain("/users/");
        assertThat(template).doesNotContain("/u/");

        // 10. Separation from authorial attribution
        assertThat(template).contains("attributionLine");

        // 11. Script presence
        assertThat(template).contains("wiki-contributors.js");
    }

    @Test
    @DisplayName("detail.html enforces strict DOM relative placement for desktop sidebar and mobile card")
    void detailPageEnforcesDOMRelativePlacement() throws Exception {
        String template = read("src/main/resources/templates/wiki/public/detail.html");

        // Desktop sidebar relative ordering:
        // wiki-public-reading-sidebar < wikiPublicToc < wiki-public-back-to-top < wiki-public-contributors-sidebar-card < closing </aside>
        int sidebarOpenIdx = template.indexOf("class=\"wiki-public-reading-sidebar\"");
        int tocIdx = template.indexOf("id=\"wikiPublicToc\"");
        int backToTopIdx = template.indexOf("class=\"wiki-public-back-to-top\"");
        int desktopCardIdx = template.indexOf("class=\"wiki-public-contributors-sidebar-card\"");
        int sidebarCloseIdx = template.indexOf("</aside>", desktopCardIdx);

        assertThat(sidebarOpenIdx).isGreaterThan(0);
        assertThat(tocIdx).isGreaterThan(sidebarOpenIdx);
        assertThat(backToTopIdx).isGreaterThan(tocIdx);
        assertThat(desktopCardIdx).isGreaterThan(backToTopIdx);
        assertThat(sidebarCloseIdx).isGreaterThan(desktopCardIdx);

        // Mobile relative ordering:
        // end of wiki-public-reading-layout (<aside> close) < wiki-public-contributors-mobile-card < wikiDiscussion
        int mobileCardIdx = template.indexOf("class=\"wiki-public-contributors-mobile-card\"");
        int discussionIdx = template.indexOf("id=\"wikiDiscussion\"");

        assertThat(mobileCardIdx).isGreaterThan(sidebarCloseIdx);
        assertThat(discussionIdx).isGreaterThan(mobileCardIdx);
    }

    @Test
    @DisplayName("wiki.css contains styles for desktop sidebar card, mobile card, and modal dialog")
    void stylesheetContainsContributorCardStyles() throws Exception {
        String css = read("src/main/resources/static/css/wiki/wiki.css");

        assertThat(css).contains(".wiki-public-contributors-sidebar-card");
        assertThat(css).contains(".wiki-public-contributors-mobile-card");
        assertThat(css).contains(".wiki-public-contributors-avatar-stack");
        assertThat(css).contains(".wiki-public-contributors-more-badge");
        assertThat(css).contains(".wiki-contributors-modal");
        assertThat(css).contains(".wiki-contributors-modal-dialog");
        assertThat(css).contains(".wiki-contributors-modal-header");
        assertThat(css).contains(".wiki-contributors-modal-body");
    }
}
