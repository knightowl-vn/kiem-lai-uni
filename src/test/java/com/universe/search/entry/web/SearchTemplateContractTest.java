package com.universe.search.entry.web;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("SearchTemplateContractTest — Template & Frontend Contract Tests")
class SearchTemplateContractTest {

    @Test
    @DisplayName("Search index template (search/index.html) định nghĩa SEO robots noindex,follow và nạp đúng stylesheet hệ thống")
    void searchPageHasRobotsNoindexFollowAndStylesheets() throws Exception {
        String template = read("src/main/resources/templates/search/index.html");

        // 1. SEO noindex, follow
        assertThat(template).contains("<meta name=\"robots\" content=\"noindex,follow\">");
        assertThat(template).doesNotContain("rel=\"canonical\"");

        // 2. Shared navbar included with activeNav='search'
        assertThat(template).contains("th:replace=\"~{fragments/navbar :: navbar(activeNav='search')}\"");

        // 3. Stylesheets: theme, navbar, wiki, reader, search
        assertThat(template).contains("th:href=\"@{/css/theme.css}\"");
        assertThat(template).contains("th:href=\"@{/css/navbar.css}\"");
        assertThat(template).contains("th:href=\"@{/css/wiki/wiki.css}\"");
        assertThat(template).contains("th:href=\"@{/css/novel/reader.css}\"");
        assertThat(template).contains("th:href=\"@{/css/search/search.css}\"");
    }

    @Test
    @DisplayName("Search index template (search/index.html) KHÔNG chứa form tìm kiếm thứ hai hoặc query input trong thân trang")
    void searchPageHasNoBodySearchFormContract() throws Exception {
        String template = read("src/main/resources/templates/search/index.html");

        // Thân trang không được chứa form tìm kiếm trùng lặp
        assertThat(template).doesNotContain("<form");
        assertThat(template).doesNotContain("role=\"search\"");
        assertThat(template).doesNotContain("id=\"searchQuery\"");
        assertThat(template).doesNotContain("name=\"q\"");
        assertThat(template).doesNotContain("search-submit-btn");
    }

    @Test
    @DisplayName("Wiki results tái sử dụng cấu trúc và CSS card Wiki chuẩn của hệ thống")
    void wikiCardStructureContract() throws Exception {
        String template = read("src/main/resources/templates/search/index.html");

        // Card container & markup classes from wiki.css
        assertThat(template).contains("class=\"wiki-public-card-grid\"");
        assertThat(template).contains("class=\"wiki-public-index-card\"");
        assertThat(template).contains("class=\"wiki-public-index-card-link\"");
        assertThat(template).contains("class=\"wiki-public-index-card-media\"");
        assertThat(template).contains("class=\"wiki-public-index-card-image\"");
        assertThat(template).contains("class=\"wiki-public-index-card-placeholder\"");
        assertThat(template).contains("class=\"wiki-public-index-card-content\"");
        assertThat(template).contains("class=\"wiki-public-index-card-type\"");
        assertThat(template).contains("class=\"wiki-public-index-card-title\"");
        assertThat(template).contains("class=\"wiki-public-index-card-summary\"");
        assertThat(template).contains("class=\"wiki-public-index-card-meta\"");

        // Presentation helpers on item
        assertThat(template).contains("item.displayCoverImageUrl()");
        assertThat(template).contains("item.fallbackCoverImageUrl()");
        assertThat(template).contains("item.coverObjectPosition()");
    }

    @Test
    @DisplayName("Chỉ báo 'Khớp danh xưng' trên Wiki card chỉ hiển thị khi item.matchedAlias != null")
    void wikiMatchedAliasIndicatorContract() throws Exception {
        String template = read("src/main/resources/templates/search/index.html");

        assertThat(template).contains("item.matchedAlias != null and !item.matchedAlias.isBlank()");
        assertThat(template).contains("search-matched-alias-badge");
        assertThat(template).contains("'Khớp danh xưng: ' + item.matchedAlias");
    }

    @Test
    @DisplayName("Novel results tái sử dụng cấu trúc lưới chương novel-reader-chapter-grid chuẩn của hệ thống, không chứa badge tìm kiếm tùy tiện")
    void novelChapterGridStructureContract() throws Exception {
        String template = read("src/main/resources/templates/search/index.html");

        assertThat(template).contains("class=\"novel-reader-chapter-grid\"");
        assertThat(template).contains("class=\"novel-reader-chapter-item\"");
        assertThat(template).contains("class=\"novel-reader-chapter-number\"");
        assertThat(template).contains("class=\"novel-reader-chapter-title\"");
        assertThat(template).doesNotContain("search-chapter-exact-badge");
        assertThat(template).doesNotContain("Khớp số chương");
    }

