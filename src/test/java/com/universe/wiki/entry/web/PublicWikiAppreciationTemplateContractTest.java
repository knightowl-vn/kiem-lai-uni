package com.universe.wiki.entry.web;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("PublicWikiAppreciationTemplateContractTest — Public Wiki Appreciation Template & Asset Contracts")
class PublicWikiAppreciationTemplateContractTest {

    @Test
    @DisplayName("Public Wiki detail page (detail.html) contains appreciation widget markup, attributes and scripts")
    void detailPageContainsAppreciationWidgetContract() throws Exception {
        String detailPage = read("src/main/resources/templates/wiki/public/detail.html");

        // 1. Conditional CSS stylesheet link
        assertThat(detailPage).contains("th:if=\"${isAppreciationEligible}\"");
        assertThat(detailPage).contains("th:href=\"@{/css/wiki/wiki-appreciation.css}\"");

        // 2. Widget container and data contract attributes
        assertThat(detailPage).contains("id=\"wikiAppreciationWidget\"");
        assertThat(detailPage).contains("class=\"wiki-appreciation-widget\"");
        assertThat(detailPage).contains("th:if=\"${isAppreciationEligible and appreciationState != null}\"");
        assertThat(detailPage).contains("data-article-id=");
        assertThat(detailPage).contains("data-appreciation-url=@{/api/wiki/articles/{id}/appreciation(id=${article.id})}");
        assertThat(detailPage).contains("data-viewer-value=");
        assertThat(detailPage).contains("data-authenticated=");
        assertThat(detailPage).contains("data-login-url=@{/login(returnTo=${'/wiki/' + articleTypePath + '/' + article.slug})}");
        assertThat(detailPage).contains("data-csrf-token=");
        assertThat(detailPage).contains("data-csrf-header=");

        // 3. Header, stats & Vietnamese copy (structurally stable DOM for empty and populated states)
        assertThat(detailPage).contains("Mức độ yêu thích");
        assertThat(detailPage).contains("id=\"wikiAppreciationStats\"");
        assertThat(detailPage).contains("id=\"wikiAppreciationAverage\"");
        assertThat(detailPage).contains("th:hidden=\"${appreciationState.count == 0 or appreciationState.average == null}\"");
        assertThat(detailPage).contains("class=\"wiki-appreciation-separator\"");
        assertThat(detailPage).contains("id=\"wikiAppreciationCount\"");
        assertThat(detailPage).contains("class=\"wiki-appreciation-label\"");
        assertThat(detailPage).contains("class=\"wiki-appreciation-body\"");
        assertThat(detailPage).contains("appreciationState.displayAverage()");
        assertThat(detailPage).contains("lượt đánh giá");
        assertThat(detailPage).contains("Chưa có đánh giá");
        assertThat(detailPage).doesNotContain("th:if=\"${appreciationState.count > 0 and appreciationState.average != null}\"");

        // 4. Accessible half-star buttons (9 selectable half-star increments: 1.0 to 5.0)
        assertThat(detailPage).contains("class=\"wiki-appreciation-stars\"");
        assertThat(detailPage).contains("aria-label=\"Đánh giá mức độ yêu thích từ 1 đến 5 sao\"");
        assertThat(detailPage).contains("data-star-value=\"1.0\"");
        assertThat(detailPage).contains("data-star-value=\"1.5\"");
        assertThat(detailPage).contains("data-star-value=\"2.0\"");
        assertThat(detailPage).contains("data-star-value=\"2.5\"");
        assertThat(detailPage).contains("data-star-value=\"3.0\"");
        assertThat(detailPage).contains("data-star-value=\"3.5\"");
        assertThat(detailPage).contains("data-star-value=\"4.0\"");
        assertThat(detailPage).contains("data-star-value=\"4.5\"");
        assertThat(detailPage).contains("data-star-value=\"5.0\"");
        assertThat(detailPage).contains("aria-label=\"Yêu thích 1 trên 5\"");
        assertThat(detailPage).contains("aria-label=\"Yêu thích 1.5 trên 5\"");
        assertThat(detailPage).contains("aria-label=\"Yêu thích 2 trên 5\"");
        assertThat(detailPage).contains("aria-label=\"Yêu thích 2.5 trên 5\"");
        assertThat(detailPage).contains("aria-label=\"Yêu thích 3 trên 5\"");
        assertThat(detailPage).contains("aria-label=\"Yêu thích 3.5 trên 5\"");
        assertThat(detailPage).contains("aria-label=\"Yêu thích 4 trên 5\"");
        assertThat(detailPage).contains("aria-label=\"Yêu thích 4.5 trên 5\"");
        assertThat(detailPage).contains("aria-label=\"Yêu thích 5 trên 5\"");
        assertThat(detailPage).contains("th:aria-pressed=");

        // 5. Accessible feedback region
        assertThat(detailPage).contains("id=\"wikiAppreciationFeedback\"");
        assertThat(detailPage).contains("role=\"status\"");
        assertThat(detailPage).contains("aria-live=\"polite\"");

        // 6. Conditional JS script inclusion with defer
        assertThat(detailPage).contains("th:if=\"${isAppreciationEligible}\"");
        assertThat(detailPage).contains("th:src=\"@{/js/wiki/wiki-appreciation.js}\"");
        assertThat(detailPage).contains("defer");
    }

