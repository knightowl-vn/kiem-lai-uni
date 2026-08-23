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

    private String read(String relativePath) throws Exception {
        return Files.readString(Path.of(relativePath), StandardCharsets.UTF_8);
    }
}