    @Test
    @DisplayName("Scope navigation tabs định nghĩa 3 scope (all, wiki, novel), bảo toàn normalized query và aria-current")
    void scopeNavigationContract() throws Exception {
        String template = read("src/main/resources/templates/search/index.html");

        assertThat(template).contains("th:href=\"@{/search(q=${query}, scope='all')}\"");
        assertThat(template).contains("th:href=\"@{/search(q=${query}, scope='wiki')}\"");
        assertThat(template).contains("th:href=\"@{/search(q=${query}, scope='novel')}\"");

        assertThat(template).contains("th:attr=\"aria-current=${scope == 'all' ? 'page' : null}\"");
        assertThat(template).contains("th:attr=\"aria-current=${scope == 'wiki' ? 'page' : null}\"");
        assertThat(template).contains("th:attr=\"aria-current=${scope == 'novel' ? 'page' : null}\"");
    }

    @Test
    @DisplayName("Template hiển thị prompt state khi query rỗng và empty state khi không có kết quả")
    void blankAndEmptyStatesContract() throws Exception {
        String template = read("src/main/resources/templates/search/index.html");

        // Blank query prompt
        assertThat(template).contains("th:if=\"${query == null or query.isBlank()}\"");
        assertThat(template).contains("class=\"search-prompt-state\"");

        // Non-blank area
        assertThat(template).contains("th:if=\"${query != null and !query.isBlank()}\"");
        assertThat(template).contains("class=\"search-empty-state\"");
    }

    @Test
    @DisplayName("Wiki và Novel results được render thành 2 nhóm riêng biệt, liên kết chuẩn canonical")
    void resultGroupsAndCanonicalLinksContract() throws Exception {
        String template = read("src/main/resources/templates/search/index.html");

        // Wiki section
        assertThat(template).contains("search-group-wiki");
        assertThat(template).contains("th:href=\"${item.canonicalUrl}\"");

        // Novel section
        assertThat(template).contains("search-group-novel");
        assertThat(template).contains("th:href=\"@{/novel/chapters/{slug}(slug=${ch.slug})}\"");
    }

    @Test
    @DisplayName("Shared navbar (navbar.html) điều hướng linh hoạt: /novel/locate khi activeNav='novel', /search cho các scope khác")
    void navbarSearchIntegrationContract() throws Exception {
        String navbar = read("src/main/resources/templates/fragments/navbar.html");

        assertThat(navbar).contains("th:action=\"@{${activeNav == 'novel' ? '/novel/locate' : '/search'}}\"");
        assertThat(navbar).contains("name=\"q\"");
        assertThat(navbar).contains("th:if=\"${activeNav != 'novel'}\"");
        assertThat(navbar).contains("name=\"scope\"");
        assertThat(navbar).contains("th:value=\"${navbarSearchScope != null and !navbarSearchScope.isBlank() ? navbarSearchScope : (activeNav == 'wiki' ? 'wiki' : 'all')}\"");
        assertThat(navbar).contains("th:value=\"${navbarSearchQuery != null ? navbarSearchQuery : ''}\"");
    }

    @Test
    @DisplayName("Wiki results render cột appreciation summary khi summary != null (đầy đủ sao, điểm trung bình, số lượt đánh giá hoặc trạng thái chưa có đánh giá)")
    void wikiCardAppreciationContract() throws Exception {
        String template = read("src/main/resources/templates/search/index.html");

        assertThat(template).contains("appreciationSummaries != null ? appreciationSummaries[item.id] : null");
        assertThat(template).contains("class=\"wiki-public-index-card-appreciation\"");
        assertThat(template).contains("class=\"wiki-public-index-card-appreciation-stats\"");
        assertThat(template).contains("class=\"wiki-public-index-card-rating-row\"");
        assertThat(template).contains("class=\"wiki-public-index-card-star-icon\"");
        assertThat(template).contains("class=\"wiki-public-index-card-rating-avg\"");
        assertThat(template).contains("summary.displayAverage()");
        assertThat(template).contains("class=\"wiki-public-index-card-rating-scale\"");
        assertThat(template).contains("class=\"wiki-public-index-card-rating-count\"");
        assertThat(template).contains("class=\"wiki-public-index-card-appreciation-empty\"");
        assertThat(template).contains("Chưa có đánh giá");
    }

    private String read(String relativePath) throws Exception {
        return Files.readString(Path.of(relativePath), StandardCharsets.UTF_8).replace("\r\n", "\n");
    }
}
