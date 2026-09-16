package com.universe.novel.entry.reader;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class ReaderChapterContentVersionTemplateContractTest {

    @Test
    @DisplayName("Novel chapter reading page (chapter.html) exposes data-chapter-id and data-content-version on chapter body")
    void chapterReadingPageExposesContentVersionContract() throws Exception {
        String chapterPage = read("src/main/resources/templates/novel/chapter.html");

        assertThat(chapterPage).contains("<article class=\"novel-reader-chapter-body\"");
        assertThat(chapterPage).contains("th:attr=\"data-chapter-id=${chapter.id},data-content-version=${chapter.contentVersion}\"");
        assertThat(chapterPage).contains("th:utext=\"${chapter.contentHtml}\"");
    }

    private String read(String relativePath) throws Exception {
        return Files.readString(Path.of(relativePath), StandardCharsets.UTF_8).replace("\r\n", "\n");
    }
}