    @Test
    @DisplayName("wiki-appreciation.css defines required styles, enlarged 44px touch targets, active/hover states, and accessible focus")
    void cssStylesheetDefinesRequiredStyles() throws Exception {
        String css = read("src/main/resources/static/css/wiki/wiki-appreciation.css");

        assertThat(css).contains(".wiki-appreciation-widget");
        assertThat(css).contains(".wiki-star-btn");
        assertThat(css).contains(".wiki-star-btn.is-active");
        assertThat(css).contains(".wiki-star-btn.is-hover");
        assertThat(css).contains(":focus-visible");
        assertThat(css).contains(".wiki-star-icon");

        // Touch target sizing contract (WCAG / mobile hit area >= 44px height, 22px half / 44px full widths)
        assertThat(css).contains("min-height: 44px;");
        assertThat(css).contains("width: 44px;");
        assertThat(css).contains("width: 22px;");
        assertThat(css).contains(".wiki-star-btn--half-left {\n    width: 22px;\n    min-width: 22px;\n    justify-content: flex-end;");
        assertThat(css).contains(".wiki-star-btn--half-right {\n    width: 22px;\n    min-width: 22px;\n    justify-content: flex-start;");

        // MS-05F9 UX polish: compact side-by-side layout (no full-width space-between), bounded width, and mobile stacking
        assertThat(css).doesNotContain("justify-content: space-between;");
        assertThat(css).contains("width: fit-content;");
        assertThat(css).contains(".wiki-appreciation-body {\n    display: flex;\n    align-items: center;\n    gap: 1.25rem;\n}");
        assertThat(css).contains("@media (max-width: 767.98px)");
        assertThat(css).contains(".wiki-appreciation-body {\n        flex-direction: column;");
    }

    @Test
    @DisplayName("wiki-appreciation.js enforces PUT method, authentication check, CSRF tokens, redirect handling, and live updates")
    void javascriptFileEnforcesContract() throws Exception {
        String js = read("src/main/resources/static/js/wiki/wiki-appreciation.js");

        // Element lookup
        assertThat(js).contains("wikiAppreciationWidget");
        assertThat(js).contains("wiki-star-btn");

        // Mutation parameters
        assertThat(js).contains("method: 'PUT'");
        assertThat(js).contains("'Content-Type': 'application/json'");
        assertThat(js).contains("JSON.stringify({ value: starValue })");

        // Auth & CSRF
        assertThat(js).contains("data-authenticated");
        assertThat(js).contains("data-login-url");
        assertThat(js).contains("data-csrf-header");
        assertThat(js).contains("data-csrf-token");

        // Security redirect handling (detect followed redirects before calling response.json())
        assertThat(js).contains("response.redirected");
        assertThat(js).contains("/access-denied");

        // Live DOM updating
        assertThat(js).contains("is-active");
        assertThat(js).contains("aria-pressed");
        assertThat(js).contains("wikiAppreciationAverage");
        assertThat(js).contains("wikiAppreciationCount");
        assertThat(js).contains("Chưa có đánh giá");
        assertThat(js).contains("lượt đánh giá");
        assertThat(js).contains("Đã lưu đánh giá của bạn.");
        assertThat(js).doesNotContain("lượt yêu thích");
    }

