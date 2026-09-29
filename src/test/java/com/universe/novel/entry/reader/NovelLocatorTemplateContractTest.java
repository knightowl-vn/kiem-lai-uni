package com.universe.novel.entry.reader;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("NovelLocatorTemplateContractTest — Novel Locator Template & DOM ID Contracts")
class NovelLocatorTemplateContractTest {

    @Test
    @DisplayName("Chapter list template (chapter-list.html) định nghĩa deterministic id='chapter-{chapterNumber}' trên thẻ anchor")
    void chapterListTemplateRendersDeterministicAnchorId() throws Exception {
        String template = read("src/main/resources/templates/novel/chapter-list.html");

        // 1. Thẻ <a> chứa th:id="chapter-${chapter.chapterNumber}"
        assertThat(template).contains("th:id=\"${'chapter-' + chapter.chapterNumber}\"");

        // 2. Canonical href và CSS class chuẩn được bảo toàn
        assertThat(template).contains("th:href=\"@{/novel/chapters/{slug}(slug=${chapter.slug})}\"");
        assertThat(template).contains("class=\"novel-reader-chapter-item\"");

        // 3. Không chứa class highlight, selected hay found badge tùy tiện
        assertThat(template).doesNotContain("novel-reader-chapter-highlight");
        assertThat(template).doesNotContain("novel-reader-chapter-selected");
        assertThat(template).doesNotContain("novel-reader-chapter-found");
        assertThat(template).doesNotContain("search-chapter-exact-badge");
    }

    @Test
    @DisplayName("Novel index template (novel/index.html) render data-volume-id và data-volume-sort-order cho accordion locator")
    void novelIndexTemplateVolumeAccordionAttributesContract() throws Exception {
        String template = read("src/main/resources/templates/novel/index.html");

        assertThat(template).contains("data-volume-id=${volume.id}");
        assertThat(template).contains("data-volume-sort-order=${volume.sortOrder}");
        assertThat(template).contains("class=\"novel-reader-volume-trigger\"");
        assertThat(template).contains("class=\"novel-reader-volume-content\"");
    }

    @Test
    @DisplayName("Shared navbar (navbar.html) cấu hình action /novel/locate khi activeNav='novel' và /search cho context khác")
    void navbarActionDispatchContract() throws Exception {
        String navbar = read("src/main/resources/templates/fragments/navbar.html");

        // Form action dynamic
        assertThat(navbar).contains("th:action=\"@{${activeNav == 'novel' ? '/novel/locate' : '/search'}}\"");

        // Scope hidden input chỉ render khi activeNav != 'novel'
        assertThat(navbar).contains("th:if=\"${activeNav != 'novel'}\"");
        assertThat(navbar).contains("name=\"scope\"");
        assertThat(navbar).contains("th:value=\"${navbarSearchScope != null and !navbarSearchScope.isBlank() ? navbarSearchScope : (activeNav == 'wiki' ? 'wiki' : 'all')}\"");

        // Input q
        assertThat(navbar).contains("name=\"q\"");
        assertThat(navbar).contains("id=\"navbarWikiKeyword\"");
    }

    private String read(String relativePath) throws Exception {
        return Files.readString(Path.of(relativePath), StandardCharsets.UTF_8).replace("\r\n", "\n");
    }
}
