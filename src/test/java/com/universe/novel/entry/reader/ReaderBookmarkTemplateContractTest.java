package com.universe.novel.entry.reader;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class ReaderBookmarkTemplateContractTest {

    @Test
    @DisplayName("Novel chapter reading page (chapter.html) tích hợp nút bookmark trong utility row và nạp reader-bookmark.js")
    void chapterReadingPageIncludesBookmarkButtonAndScriptContract() throws Exception {
        String chapterPage = read("src/main/resources/templates/novel/chapter.html");

        // 1. Bookmark button exists in utility actions
        assertThat(chapterPage).contains("id=\"novelChapterBookmarkBtn\"");
        assertThat(chapterPage).contains("sec:authorize=\"isAuthenticated()\"");
        assertThat(chapterPage).contains("class=\"novel-chapter-bookmark-btn\"");
        assertThat(chapterPage).contains("data-chapter-id=");
        assertThat(chapterPage).contains("data-bookmarked=");
        assertThat(chapterPage).contains("data-bookmark-url=");
        assertThat(chapterPage).contains("data-csrf-token=");
        assertThat(chapterPage).contains("data-csrf-header=");
        assertThat(chapterPage).contains("th:text=\"${isBookmarked ? 'Đã đánh dấu' : 'Đánh dấu'}\"");

        // 2. reader-bookmark.js is included with defer and restricted to authenticated users
        assertThat(chapterPage).contains("th:src=\"@{/js/novel/reader-bookmark.js}\"");
        assertThat(chapterPage).contains("defer");
    }

    @Test
    @DisplayName("Bookmarks list page (bookmarks.html) định nghĩa danh sách bookmark, liên kết slug, nút unbookmark, empty state và script")
    void bookmarksListPageDefinesExpectedContract() throws Exception {
        String bookmarksPage = read("src/main/resources/templates/novel/reader/bookmarks.html");

        // 1. Navbar and styles
        assertThat(bookmarksPage).contains("th:replace=\"~{fragments/navbar :: navbar(activeNav='novel')}\"");
        assertThat(bookmarksPage).contains("th:href=\"@{/css/novel/reader.css}\"");

        // 1b. Personal Area Navigation Switcher
        assertThat(bookmarksPage).contains("th:replace=\"~{novel/reader/fragments/personal-nav :: personalNav(activeTab='bookmarks')}\"");

        // 2. Bookmark list iteration
        assertThat(bookmarksPage).contains("th:if=\"${!#lists.isEmpty(bookmarks)}\"");
        assertThat(bookmarksPage).contains("th:each=\"bm : ${bookmarks}\"");
        assertThat(bookmarksPage).contains("th:href=\"@{/novel/chapters/{slug}(slug=${bm.chapterSlug})}\"");
        assertThat(bookmarksPage).contains("bm.chapterNumber");
        assertThat(bookmarksPage).contains("bm.chapterTitle");
        assertThat(bookmarksPage).contains("bm.volumeTitle");
        assertThat(bookmarksPage).contains("bm.bookmarkedAt");

        // 3. Remove bookmark button
        assertThat(bookmarksPage).contains("class=\"novel-bookmark-remove-btn js-novel-bookmark-remove-btn\"");
        assertThat(bookmarksPage).contains("data-unbookmark-url=");
        assertThat(bookmarksPage).contains("data-csrf-token=");
        assertThat(bookmarksPage).contains("data-csrf-header=");

        // 4. Empty state
        assertThat(bookmarksPage).contains("id=\"novelBookmarksEmpty\"");
        assertThat(bookmarksPage).contains("th:href=\"@{/novel}\"");

        // 5. Script
        assertThat(bookmarksPage).contains("th:src=\"@{/js/novel/reader-bookmark.js}\"");
        assertThat(bookmarksPage).contains("defer");
    }

    @Test
    @DisplayName("Personal Nav fragment (personal-nav.html) định nghĩa switcher giữa Lịch sử đọc và Dấu trang kèm aria-current và giữ nguyên ngữ nghĩa native link")
    void personalNavFragmentDefinesExpectedContract() throws Exception {
        String nav = read("src/main/resources/templates/novel/reader/fragments/personal-nav.html");

        assertThat(nav).contains("class=\"novel-personal-nav\"");
        assertThat(nav).contains("th:href=\"@{/novel/history}\"");
        assertThat(nav).contains("th:href=\"@{/novel/bookmarks}\"");
        assertThat(nav).contains("th:classappend=\"${activeTab == 'history'} ? ' is-active' : ''\"");
        assertThat(nav).contains("th:classappend=\"${activeTab == 'bookmarks'} ? ' is-active' : ''\"");
        assertThat(nav).contains("th:attr=\"aria-current=${activeTab == 'history' ? 'page' : null}\"");
        assertThat(nav).contains("th:attr=\"aria-current=${activeTab == 'bookmarks' ? 'page' : null}\"");
        assertThat(nav).contains("Lịch sử đọc");
        assertThat(nav).contains("Dấu trang");

        // Native link semantics: no role="list", role="listitem", or role="tab" overrides
        assertThat(nav).doesNotContain("role=\"list\"");
        assertThat(nav).doesNotContain("role=\"listitem\"");
        assertThat(nav).doesNotContain("role=\"tab\"");
    }

    @Test
    @DisplayName("Navbar fragment (navbar.html) dẫn tới khu vực Novel của tôi và không chứa liên kết dấu trang chi tiết trực tiếp")
    void navbarConsolidatesNovelPersonalLinkAndExcludesDirectBookmark() throws Exception {
        String navbar = read("src/main/resources/templates/fragments/navbar.html");

        assertThat(navbar).contains("th:href=\"@{/novel/history}\"");
        assertThat(navbar).contains("Novel của tôi");
        assertThat(navbar).doesNotContain("th:href=\"@{/novel/bookmarks}\"");
    }

    @Test
    @DisplayName("reader-bookmark.js xử lý đúng phương thức POST/DELETE, CSRF header và cập nhật UI")
    void bookmarkJsScriptContract() throws Exception {
        String js = read("src/main/resources/static/js/novel/reader-bookmark.js");

        assertThat(js).contains("novelChapterBookmarkBtn");
        assertThat(js).contains("isBookmarked ? 'DELETE' : 'POST'");
        assertThat(js).contains("data-csrf-header");
        assertThat(js).contains("data-csrf-token");
        assertThat(js).contains("js-novel-bookmark-remove-btn");
        assertThat(js).contains("method: 'DELETE'");
    }

    private String read(String relativePath) throws Exception {
        return Files.readString(Path.of(relativePath), StandardCharsets.UTF_8).replace("\r\n", "\n");
    }
}