    @Test
    @DisplayName("MS-05F6: Public Wiki index card enforces horizontal structure, bulk appreciation map lookup, and read-only semantics")
    void indexPageContainsHorizontalCardAndAppreciationContract() throws Exception {
        String indexPage = read("src/main/resources/templates/wiki/public/index.html");

        // 1. Horizontal card structural containers
        assertThat(indexPage).contains("class=\"wiki-public-index-card\"");
        assertThat(indexPage).contains("class=\"wiki-public-index-card-link\"");
        assertThat(indexPage).contains("class=\"wiki-public-index-card-media\"");
        assertThat(indexPage).contains("class=\"wiki-public-index-card-placeholder\"");
        assertThat(indexPage).contains("class=\"wiki-public-index-card-content\"");
        assertThat(indexPage).contains("class=\"wiki-public-index-card-appreciation\"");

        // 2. Badge & relative-time timestamp preserved
        assertThat(indexPage).contains("class=\"wiki-public-index-card-type\"");
        assertThat(indexPage).contains("data-relative-time");
        assertThat(indexPage).contains("th:datetime=\"${article.updatedAt}\"");

        // 3. Appreciation eligibility guard (presentation source-of-truth: map lookup, NOT duplicated article type)
        assertThat(indexPage).contains("th:with=\"summary=${appreciationSummaries[article.id]}\"");
        assertThat(indexPage).contains("th:if=\"${summary != null}\"");
        assertThat(indexPage).doesNotContain("article.articleType == 'CHARACTER'");
        assertThat(indexPage).doesNotContain("article.articleType == 'FACTION'");

        // 4. Score/count formatting and branch conditions
        assertThat(indexPage).contains("th:if=\"${summary.count > 0 and summary.average != null}\"");
        assertThat(indexPage).contains("th:if=\"${summary.count == 0 or summary.average == null}\"");
        assertThat(indexPage).contains("summary.displayAverage()");
        assertThat(indexPage).contains("/ 5");
        assertThat(indexPage).contains("lượt đánh giá");

        // 5. Empty state copy
        assertThat(indexPage).contains("Chưa có đánh giá");
        assertThat(indexPage).doesNotContain("lượt yêu thích");

        // 6. Read-only accessibility: decorative star is aria-hidden, no buttons, no interactive F5 star class
        assertThat(indexPage).contains("class=\"wiki-public-index-card-star-icon\"");
        assertThat(indexPage).contains("aria-hidden=\"true\"");
        assertThat(indexPage).doesNotContain("wiki-star-btn");
        assertThat(indexPage).doesNotContain("<button");
    }

    @Test
    @DisplayName("MS-05F6: wiki.css defines horizontal card grid, media placeholder, and responsive breakpoints")
    void wikiCssDefinesHorizontalCardStyles() throws Exception {
        String css = read("src/main/resources/static/css/wiki/wiki.css");

        assertThat(css).contains(".wiki-public-card-grid");
        assertThat(css).contains("flex-direction: column");
        assertThat(css).contains(".wiki-public-index-card-link");
        assertThat(css).contains(".wiki-public-index-card-media");
        assertThat(css).contains(".wiki-public-index-card-content");
        assertThat(css).contains(".wiki-public-index-card-appreciation");
        assertThat(css).contains(".wiki-public-index-card-star-icon");
        assertThat(css).contains("@media (max-width: 767.98px)");

        // Mobile /wiki header toolbar responsive vertical stacking
        assertThat(css).contains(".wiki-public-index-toolbar {\n\t\tflex-direction: column;\n\t\talign-items: stretch;");
        assertThat(css).contains(".wiki-public-index-tools {\n\t\twidth: 100%;\n\t\tflex-direction: column;");
    }

    private String read(String relativePath) throws Exception {
        return Files.readString(Path.of(relativePath), StandardCharsets.UTF_8).replace("\r\n", "\n");
    }
}
