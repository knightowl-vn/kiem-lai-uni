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
        assertThat(detailPage).contains("#numbers.formatDecimal(appreciationState.average, 1, 1)");
        assertThat(detailPage).contains("lượt yêu thích");
        assertThat(detailPage).contains("Chưa có lượt yêu thích");
        assertThat(detailPage).doesNotContain("th:if=\"${appreciationState.count > 0 and appreciationState.average != null}\"");

        // 4. Accessible 5-star buttons
        assertThat(detailPage).contains("class=\"wiki-appreciation-stars\"");
        assertThat(detailPage).contains("aria-label=\"Đánh giá mức độ yêu thích từ 1 đến 5 sao\"");
        assertThat(detailPage).contains("data-star-value=\"1\"");
        assertThat(detailPage).contains("data-star-value=\"2\"");
        assertThat(detailPage).contains("data-star-value=\"3\"");
        assertThat(detailPage).contains("data-star-value=\"4\"");
        assertThat(detailPage).contains("data-star-value=\"5\"");
        assertThat(detailPage).contains("aria-label=\"Yêu thích 1 trên 5\"");
        assertThat(detailPage).contains("aria-label=\"Yêu thích 2 trên 5\"");
        assertThat(detailPage).contains("aria-label=\"Yêu thích 3 trên 5\"");
        assertThat(detailPage).contains("aria-label=\"Yêu thích 4 trên 5\"");
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
    @DisplayName("wiki-appreciation.css defines required styles, active/hover states, and accessible focus")
    void cssStylesheetDefinesRequiredStyles() throws Exception {
        String css = read("src/main/resources/static/css/wiki/wiki-appreciation.css");

        assertThat(css).contains(".wiki-appreciation-widget");
        assertThat(css).contains(".wiki-star-btn");
        assertThat(css).contains(".wiki-star-btn.is-active");
        assertThat(css).contains(".wiki-star-btn.is-hover");
        assertThat(css).contains(":focus-visible");
        assertThat(css).contains(".wiki-star-icon");
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
        assertThat(js).contains("Chưa có lượt yêu thích");
        assertThat(js).contains("lượt yêu thích");
    }

    private String read(String relativePath) throws Exception {
        return Files.readString(Path.of(relativePath), StandardCharsets.UTF_8).replace("\r\n", "\n");
    }
}
