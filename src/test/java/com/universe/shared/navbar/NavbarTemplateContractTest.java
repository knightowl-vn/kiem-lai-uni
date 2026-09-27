package com.universe.shared.navbar;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class NavbarTemplateContractTest {

    @Test
    @DisplayName("Navbar fragment (navbar.html) định nghĩa đầy đủ Home (/home), Novel (/novel), Wiki (/wiki) và logic active state")
    void navbarFragmentDefinesExpectedStructureAndActiveNavigation() throws Exception {
        String navbar = read("src/main/resources/templates/fragments/navbar.html");

        // 1. Fragment definition
        assertThat(navbar).contains("th:fragment=\"navbar\"");

        // 2. Home link to /home with house icon
        assertThat(navbar).contains("class=\"navbar-home-link\"");
        assertThat(navbar).contains("th:href=\"@{/home}\"");
        assertThat(navbar).contains("class=\"navbar-home-icon\"");
        assertThat(navbar).contains("class=\"navbar-home-text\"");
        assertThat(navbar).contains("Home");
        assertThat(navbar).contains("aria-label=\"Trang chủ\"");
        assertThat(navbar).contains("<svg");

        // 3. Novel link to /novel
        assertThat(navbar).contains("class=\"navbar-novel-link\"");
        assertThat(navbar).contains("th:href=\"@{/novel}\"");
        assertThat(navbar).contains("Novel");

        // 4. Wiki link to /wiki
        assertThat(navbar).contains("class=\"navbar-wiki-link\"");
        assertThat(navbar).contains("th:href=\"@{/wiki}\"");
        assertThat(navbar).contains("Wiki");

        // 5. Active state evaluation
        assertThat(navbar).contains("activeNav == 'home'");
        assertThat(navbar).contains("activeNav == 'novel'");
        assertThat(navbar).contains("activeNav == 'wiki'");
        assertThat(navbar).contains("aria-current");

        // 6. Search control
        assertThat(navbar).contains("id=\"navbarWikiSearch\"");
        assertThat(navbar).contains("id=\"navbarWikiSearchForm\"");
        assertThat(navbar).contains("id=\"navbarWikiKeyword\"");
        assertThat(navbar).contains("id=\"navbarWikiSearchToggle\"");

        // 7. Account area
        assertThat(navbar).contains("class=\"navbar-account-area\"");
        assertThat(navbar).contains("sec:authorize=\"isAnonymous()\"");
        assertThat(navbar).contains("sec:authorize=\"isAuthenticated()\"");
    }

    @Test
    @DisplayName("Avatar dropdown fragment định nghĩa đúng 4 liên kết cá nhân theo ngữ cảnh và không chứa các liên kết chi tiết cũ")
    void avatarDropdownDefinesContextPersonalLinksAndRemovesGranularItems() throws Exception {
        String navbar = read("src/main/resources/templates/fragments/navbar.html");

        // 1. Profile link
        assertThat(navbar).contains("th:href=\"@{/profile}\"");
        assertThat(navbar).contains("Xem hồ sơ");

        // 2. Novel personal entry point
        assertThat(navbar).contains("th:href=\"@{/novel/history}\"");
        assertThat(navbar).contains("Novel của tôi");

        // 3. Wiki personal entry point
        assertThat(navbar).contains("th:href=\"@{/wiki/saved}\"");
        assertThat(navbar).contains("Wiki của tôi");

        // 4. Interaction comments personal entry point
        assertThat(navbar).contains("th:href=\"@{/comments/my}\"");
        assertThat(navbar).contains("Bình luận của tôi");

        // 5. Old granular links removed from global dropdown
        assertThat(navbar).doesNotContain("th:href=\"@{/novel/bookmarks}\"");
        assertThat(navbar).doesNotContain("Dấu trang");
        assertThat(navbar).doesNotContain("Lịch sử đọc");
        assertThat(navbar).doesNotContain("Bài viết Wiki đã lưu");

        // 6. Theme toggle and logout controls preserved
        assertThat(navbar).contains("id=\"themeToggleCheckbox\"");
        assertThat(navbar).contains("Chế độ tối");
        assertThat(navbar).contains("th:action=\"@{/logout}\"");
        assertThat(navbar).contains("method=\"post\"");
        assertThat(navbar).contains("Đăng xuất");
    }

    @Test
    @DisplayName("navbar.css định nghĩa styles cho .navbar-home-link, .navbar-novel-link, .navbar-wiki-link, active state và mobile responsive rules hiển thị đầy đủ")
    void navbarCssDefinesExpectedStylesAndResponsiveRules() throws Exception {
        String css = read("src/main/resources/static/css/navbar.css");

        // Navigation links styling
        assertThat(css).contains(".navbar-home-link");
        assertThat(css).contains(".navbar-novel-link");
        assertThat(css).contains(".navbar-wiki-link");

        // Active state styling
        assertThat(css).contains(".navbar-home-link.active");
        assertThat(css).contains(".navbar-novel-link.active");
        assertThat(css).contains(".navbar-wiki-link.active");
        assertThat(css).contains(".navbar-home-link[aria-current=\"page\"]");
        assertThat(css).contains(".navbar-novel-link[aria-current=\"page\"]");
        assertThat(css).contains(".navbar-wiki-link[aria-current=\"page\"]");

        // Mobile breakpoint keeps links visible and compact
        assertThat(css).contains("@media (max-width: 767.98px)");
        int mediaQueryIndex = css.indexOf("@media (max-width: 767.98px)");
        String mobileCss = css.substring(mediaQueryIndex);

        assertThat(mobileCss).contains(".navbar-home-link");
        assertThat(mobileCss).contains(".navbar-novel-link");
        assertThat(mobileCss).contains(".navbar-wiki-link");
        assertThat(mobileCss).contains("display: inline-flex");
        assertThat(mobileCss).contains("flex-wrap: nowrap");
    }

    @Test
    @DisplayName("Các template công khai tái sử dụng fragments/navbar mà không nhân bản markup navbar")
    void publicTemplatesReuseSharedNavbarFragment() throws Exception {
        List<String> publicTemplates = List.of(
                "src/main/resources/templates/home.html",
                "src/main/resources/templates/novel/index.html",
                "src/main/resources/templates/novel/chapter.html",
                "src/main/resources/templates/wiki/public/index.html",
                "src/main/resources/templates/wiki/public/detail.html",
                "src/main/resources/templates/wiki/public/not-found.html",
                "src/main/resources/templates/identity/profile.html"
        );

        for (String templatePath : publicTemplates) {
            String content = read(templatePath);
            assertThat(content)
                    .as("Template %s must replace fragments/navbar :: navbar", templatePath)
                    .contains("fragments/navbar :: navbar");
            assertThat(content)
                    .as("Template %s must include navbar.css", templatePath)
                    .contains("css/navbar.css");
        }
    }

    @Test
    @DisplayName("Navbar fragment định nghĩa đầy đủ cấu trúc chuông thông báo, badge, popover/panel, bộ lọc, CSRF bindings và script navbar-notifications.js")
    void navbarNotificationsContractIsDefinedCorrectly() throws Exception {
        String navbar = read("src/main/resources/templates/fragments/navbar.html");
        String css = read("src/main/resources/static/css/navbar.css");

        // 1. Authenticated container & CSRF bindings
        assertThat(navbar).contains("id=\"navbarNotifications\"");
        assertThat(navbar).contains("th:attr=\"data-csrf-token=");
        assertThat(navbar).contains("data-csrf-header=");

        // 2. Bell button & accessibility attributes (omits aria-haspopup to avoid invalid menu semantics)
        assertThat(navbar).contains("id=\"navbarBellButton\"");
        assertThat(navbar).contains("aria-label=\"Thông báo\"");
        assertThat(navbar).contains("aria-expanded=\"false\"");
        assertThat(navbar).contains("aria-controls=\"navbarNotificationsPanel\"");
        assertThat(navbar).doesNotContain("aria-haspopup=\"true\"");
        assertThat(navbar).doesNotContain("aria-haspopup=\"menu\"");
        assertThat(navbar).doesNotContain("aria-haspopup=\"dialog\"");

        // 3. Unread badge & screen reader label
        assertThat(navbar).contains("id=\"navbarBellBadge\"");
        assertThat(navbar).contains("id=\"navbarBellBadgeSr\"");

        // 4. Notification Panel & Controls
        assertThat(navbar).contains("id=\"navbarNotificationsPanel\"");
        assertThat(navbar).contains("role=\"region\"");
        assertThat(navbar).contains("aria-label=\"Bảng thông báo\"");
        assertThat(navbar).contains("id=\"notifMobileBackButton\"");
        assertThat(navbar).contains("id=\"notifMarkAllBtn\"");
        assertThat(navbar).contains("Đánh dấu tất cả đã đọc");

        // 5. Filter tabs & Feed list
        assertThat(navbar).contains("id=\"notifFilterAll\"");
        assertThat(navbar).contains("id=\"notifFilterUnread\"");
        assertThat(navbar).contains("id=\"notifFeedList\"");
        assertThat(navbar).contains("id=\"notifFeedFooter\"");
        assertThat(navbar).contains("id=\"notifLoadMoreBtn\"");

        // 6. Script reference
        assertThat(navbar).contains("th:src=\"@{/js/navbar-notifications.js}\"");

        // 7. CSS definitions
        assertThat(css).contains(".navbar-notifications");
        assertThat(css).contains(".navbar-bell-button");
        assertThat(css).contains(".navbar-bell-badge");
        assertThat(css).contains(".navbar-notifications-panel");
        assertThat(css).contains(".notif-item");
        assertThat(css).contains(".notif-item.is-unread");
        assertThat(css).contains("notifications-panel-open");
    }

    private String read(String relativePath) throws Exception {
        return Files.readString(Path.of(relativePath), StandardCharsets.UTF_8);
    }
}
