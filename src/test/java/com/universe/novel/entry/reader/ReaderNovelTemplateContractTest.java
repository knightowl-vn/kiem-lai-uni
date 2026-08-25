package com.universe.novel.entry.reader;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class ReaderNovelTemplateContractTest {

    @Test
    @DisplayName("Novel reader landing page (index.html) nạp shared navbar fragment với activeNav='novel', script reader-accordion.js với defer và có đầy đủ markup accordion accessibility")
    void landingPageIncludesReaderAccordionScriptAndAccessibilityMarkup() throws Exception {
        String index = read("src/main/resources/templates/novel/index.html");

        assertThat(index).contains("th:replace=\"~{fragments/navbar :: navbar(activeNav='novel')}\"");
        assertThat(index).contains("th:href=\"@{/css/navbar.css}\"");
        assertThat(index).contains("th:src=\"@{/js/novel/reader-accordion.js}\"");
        assertThat(index).contains("defer");
        assertThat(index).contains("class=\"novel-reader\"");
        assertThat(index).contains("class=\"novel-reader-hero\"");
        assertThat(index).contains("class=\"novel-reader-toc\"");
        assertThat(index).contains("class=\"novel-reader-volume-trigger\"");
        assertThat(index).contains("aria-expanded=\"false\"");
        assertThat(index).contains("aria-controls=");
        assertThat(index).contains("data-volume-id=");
        assertThat(index).contains("class=\"novel-reader-volume-content\"");
        assertThat(index).contains("hidden");
        assertThat(index).contains("th:href=\"@{/css/novel/reader.css}\"");
    }

    @Test
    @DisplayName("Novel chapter list fragment (chapter-list.html) định nghĩa fragment chapterList và liên kết chapter slug")
    void chapterListFragmentDefinesExpectedStructure() throws Exception {
        String fragment = read("src/main/resources/templates/novel/chapter-list.html");

        assertThat(fragment).contains("th:fragment=\"chapterList\"");
        assertThat(fragment).contains("class=\"novel-reader-chapter-list\"");
        assertThat(fragment).contains("class=\"novel-reader-chapter-grid\"");
        assertThat(fragment).contains("class=\"novel-reader-chapter-item\"");
        assertThat(fragment).contains("th:href=\"@{/novel/chapters/{slug}(slug=${chapter.slug})}\"");
        assertThat(fragment).contains("class=\"novel-reader-chapter-number\"");
        assertThat(fragment).contains("class=\"novel-reader-chapter-title\"");
    }

    @Test
    @DisplayName("Novel chapter reading page (chapter.html) chứa breadcrumb, tiêu đề, navigation và body utext")
    void chapterReadingPageDefinesExpectedStructure() throws Exception {
        String chapterPage = read("src/main/resources/templates/novel/chapter.html");

        assertThat(chapterPage).contains("class=\"novel-chapter-reading\"");
        assertThat(chapterPage).contains("class=\"novel-chapter-breadcrumb\"");
        assertThat(chapterPage).contains("class=\"novel-chapter-header\"");
        assertThat(chapterPage).contains("class=\"novel-chapter-volume-tag\"");
        assertThat(chapterPage).contains("class=\"novel-chapter-title\"");
        assertThat(chapterPage).contains("class=\"novel-chapter-nav novel-chapter-nav--top\"");
        assertThat(chapterPage).contains("class=\"novel-chapter-nav novel-chapter-nav--bottom\"");
        assertThat(chapterPage).contains("th:href=\"@{/novel/chapters/{slug}(slug=${chapter.previousChapter.slug})}\"");
        assertThat(chapterPage).contains("th:href=\"@{/novel/chapters/{slug}(slug=${chapter.nextChapter.slug})}\"");
        assertThat(chapterPage).contains("th:href=\"@{/novel}\"");
        assertThat(chapterPage).contains("class=\"novel-reader-chapter-body\"");
        assertThat(chapterPage).contains("th:utext=\"${chapter.contentHtml}\"");
        assertThat(chapterPage).contains("th:href=\"@{/css/novel/reader.css}\"");

        // Tái sử dụng shared navbar fragment với activeNav='novel'
        assertThat(chapterPage).contains("th:replace=\"~{fragments/navbar :: navbar(activeNav='novel')}\"");
        assertThat(chapterPage).contains("th:href=\"@{/css/navbar.css}\"");
    }

    @Test
    @DisplayName("Novel chapter reading page (chapter.html) chứa trigger Mục lục (button), Drawer duy nhất, danh sách TOC lặp chapter.tableOfContents và nạp reader-chapter-toc.js")
    void chapterReadingPageIncludesTableOfContentsDrawerContract() throws Exception {
        String chapterPage = read("src/main/resources/templates/novel/chapter.html");

        // 1. Triggers are buttons with type="button"
        assertThat(chapterPage).contains("button type=\"button\"");
        assertThat(chapterPage).contains("js-novel-toc-trigger");

        // 2. Trigger references the drawer through aria-controls
        assertThat(chapterPage).contains("aria-controls=\"novelTocDrawer\"");
        assertThat(chapterPage).contains("aria-expanded=\"false\"");

        // 3. Drawer exists exactly once
        assertThat(countOccurrences(chapterPage, "id=\"novelTocDrawer\"")).isEqualTo(1);
        assertThat(countOccurrences(chapterPage, "id=\"novelTocBackdrop\"")).isEqualTo(1);
        assertThat(chapterPage).contains("role=\"dialog\"");
        assertThat(chapterPage).contains("aria-modal=\"true\"");
        assertThat(chapterPage).contains("aria-label=\"Mục lục chương\"");
        assertThat(chapterPage).contains("id=\"novelTocCloseBtn\"");
        assertThat(chapterPage).contains("aria-label=\"Đóng mục lục\"");

        // 4. TOC iterates chapter.tableOfContents
        assertThat(chapterPage).contains("th:each=\"tocItem : ${chapter.tableOfContents}\"");

        // 5. TOC links use each item's slug
        assertThat(chapterPage).contains("th:href=\"@{/novel/chapters/{slug}(slug=${tocItem.slug})}\"");

        // 6. Active Chapter comparison exists
        assertThat(chapterPage).contains("tocItem.slug == chapter.slug");

        // 7. aria-current active-state contract exists
        assertThat(chapterPage).contains("aria-current");

        // 8. Scrollable TOC structure/class exists
        assertThat(chapterPage).contains("class=\"novel-toc-scrollable\"");
        assertThat(chapterPage).contains("class=\"novel-toc-list\"");
        assertThat(chapterPage).contains("class=\"novel-toc-item\"");
        assertThat(chapterPage).contains("class=\"novel-toc-link\"");

        // 9. Dedicated JS file is included with defer
        assertThat(chapterPage).contains("th:src=\"@{/js/novel/reader-chapter-toc.js}\"");
        assertThat(chapterPage).contains("defer");

        // 10. TOC drawer contains a clear return link to /novel with "Về trang Novel" text
        assertThat(chapterPage).contains("class=\"novel-toc-back-link\"");
        assertThat(chapterPage).contains("Về trang Novel");
    }

    @Test
    @DisplayName("Novel chapter reading page (chapter.html) và reader.css tích hợp utility row, gear trigger, popover cài đặt đọc (cỡ chữ scale 18px, kiểu chữ serif/sans, giao diện sáng/tối, theme.js và reading-preferences.js), đồng thời giữ TOC thuần túy điều hướng chương")
    void chapterReadingPageIntegratesReadingPreferencesAndThemeContract() throws Exception {
        String chapterPage = read("src/main/resources/templates/novel/chapter.html");
        String readerCss = read("src/main/resources/static/css/novel/reader.css");
        String novelSettingsJs = read("src/main/resources/static/js/novel/reader-reading-settings.js");

        // 1. Utility row contains both breadcrumb and gear settings trigger
        assertThat(chapterPage).contains("class=\"novel-chapter-utility-row\"");
        assertThat(chapterPage).contains("class=\"novel-chapter-breadcrumb\"");
        assertThat(chapterPage).contains("class=\"novel-reading-settings-trigger\"");
        assertThat(chapterPage).contains("id=\"novelReadingSettingsTrigger\"");
        assertThat(chapterPage).contains("aria-label=\"Cài đặt đọc\"");
        assertThat(chapterPage).contains("aria-controls=\"novelReadingSettingsPopover\"");
        assertThat(chapterPage).contains("aria-expanded=\"false\"");
        assertThat(chapterPage).contains("class=\"novel-reading-settings-gear-icon\"");
        assertThat(chapterPage).contains("<svg");

        // 2. Exactly one settings trigger exists on page
        assertThat(countOccurrences(chapterPage, "id=\"novelReadingSettingsTrigger\"")).isEqualTo(1);

        // 3. TOC Drawer contains NO reading settings
        assertThat(chapterPage).doesNotContain("class=\"novel-toc-settings\"");

        // 4. Popover contains font size, font family, and theme controls
        assertThat(chapterPage).contains("id=\"novelReadingSettingsPopover\"");
        assertThat(chapterPage).contains("data-reading-font-action=\"decrease\"");
        assertThat(chapterPage).contains("data-reading-font-action=\"reset\"");
        assertThat(chapterPage).contains("data-reading-font-action=\"increase\"");
        assertThat(chapterPage).contains("data-reading-font-family=\"serif\"");
        assertThat(chapterPage).contains("data-reading-font-family=\"sans\"");
        assertThat(chapterPage).contains("data-theme-set=\"light\"");
        assertThat(chapterPage).contains("data-theme-set=\"dark\"");
        assertThat(chapterPage).contains("aria-pressed=");

        // 5. Loads shared preference engine, theme, and novel UI controller scripts
        assertThat(chapterPage).contains("th:src=\"@{/js/theme.js}\"");
        assertThat(chapterPage).contains("th:src=\"@{/js/reading/reading-preferences.js}\"");
        assertThat(chapterPage).contains("th:src=\"@{/js/novel/reader-reading-settings.js}\"");

        // 6. UI script manages popover interactions
        assertThat(novelSettingsJs).contains("novelReadingSettingsTrigger");
        assertThat(novelSettingsJs).contains("novelReadingSettingsPopover");
        assertThat(novelSettingsJs).contains("aria-expanded");
        assertThat(novelSettingsJs).contains("Escape");

        // 7. reader.css consumes reading-scale with 18px base, reading font, and utility row/popover styling
        assertThat(readerCss).contains(".novel-chapter-utility-row");
        assertThat(readerCss).contains(".novel-reading-settings-trigger");
        assertThat(readerCss).contains(".novel-reading-settings-popover");
        assertThat(readerCss).contains(".novel-reader-chapter-body");
        assertThat(readerCss).contains("font-family: var(--font-reading);");
        assertThat(readerCss).contains("font-size: calc(18px * var(--reading-scale, 1));");

        // 8. reader.css defines dark mode theme token support
        assertThat(readerCss).contains("html[data-theme=\"dark\"]");
        assertThat(readerCss).contains("html[data-bs-theme=\"dark\"]");
    }

    @Test
    @DisplayName("Novel reader landing page (index.html) chứa hero actions với nút Đọc tiếp (khi có continueReading) và Bắt đầu đọc (khi chưa có continueReading)")
    void landingPageIncludesHeroActionsContract() throws Exception {
        String index = read("src/main/resources/templates/novel/index.html");
        String readerCss = read("src/main/resources/static/css/novel/reader.css");

        assertThat(index).contains("class=\"novel-reader-hero-actions\"");
        assertThat(index).contains("id=\"continueReadingBtn\"");
        assertThat(index).contains("id=\"startReadingBtn\"");
        assertThat(index).contains("class=\"novel-reader-continue-info\"");
        assertThat(index).contains("id=\"toc\"");

        assertThat(readerCss).contains(".novel-reader-hero-actions");
        assertThat(readerCss).contains(".novel-reader-btn");
        assertThat(readerCss).contains(".novel-reader-btn-primary");
        assertThat(readerCss).contains(".novel-reader-continue-info");
    }

    @Test
    @DisplayName("Novel chapter reading page (chapter.html) chứa tracking container cho authenticated user và nạp reader-progress.js")
    void chapterReadingPageIncludesReadingProgressContract() throws Exception {
        String chapterPage = read("src/main/resources/templates/novel/chapter.html");
        String progressJs = read("src/main/resources/static/js/novel/reader-progress.js");

        assertThat(chapterPage).contains("id=\"novelReadingProgressTracker\"");
        assertThat(chapterPage).contains("sec:authorize=\"isAuthenticated()\"");
        assertThat(chapterPage).contains("data-chapter-id=");
        assertThat(chapterPage).contains("data-csrf-token=");
        assertThat(chapterPage).contains("data-csrf-header=");
        assertThat(chapterPage).contains("th:src=\"@{/js/novel/reader-progress.js}\"");
        assertThat(chapterPage).contains("defer");

        assertThat(progressJs).contains("novelReadingProgressTracker");
        assertThat(progressJs).contains("encodeURIComponent(chapterId)");
        assertThat(progressJs).contains("/progress");
    }

    private String read(String relativePath) throws Exception {
        return Files.readString(Path.of(relativePath), StandardCharsets.UTF_8);
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
