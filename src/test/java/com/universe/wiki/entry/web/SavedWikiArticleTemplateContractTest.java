package com.universe.wiki.entry.web;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("SavedWikiArticleTemplateContractTest — Template & Frontend Contract Tests")
class SavedWikiArticleTemplateContractTest {

    @Test
    @DisplayName("Public Wiki detail page (detail.html) tích hợp nút lưu bài viết và script wiki-saved.js")
    void detailPageIncludesSaveButtonAndScriptContract() throws Exception {
        String detailPage = read("src/main/resources/templates/wiki/public/detail.html");

        // 1. Save button in utility actions
        assertThat(detailPage).contains("id=\"wikiSaveArticleBtn\"");
        assertThat(detailPage).contains("class=\"wiki-save-btn\"");
        assertThat(detailPage).contains("data-article-id=");
        assertThat(detailPage).contains("data-save-url=");
        assertThat(detailPage).contains("data-saved=");
        assertThat(detailPage).contains("data-authenticated=");
        assertThat(detailPage).contains("data-login-url=@{/login(returnTo=${'/wiki/' + articleTypePath + '/' + article.slug})},");
        assertThat(detailPage).contains("data-csrf-token=");
        assertThat(detailPage).contains("data-csrf-header=");
        assertThat(detailPage).contains("wiki-save-icon-outline");
        assertThat(detailPage).contains("wiki-save-icon-filled");
        assertThat(detailPage).contains("th:text=\"${isSaved ? 'Đã lưu' : 'Lưu bài viết'}\"");

        // 2. wiki-saved.js included with defer
        assertThat(detailPage).contains("th:src=\"@{/js/wiki/wiki-saved.js}\"");
        assertThat(detailPage).contains("defer");
    }

    @Test
    @DisplayName("Saved Wiki articles list page (saved.html) định nghĩa danh sách, item khả dụng, tombstone không khả dụng, empty state và pagination")
    void savedListPageDefinesExpectedContract() throws Exception {
        String savedPage = read("src/main/resources/templates/wiki/public/saved.html");

        // 1. Shared navbar, stylesheet & personal navigation
        assertThat(savedPage).contains("th:replace=\"~{fragments/navbar :: navbar(activeNav='wiki')}\"");
        assertThat(savedPage).contains("th:href=\"@{/css/wiki/wiki.css}\"");
        assertThat(savedPage).contains("th:replace=\"~{wiki/public/fragments/personal-nav :: personalNav(activeTab='saved')}\"");

        // 2. List iteration & card structure
        assertThat(savedPage).contains("th:if=\"${!savedPage.items.isEmpty()}\"");
        assertThat(savedPage).contains("th:each=\"item : ${savedPage.items}\"");
        assertThat(savedPage).contains("class=\"wiki-public-index-card wiki-saved-card\"");

        // 3. Available branch renders canonical 4:5 media thumbnail, title, link, type, summary, appreciation stats
        assertThat(savedPage).contains("th:if=\"${item.available}\"");
        assertThat(savedPage).contains("wiki-public-index-card-media");
        assertThat(savedPage).contains("item.coverMediaAssetId != null");
        assertThat(savedPage).contains("item.displayCoverImageUrl()");
        assertThat(savedPage).contains("item.fallbackCoverImageUrl()");
        assertThat(savedPage).contains("wiki-public-index-card-placeholder");
        assertThat(savedPage).contains("wiki-public-index-card-type");
        assertThat(savedPage).contains("th:href=\"@{/wiki/{type}/{slug}(");
        assertThat(savedPage).contains("type=${item.articleTypePath}");
        assertThat(savedPage).doesNotContain("#strings.toLowerCase(#strings.replace(item.articleType");
        assertThat(savedPage).contains("th:text=\"${item.title}\"");
        assertThat(savedPage).contains("item.articleType");
        assertThat(savedPage).contains("item.summary");
        assertThat(savedPage).contains("wiki-public-index-card-appreciation");
        assertThat(savedPage).contains("wiki-public-index-card-star-icon");

        // 4. Saved actions footer (Read + Unsave buttons)
        assertThat(savedPage).contains("wiki-saved-card-footer");
        assertThat(savedPage).contains("wiki-saved-read-btn");
        assertThat(savedPage).contains("Xem bài viết");

        // 5. Unavailable branch renders generic tombstone without private metadata
        assertThat(savedPage).contains("th:unless=\"${item.available}\"");
        assertThat(savedPage).contains("wiki-saved-tombstone");
        assertThat(savedPage).contains("Bài viết không còn khả dụng");

        // 6. Unsave button on items
        assertThat(savedPage).contains("class=\"wiki-saved-remove-btn js-wiki-unsave-btn\"");
        assertThat(savedPage).contains("data-article-id=");
        assertThat(savedPage).contains("data-unsave-url=");
        assertThat(savedPage).contains("data-csrf-token=");
        assertThat(savedPage).contains("data-csrf-header=");

        // 7. Empty state & container current page data attribute
        assertThat(savedPage).contains("id=\"wikiSavedEmpty\"");
        assertThat(savedPage).contains("th:if=\"${savedPage.items.isEmpty()}\"");
        assertThat(savedPage).contains("Bạn chưa lưu bài viết Wiki nào.");
        assertThat(savedPage).contains("th:href=\"@{/wiki}\"");
        assertThat(savedPage).contains("data-current-page=");

        // 8. Pagination
        assertThat(savedPage).contains("th:if=\"${savedPage.totalPages > 1}\"");
        assertThat(savedPage).contains("savedPage.first");
        assertThat(savedPage).contains("savedPage.last");
        assertThat(savedPage).contains("savedPage.page - 1");
        assertThat(savedPage).contains("savedPage.page + 1");

        // 9. Script
        assertThat(savedPage).contains("th:src=\"@{/js/wiki/wiki-saved.js}\"");
        assertThat(savedPage).contains("defer");
    }

