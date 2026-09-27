package com.universe.wiki.entry.web;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("UserWikiContributionTemplateContractTest — Personal Wiki Hub Template Contract Tests")
class UserWikiContributionTemplateContractTest {

    @Test
    @DisplayName("Wiki personal-nav fragment (personal-nav.html) tuân thủ semantic navigation và a11y")
    void personalNavFragmentContract() throws Exception {
        String fragment = read("src/main/resources/templates/wiki/public/fragments/personal-nav.html");

        // 1. Navigation element & a11y label
        assertThat(fragment).contains("<nav th:fragment=\"personalNav(activeTab)\"");
        assertThat(fragment).contains("class=\"wiki-personal-nav\"");
        assertThat(fragment).contains("aria-label=\"Điều hướng cá nhân Wiki\"");

        // 2. Tab links & active attribute
        assertThat(fragment).contains("th:href=\"@{/wiki/saved}\"");
        assertThat(fragment).contains("th:href=\"@{/wiki/contributions}\"");
        assertThat(fragment).contains("th:attr=\"aria-current=${activeTab == 'saved' ? 'page' : null}\"");
        assertThat(fragment).contains("th:attr=\"aria-current=${activeTab == 'contributions' ? 'page' : null}\"");

        // 3. Labels
        assertThat(fragment).contains("<span>Bài viết đã lưu</span>");
        assertThat(fragment).contains("<span>Đóng góp của tôi</span>");

        // 4. Strict a11y: No invalid role overrides on links or container
        assertThat(fragment).doesNotContain("role=\"tab\"");
        assertThat(fragment).doesNotContain("role=\"tablist\"");
        assertThat(fragment).doesNotContain("role=\"list\"");
        assertThat(fragment).doesNotContain("role=\"listitem\"");
    }

    @Test
    @DisplayName("Saved Wiki articles page (saved.html) tích hợp personal-nav fragment")
    void savedPageIncludesPersonalNav() throws Exception {
        String savedPage = read("src/main/resources/templates/wiki/public/saved.html");

        assertThat(savedPage).contains("th:replace=\"~{wiki/public/fragments/personal-nav :: personalNav(activeTab='saved')}\"");
    }

    @Test
    @DisplayName("Contributions page (contributions.html) tuân thủ đầy đủ cấu trúc UI và ViewModel contract")
    void contributionsPageContract() throws Exception {
        String contributionsPage = read("src/main/resources/templates/wiki/public/contributions.html");

        // 1. Shared navbar & stylesheet & switcher
        assertThat(contributionsPage).contains("th:replace=\"~{fragments/navbar :: navbar(activeNav='wiki')}\"");
        assertThat(contributionsPage).contains("th:href=\"@{/css/wiki/wiki.css}\"");
        assertThat(contributionsPage).contains("th:replace=\"~{wiki/public/fragments/personal-nav :: personalNav(activeTab='contributions')}\"");

        // 2. Breadcrumb
        assertThat(contributionsPage).contains("<span aria-current=\"page\">Đóng góp của tôi</span>");

        // 3. List iteration
        assertThat(contributionsPage).contains("th:if=\"${!contributionsPage.items.isEmpty()}\"");
        assertThat(contributionsPage).contains("th:each=\"item : ${contributionsPage.items}\"");

        // 4. Card elements: badges, article link, message, feedback
        assertThat(contributionsPage).contains("th:text=\"${item.contributionTypeLabel}\"");
        assertThat(contributionsPage).contains("th:classappend=\"${item.statusBadgeClass}\"");
        assertThat(contributionsPage).contains("th:text=\"${item.statusLabel}\"");
        assertThat(contributionsPage).contains("th:if=\"${item.hasArticleLink()}\"");
        assertThat(contributionsPage).contains("type=${item.liveArticleTypePath}, slug=${item.liveArticleSlug}");
        assertThat(contributionsPage).contains("th:text=\"${item.message}\"");
        assertThat(contributionsPage).contains("item.resolutionNote");

        // 5. Empty state
        assertThat(contributionsPage).contains("id=\"wikiContributionsEmpty\"");
        assertThat(contributionsPage).contains("th:if=\"${contributionsPage.items.isEmpty()}\"");
        assertThat(contributionsPage).contains("Bạn chưa gửi đóng góp Wiki nào.");
        assertThat(contributionsPage).contains("th:href=\"@{/wiki}\"");

        // 6. Pagination
        assertThat(contributionsPage).contains("th:if=\"${contributionsPage.totalPages > 1}\"");
        assertThat(contributionsPage).contains("contributionsPage.first");
        assertThat(contributionsPage).contains("contributionsPage.last");
        assertThat(contributionsPage).contains("contributionsPage.page - 1");
        assertThat(contributionsPage).contains("contributionsPage.page + 1");
    }

    private String read(String relativePath) throws Exception {
        return Files.readString(Path.of(relativePath), StandardCharsets.UTF_8).replace("\r\n", "\n");
    }
}
