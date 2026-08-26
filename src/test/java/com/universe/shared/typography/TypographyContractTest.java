package com.universe.shared.typography;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TypographyContractTest {

    @Test
    @DisplayName("typography.css định nghĩa đầy đủ 3 token CSS custom properties: --font-ui, --font-reading, --font-mono")
    void typographyTokensAreDefinedInRoot() throws Exception {
        String css = read("src/main/resources/static/css/typography.css");

        assertThat(css).contains(":root");
        assertThat(css).contains("--font-ui:");
        assertThat(css).contains("--font-reading:");
        assertThat(css).contains("--font-mono:");
        assertThat(css).contains("system-ui");
        assertThat(css).contains("\"Times New Roman\"");
        assertThat(css).contains("\"Cascadia Code\"");
    }

    @Test
    @DisplayName("Các root stylesheets (theme.css, dashboard.css, reader.css) tự động import typography.css; wiki/admin.css không import trùng lặp")
    void rootStylesheetsImportTypographyCss() throws Exception {
        String themeCss = read("src/main/resources/static/css/theme.css");
        assertThat(themeCss).contains("@import url('typography.css');");

        String dashboardCss = read("src/main/resources/static/css/admin/dashboard.css");
        assertThat(dashboardCss).contains("@import url('../typography.css');");

        String readerCss = read("src/main/resources/static/css/novel/reader.css");
        assertThat(readerCss).contains("@import url('../typography.css');");

        String wikiAdminCss = read("src/main/resources/static/css/wiki/admin.css");
        assertThat(wikiAdminCss).doesNotContain("@import url('../typography.css');");
    }

    @Test
    @DisplayName("Mọi template Wiki Admin đều luôn tải dashboard.css đi kèm wiki/admin.css")
    void allWikiAdminTemplatesAccompanyDashboardCss() throws Exception {
        List<String> wikiAdminTemplates = List.of(
                "src/main/resources/templates/admin/wiki/articles.html",
                "src/main/resources/templates/admin/wiki/create.html",
                "src/main/resources/templates/admin/wiki/detail.html",
                "src/main/resources/templates/admin/wiki/edit.html",
                "src/main/resources/templates/admin/wiki/revision-detail.html",
                "src/main/resources/templates/admin/wiki/revisions.html"
        );

        for (String templatePath : wikiAdminTemplates) {
            String content = read(templatePath);
            assertThat(content)
                    .as("Template %s must link dashboard.css", templatePath)
                    .contains("css/admin/dashboard.css");
            assertThat(content)
                    .as("Template %s must link wiki/admin.css", templatePath)
                    .contains("css/wiki/admin.css");
        }
    }

    @Test
    @DisplayName("reader.css sử dụng var(--font-ui) và var(--font-reading), không còn dùng Georgia cho văn bản tiếng Việt")
    void readerCssUsesCentralizedTypographyTokens() throws Exception {
        String readerCss = read("src/main/resources/static/css/novel/reader.css");

        // UI & Reading font variables are referenced
        assertThat(readerCss).contains("var(--font-ui)");
        assertThat(readerCss).contains("var(--font-reading)");

        // Chapter title and body prose use reading font
        assertThat(readerCss).contains(".novel-chapter-title");
        assertThat(readerCss).contains(".novel-reader-chapter-body");

        // Georgia is only allowed once as the intentional decorative exception for "劍"
        int georgiaCount = countOccurrences(readerCss, "Georgia");
        assertThat(georgiaCount).isEqualTo(1);
        assertThat(readerCss).contains(".novel-reader-cover-placeholder::before");
        assertThat(readerCss).contains("content: \"劍\";");

        // Hardcoded "Georgia, \"Times New Roman\", serif" must be completely removed
        assertThat(readerCss).doesNotContain("Georgia, \"Times New Roman\", serif");
    }

    @Test
    @DisplayName("dashboard.css và wiki/admin.css sử dụng các token typography tập trung")
    void adminStylesUseCentralizedTypographyTokens() throws Exception {
        String dashboardCss = read("src/main/resources/static/css/admin/dashboard.css");
        assertThat(dashboardCss).contains("var(--font-ui)");
        assertThat(dashboardCss).doesNotContain("Inter,");

        String wikiAdminCss = read("src/main/resources/static/css/wiki/admin.css");
        assertThat(wikiAdminCss).contains("var(--font-mono)");
        assertThat(wikiAdminCss).doesNotContain("\"Cascadia Code\", \"Consolas\", monospace");
    }

    @Test
    @DisplayName("Không còn thẻ <link> trực tiếp thừa typography.css trên các template chính (đã được nạp qua @import trong stylesheets)")
    void templatesDoNotContainRedundantDirectTypographyLinks() throws Exception {
        String homeHtml = read("src/main/resources/templates/home.html");
        assertThat(homeHtml).doesNotContain("typography.css");

        String novelIndexHtml = read("src/main/resources/templates/novel/index.html");
        assertThat(novelIndexHtml).doesNotContain("typography.css");

        String novelChapterHtml = read("src/main/resources/templates/novel/chapter.html");
        assertThat(novelChapterHtml).doesNotContain("typography.css");

        String adminDashboardHtml = read("src/main/resources/templates/admin/dashboard.html");
        assertThat(adminDashboardHtml).doesNotContain("typography.css");

        String adminProfileHtml = read("src/main/resources/templates/admin/novel/profile.html");
        assertThat(adminProfileHtml).doesNotContain("typography.css");

        String wikiIndexHtml = read("src/main/resources/templates/wiki/public/index.html");
        assertThat(wikiIndexHtml).doesNotContain("typography.css");
    }

    @Test
    @DisplayName("Văn bản văn xuôi dài (Wiki article và Novel chapter) sử dụng text-align: justify và hỗ trợ căn trái trên mobile hẹp")
    void longFormProseTypographyAlignmentRulesAreDefined() throws Exception {
        String wikiCss = read("src/main/resources/static/css/wiki/wiki.css");
        String readerCss = read("src/main/resources/static/css/novel/reader.css");

        // 1. Wiki article prose justification
        assertThat(wikiCss).contains(".wiki-article-content p {");
        assertThat(wikiCss).contains("text-align: justify;");
        assertThat(wikiCss).contains("text-justify: inter-word;");
        assertThat(wikiCss).contains("overflow-wrap: break-word;");

        // 2. Wiki non-prose elements left-aligned
        assertThat(wikiCss).contains("blockquote p");
        assertThat(wikiCss).contains("text-align: left;");

        // 3. Novel chapter body prose justification
        assertThat(readerCss).contains(".novel-reader-chapter-body p {");
        assertThat(readerCss).contains("text-align: justify;");
        assertThat(readerCss).contains("text-justify: inter-word;");
        assertThat(readerCss).contains("overflow-wrap: break-word;");

        // 4. Narrow mobile responsive left-align rules
        assertThat(wikiCss).contains("@media (max-width: 576px)");
        assertThat(readerCss).contains("@media (max-width: 576px)");
    }

    @Test
    @DisplayName("typography.css định nghĩa đầy đủ token reading serif, sans, scale và các selector data-reading-font")
    void readingFontTokensAndSwitchingRulesAreDefinedInTypographyCss() throws Exception {
        String css = read("src/main/resources/static/css/typography.css");

        // 1. Reading font tokens
        assertThat(css).contains("--font-reading-serif:");
        assertThat(css).contains("--font-reading-sans:");
        assertThat(css).contains("--font-reading:");
        assertThat(css).contains("--reading-scale:");

        // 2. Default is serif
        assertThat(css).contains("--font-reading:\n        var(--font-reading-serif);");

        // 3. Attribute switching selectors
        assertThat(css).contains("html[data-reading-font=\"sans\"]");
        assertThat(css).contains("--font-reading: var(--font-reading-sans);");
        assertThat(css).contains("html[data-reading-font=\"serif\"]");
        assertThat(css).contains("--font-reading: var(--font-reading-serif);");
    }

    @Test
    @DisplayName("reading-preferences.js quản lý cả font size, reading scale và font family với storage keys chuẩn")
    void readingPreferencesScriptDefinesSharedStateAndStorageContracts() throws Exception {
        String js = read("src/main/resources/static/js/reading/reading-preferences.js");

        // 1. Storage keys
        assertThat(js).contains("kiemlai:reading:font-size");
        assertThat(js).contains("kiemlai:reading:font-family");

        // 2. Scale derivation & CSS tokens
        assertThat(js).contains("--reading-scale");
        assertThat(js).contains("--reading-font-size");
        assertThat(js).contains("data-reading-font");
        assertThat(js).contains("deriveScale");

        // 3. Allowed fonts & default fallback
        assertThat(js).contains("[\"serif\", \"sans\"]");
        assertThat(js).contains("DEFAULT_FONT = \"serif\"");
        assertThat(js).contains("DEFAULT_SIZE = 16");

        // 4. Backward-compatible font action controls
        assertThat(js).contains("data-reading-font-action");
        assertThat(js).contains("decrease");
        assertThat(js).contains("reset");
        assertThat(js).contains("increase");

        // 5. Generic engine must NOT contain Wiki-specific DOM element IDs
        assertThat(js).doesNotContain("wikiReadingSettingsTrigger");
        assertThat(js).doesNotContain("wikiReadingSettingsPopover");
    }

    @Test
    @DisplayName("Wiki public detail template và wiki.css tích hợp đầy đủ hệ thống reading preferences (utility row, Aa trigger, popover, cỡ chữ, kiểu chữ serif/sans, scale 16px)")
    void wikiArticleDetailReadingPreferencesIntegrationContractTest() throws Exception {
        String detailHtml = read("src/main/resources/templates/wiki/public/detail.html");
        String wikiCss = read("src/main/resources/static/css/wiki/wiki.css");
        String wikiSettingsJs = read("src/main/resources/static/js/wiki/wiki-reading-settings.js");

        // 1. Utility row contains both back link and gear settings trigger
        assertThat(detailHtml).contains("class=\"wiki-public-utility-row\"");
        assertThat(detailHtml).contains("← Quay lại Wiki");
        assertThat(detailHtml).contains("class=\"wiki-reading-settings-trigger\"");
        assertThat(detailHtml).contains("id=\"wikiReadingSettingsTrigger\"");
        assertThat(detailHtml).contains("aria-label=\"Cài đặt đọc\"");
        assertThat(detailHtml).contains("aria-controls=\"wikiReadingSettingsPopover\"");
        assertThat(detailHtml).contains("aria-expanded=\"false\"");
        assertThat(detailHtml).contains("class=\"wiki-reading-settings-gear-icon\"");
        assertThat(detailHtml).contains("<svg");

        // Old visible Aa text is removed
        assertThat(detailHtml).doesNotContain("wiki-reading-settings-trigger-text");

        // 2. Old permanent toolbar is removed
        assertThat(detailHtml).doesNotContain("class=\"wiki-public-reading-toolbar\"");

        // 3. Settings popover contains font size and font family controls
        assertThat(detailHtml).contains("id=\"wikiReadingSettingsPopover\"");
        assertThat(detailHtml).contains("data-reading-font-action=\"decrease\"");
        assertThat(detailHtml).contains("data-reading-font-action=\"reset\"");
        assertThat(detailHtml).contains("data-reading-font-action=\"increase\"");
        assertThat(detailHtml).contains("data-reading-font-family=\"serif\"");
        assertThat(detailHtml).contains("data-reading-font-family=\"sans\"");
        assertThat(detailHtml).contains("aria-pressed=");

        // 4. Scripts are included
        assertThat(detailHtml).contains("th:src=\"@{/js/reading/reading-preferences.js}\"");
        assertThat(detailHtml).contains("th:src=\"@{/js/wiki/wiki-reading-settings.js}\"");
        assertThat(detailHtml).contains("th:src=\"@{/js/theme.js}\"");

        // 5. CSS definitions for utility row, popover, and prose
        assertThat(wikiCss).contains(".wiki-public-utility-row");
        assertThat(wikiCss).contains(".wiki-reading-settings-trigger");
        assertThat(wikiCss).contains(".wiki-reading-settings-gear-icon");
        assertThat(wikiCss).contains(".wiki-reading-settings-popover");
        assertThat(wikiCss).contains(".wiki-public-reading-content");
        assertThat(wikiCss).contains(".wiki-article-content");
        assertThat(wikiCss).contains("font-family: var(--font-reading);");
        assertThat(wikiCss).contains("font-size: calc(16px * var(--reading-scale, 1));");

        // 6. Wiki-specific UI script controls gear popover interactions
        assertThat(wikiSettingsJs).contains("wikiReadingSettingsTrigger");
        assertThat(wikiSettingsJs).contains("wikiReadingSettingsPopover");
        assertThat(wikiSettingsJs).contains("aria-expanded");
        assertThat(wikiSettingsJs).contains("Escape");
    }

    private String read(String relativePath) throws Exception {
        return Files.readString(Path.of(relativePath), StandardCharsets.UTF_8).replace("\r\n", "\n");
    }

    private int countOccurrences(String text, String target) {
        int count = 0;
        int idx = 0;
        while ((idx = text.indexOf(target, idx)) != -1) {
            count++;
            idx += target.length();
        }
        return count;
    }
}
