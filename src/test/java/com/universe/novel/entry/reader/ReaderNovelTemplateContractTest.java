package com.universe.novel.entry.reader;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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

        // Cấu trúc phân cấp: novel-chapter-utility-row phải đóng hoàn toàn trước novel-chapter-header
        int utilityRowStart = chapterPage.indexOf("<div class=\"novel-chapter-utility-row\"");
        int headerStart = chapterPage.indexOf("<header class=\"novel-chapter-header\"");
        int navTopStart = chapterPage.indexOf("<nav class=\"novel-chapter-nav novel-chapter-nav--top\"");
        int articleStart = chapterPage.indexOf("<article class=\"novel-reader-chapter-body\"");
        int navBottomStart = chapterPage.indexOf("<nav class=\"novel-chapter-nav novel-chapter-nav--bottom\"");
        int mainEnd = chapterPage.indexOf("</main>");
        int backdropStart = chapterPage.indexOf("id=\"novelTocBackdrop\"");
        int drawerStart = chapterPage.indexOf("id=\"novelTocDrawer\"");

        assertThat(utilityRowStart).isGreaterThanOrEqualTo(0);
        assertThat(headerStart).isGreaterThan(utilityRowStart);
        assertThat(navTopStart).isGreaterThan(headerStart);
        assertThat(articleStart).isGreaterThan(navTopStart);
        assertThat(navBottomStart).isGreaterThan(articleStart);
        assertThat(mainEnd).isGreaterThan(navBottomStart);
        assertThat(backdropStart).isGreaterThan(mainEnd);
        assertThat(drawerStart).isGreaterThan(backdropStart);

        String beforeHeader = chapterPage.substring(utilityRowStart, headerStart);
        int openDivCount = countOccurrences(beforeHeader, "<div");
        int closeDivCount = countOccurrences(beforeHeader, "</div>");
        assertThat(closeDivCount)
                .as("All divs in utility row must be closed before chapter header")
                .isEqualTo(openDivCount);
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

        // 11. Stacking context contract: TOC drawer (1052) > TOC backdrop (1051) > main navbar (1050)
        String readerCss = read("src/main/resources/static/css/novel/reader.css");
        String navbarCss = read("src/main/resources/static/css/navbar.css");

        int backdropZIndex = extractZIndex(readerCss, "\\.novel-toc-backdrop\\s*\\{[^}]*z-index:\\s*(\\d+)");
        int drawerZIndex = extractZIndex(readerCss, "\\.novel-toc-drawer\\s*\\{[^}]*z-index:\\s*(\\d+)");
        int navbarZIndex = extractZIndex(navbarCss, "\\.profile-dropdown\\s*\\{[^}]*z-index:\\s*(\\d+)");

        assertThat(backdropZIndex)
                .as(".novel-toc-backdrop must define z-index: 1051")
                .isEqualTo(1051);
        assertThat(drawerZIndex)
                .as(".novel-toc-drawer must define z-index: 1052")
                .isEqualTo(1052);
        assertThat(navbarZIndex)
                .as("navbar/profile-dropdown baseline z-index")
                .isEqualTo(1050);

        assertThat(drawerZIndex)
                .as("TOC drawer z-index must be strictly greater than TOC backdrop z-index")
                .isGreaterThan(backdropZIndex);
        assertThat(backdropZIndex)
                .as("TOC backdrop z-index must be strictly greater than main navbar z-index")
                .isGreaterThan(navbarZIndex);
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

        // 6. UI script manages popover interactions without blocking document-level preference listeners
        assertThat(novelSettingsJs).contains("novelReadingSettingsTrigger");
        assertThat(novelSettingsJs).contains("novelReadingSettingsPopover");
        assertThat(novelSettingsJs).contains("aria-expanded");
        assertThat(novelSettingsJs).contains("Escape");
        assertThat(novelSettingsJs).doesNotContain("popover.addEventListener(\"click\"");

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
    @DisplayName("Novel reader landing page (index.html) chứa hero actions với nút Đọc tiếp (nhãn ngắn 'Đọc tiếp', thông tin chương ở bên ngoài), và Bắt đầu đọc điều hướng trực tiếp tới firstChapter hoặc fallback an toàn #toc")
    void landingPageIncludesHeroActionsContract() throws Exception {
        String index = read("src/main/resources/templates/novel/index.html");
        String readerCss = read("src/main/resources/static/css/novel/reader.css");

        // 1. Hero actions container
        assertThat(index).contains("class=\"novel-reader-hero-actions\"");
        assertThat(index).contains("id=\"continueReadingBtn\"");
        assertThat(index).contains("id=\"startReadingBtn\"");

        // 2. Continue Reading button label is concise "Đọc tiếp"
        assertThat(index).contains("<span>Đọc tiếp</span>");

        // 3. Secondary Chapter details and highest reached progress exist outside button
        assertThat(index).contains("class=\"novel-reader-continue-info\"");
        assertThat(index).contains("class=\"novel-reader-continue-chapter\"");
        assertThat(index).contains("class=\"novel-reader-continue-highest\"");
        assertThat(index).contains("continueReading.chapterNumber");
        assertThat(index).contains("continueReading.title");
        assertThat(index).contains("continueReading.highestReachedChapterNumber");

        // 4. Start Reading navigates directly to firstChapter when available
        assertThat(index).contains("th:if=\"${firstChapter != null}\"");
        assertThat(index).contains("th:href=\"@{/novel/chapters/{slug}(slug=${firstChapter.slug})}\"");

        // 5. Start Reading safely falls back to #toc when no readable chapters exist
        assertThat(index).contains("th:unless=\"${firstChapter != null}\"");
        assertThat(index).contains("href=\"#toc\"");

        // 6. CSS definitions
        assertThat(readerCss).contains(".novel-reader-hero-actions");
        assertThat(readerCss).contains(".novel-reader-btn");
        assertThat(readerCss).contains(".novel-reader-btn-primary");
        assertThat(readerCss).contains(".novel-reader-continue-info");
        assertThat(readerCss).contains(".novel-reader-continue-chapter");
        assertThat(readerCss).contains(".novel-reader-continue-highest");
    }

    @Test
    @DisplayName("Novel chapter reading page (chapter.html) chứa tracking container cho authenticated user và nạp reader-progress.js từ đúng đường dẫn tĩnh")
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
        assertThat(progressJs).contains("tracker.dataset.chapterId");
        assertThat(progressJs).contains("tracker.dataset.csrfToken");
        assertThat(progressJs).contains("tracker.dataset.csrfHeader");
        assertThat(progressJs).contains("encodeURIComponent(chapterId)");
        assertThat(progressJs).contains("/progress");
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

    private int extractZIndex(String cssContent, String regexPattern) {
        Matcher matcher = Pattern.compile(regexPattern).matcher(cssContent);
        assertThat(matcher.find())
                .as("CSS must match pattern: " + regexPattern)
                .isTrue();
        return Integer.parseInt(matcher.group(1));
    }
}