    @Test
    @DisplayName("Navbar fragment (navbar.html) chứa liên kết /wiki/saved trong menu tài khoản đã xác thực với nhãn Wiki của tôi")
    void navbarIncludesWikiSavedLink() throws Exception {
        String navbar = read("src/main/resources/templates/fragments/navbar.html");

        assertThat(navbar).contains("th:href=\"@{/wiki/saved}\"");
        assertThat(navbar).contains("Wiki của tôi");
    }

    @Test
    @DisplayName("wiki-saved.js xử lý đúng POST/DELETE, CSRF token, redirect đăng nhập, 204 success và xử lý 404")
    void wikiSavedJsScriptContract() throws Exception {
        String js = read("src/main/resources/static/js/wiki/wiki-saved.js");

        // Detail page toggle
        assertThat(js).contains("wikiSaveArticleBtn");
        assertThat(js).contains("isSaved ? 'DELETE' : 'POST'");
        assertThat(js).contains("data-csrf-header");
        assertThat(js).contains("data-csrf-token");
        assertThat(js).contains("data-authenticated");

        // List page unsave
        assertThat(js).contains("js-wiki-unsave-btn");
        assertThat(js).contains("method: 'DELETE'");

        // Safe redirect & status handling (never treat followed redirects as success)
        assertThat(js).contains("response.redirected");
        assertThat(js).contains("response.status === 204");
        assertThat(js).doesNotContain("response.ok || response.status === 204");
        assertThat(js).contains("response.status === 401");
        assertThat(js).contains("response.status === 404");

        // Last item pagination edge case handling
        assertThat(js).contains("data-current-page");
        assertThat(js).contains("currentPage > 0");
        assertThat(js).contains("/wiki/saved?page=");
    }

    private String read(String relativePath) throws Exception {
        return Files.readString(Path.of(relativePath), StandardCharsets.UTF_8).replace("\r\n", "\n");
    }
}
