package com.universe.novel.entry.reader;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class ReaderReadingHistoryTemplateContractTest {

    @Test
    @DisplayName("Novel chapter reading page (chapter.html) tích hợp tracker và nạp reader-history.js cho người dùng đã đăng nhập")
    void chapterReadingPageIncludesHistoryTrackerAndScriptContract() throws Exception {
        String chapterPage = read("src/main/resources/templates/novel/chapter.html");

        // 1. History tracker element
        assertThat(chapterPage).contains("id=\"novelReadingHistoryTracker\"");
        assertThat(chapterPage).contains("sec:authorize=\"isAuthenticated()\"");
        assertThat(chapterPage).contains("data-chapter-id=");
        assertThat(chapterPage).contains("data-history-url=");
        assertThat(chapterPage).contains("data-csrf-token=");
        assertThat(chapterPage).contains("data-csrf-header=");

        // 2. reader-history.js script is included
        assertThat(chapterPage).contains("th:src=\"@{/js/novel/reader-history.js}\"");
        assertThat(chapterPage).contains("defer");
    }

    @Test
    @DisplayName("History list page (history.html) định nghĩa danh sách lịch sử, liên kết slug, metadata và empty state")
    void historyListPageDefinesExpectedContract() throws Exception {
        String historyPage = read("src/main/resources/templates/novel/reader/history.html");

        // 1. Navbar and styles
        assertThat(historyPage).contains("th:replace=\"~{fragments/navbar :: navbar(activeNav='novel')}\"");
        assertThat(historyPage).contains("th:href=\"@{/css/novel/reader-history.css}\"");

        // 2. History list iteration
        assertThat(historyPage).contains("th:if=\"${!#lists.isEmpty(historyList)}\"");
        assertThat(historyPage).contains("th:each=\"item : ${historyList}\"");
        assertThat(historyPage).contains("th:href=\"@{/novel/chapters/{slug}(slug=${item.chapterSlug})}\"");
        assertThat(historyPage).contains("item.chapterNumber");
        assertThat(historyPage).contains("item.chapterTitle");
        assertThat(historyPage).contains("item.volumeTitle");
        assertThat(historyPage).contains("item.lastReadAt");

        // 3. Read CTA button
        assertThat(historyPage).contains("class=\"novel-history-read-btn\"");

        // 4. Empty state
        assertThat(historyPage).contains("id=\"novelHistoryEmpty\"");
        assertThat(historyPage).contains("th:href=\"@{/novel}\"");
    }

    @Test
    @DisplayName("Navbar fragment (navbar.html) chứa liên kết /novel/history trong profile dropdown")
    void navbarIncludesHistoryLink() throws Exception {
        String navbar = read("src/main/resources/templates/fragments/navbar.html");

        assertThat(navbar).contains("th:href=\"@{/novel/history}\"");
        assertThat(navbar).contains("Lịch sử");
    }

    @Test
    @DisplayName("reader-history.js xử lý gửi request POST bất đồng bộ, kèm CSRF và lỗi không gián đoạn")
    void historyJsScriptContract() throws Exception {
        String js = read("src/main/resources/static/js/novel/reader-history.js");

        assertThat(js).contains("novelReadingHistoryTracker");
        assertThat(js).contains("tracker.dataset.csrfToken");
        assertThat(js).contains("tracker.dataset.csrfHeader");
        assertThat(js).contains("method: \"POST\"");
        assertThat(js).contains(".catch(");
    }

    private String read(String relativePath) throws Exception {
        return Files.readString(Path.of(relativePath), StandardCharsets.UTF_8).replace("\r\n", "\n");
    }
}
