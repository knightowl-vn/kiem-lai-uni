package com.universe.wiki.entry.web;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class PublicWikiReadingSettingsTemplateContractTest {

    @Test
    @DisplayName("Public Wiki detail page (detail.html) contains reading settings trigger, popover, font controls, and scripts")
    void wikiDetailPageIncludesReadingSettingsContract() throws Exception {
        String detailHtml = read("src/main/resources/templates/wiki/public/detail.html");

        // 1. Trigger & Popover
        assertThat(detailHtml).contains("id=\"wikiReadingSettingsTrigger\"");
        assertThat(detailHtml).contains("id=\"wikiReadingSettingsPopover\"");
        assertThat(detailHtml).contains("data-reading-settings-trigger");
        assertThat(detailHtml).contains("data-reading-settings-popover");

        // 2. Font Size Actions
        assertThat(detailHtml).contains("data-reading-font-action=\"decrease\"");
        assertThat(detailHtml).contains("data-reading-font-action=\"reset\"");
        assertThat(detailHtml).contains("data-reading-font-action=\"increase\"");

        // 3. Font Family Actions
        assertThat(detailHtml).contains("data-reading-font-family=\"serif\"");
        assertThat(detailHtml).contains("data-reading-font-family=\"sans\"");

        // 4. Scripts
        assertThat(detailHtml).contains("th:src=\"@{/js/reading/reading-preferences.js}\"");
        assertThat(detailHtml).contains("th:src=\"@{/js/wiki/wiki-reading-settings.js}\"");
    }

    @Test
    @DisplayName("wiki-reading-settings.js preserves click bubbling inside popover so shared reading-preferences.js can handle font changes")
    void wikiReadingSettingsJsContract() throws Exception {
        String js = read("src/main/resources/static/js/wiki/wiki-reading-settings.js");

        assertThat(js).contains("wikiReadingSettingsTrigger");
        assertThat(js).contains("wikiReadingSettingsPopover");
        assertThat(js).contains("!popover.contains(target) && !trigger.contains(target)");
        assertThat(js).doesNotContain("popover.addEventListener(\"click\", event => {\n            event.stopPropagation();\n        });");
    }

    @Test
    @DisplayName("wiki.css applies --font-reading and --reading-scale to .wiki-public-reading-content .wiki-article-content")
    void wikiCssAppliesReadingPreferencesToArticleContent() throws Exception {
        String css = read("src/main/resources/static/css/wiki/wiki.css");

        assertThat(css).contains("font-family: var(--font-reading);");
        assertThat(css).contains("font-size: calc(16px * var(--reading-scale, 1));");
    }

    private String read(String relativePath) throws Exception {
        return Files.readString(Path.of(relativePath), StandardCharsets.UTF_8).replace("\r\n", "\n");
    }
}