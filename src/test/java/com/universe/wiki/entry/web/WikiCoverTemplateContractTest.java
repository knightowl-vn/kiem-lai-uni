package com.universe.wiki.entry.web;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("WikiCoverTemplateContractTest — Admin & Public Wiki Cover Image Template Contracts")
class WikiCoverTemplateContractTest {

    private String read(String relativePath) throws Exception {
        return Files.readString(Path.of(relativePath), StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("Admin create.html form contains enctype multipart, coverImageFile, picker input and preview card")
    void createPageContainsCoverUploadContract() throws Exception {
        String page = read("src/main/resources/templates/admin/wiki/create.html");

        assertThat(page).contains("enctype=\"multipart/form-data\"");
        assertThat(page).contains("id=\"wikiCoverPickerInput\"");
        assertThat(page).contains("id=\"wikiCoverImage\"");
        assertThat(page).contains("th:field=\"*{coverImageFile}\"");
        assertThat(page).contains("accept=\"image/jpeg,image/png,image/webp\"");
        assertThat(page).contains("class=\"wiki-admin-cover-upload-card\"");
        assertThat(page).contains("wiki-admin-cover-compact-wrapper");
        assertThat(page).contains("class=\"wiki-admin-cover-placeholder\"");
    }

    @Test
    @DisplayName("Admin edit.html form contains enctype multipart, cover preview, upload and remove controls")
    void editPageContainsCoverManagementContract() throws Exception {
        String page = read("src/main/resources/templates/admin/wiki/edit.html");

        assertThat(page).contains("enctype=\"multipart/form-data\"");
        assertThat(page).contains("id=\"wikiCoverPickerInput\"");
        assertThat(page).contains("id=\"wikiCoverImage\"");
        assertThat(page).contains("th:field=\"*{coverImageFile}\"");
        assertThat(page).contains("accept=\"image/jpeg,image/png,image/webp\"");
        assertThat(page).contains("th:field=\"*{removeCover}\"");
        assertThat(page).contains("th:if=\"${article.coverMediaAssetId != null}\"");
        assertThat(page).contains("th:data-has-persisted-cover=\"${article.coverMediaAssetId != null}\"");
        assertThat(page).contains("article.displayCoverImageUrl()");
        assertThat(page).contains("article.fallbackCoverImageUrl()");
        assertThat(page).contains("class=\"wiki-admin-cover-upload-card\"");
    }

    @Test
    @DisplayName("Public index.html card renders cover image with fallback and preserves SVG placeholder")
    void publicIndexPageContainsCoverCardContract() throws Exception {
        String page = read("src/main/resources/templates/wiki/public/index.html");

        assertThat(page).contains("class=\"wiki-public-index-card-media\"");
        assertThat(page).contains("class=\"wiki-public-index-card-image\"");
        assertThat(page).contains("article.displayCoverImageUrl()");
        assertThat(page).contains("article.fallbackCoverImageUrl()");
        assertThat(page).contains("class=\"wiki-public-index-card-placeholder\"");
        assertThat(page).contains("th:if=\"${article.coverMediaAssetId != null}\"");
    }

    @Test
    @DisplayName("Public detail.html renders cover image in header with fallback when present")
    void publicDetailPageContainsCoverContract() throws Exception {
        String page = read("src/main/resources/templates/wiki/public/detail.html");

        assertThat(page).contains("class=\"wiki-public-cover-wrapper\"");
        assertThat(page).contains("class=\"wiki-public-cover-image\"");
        assertThat(page).contains("article.displayCoverImageUrl()");
        assertThat(page).contains("article.fallbackCoverImageUrl()");
        assertThat(page).contains("th:if=\"${article.coverMediaAssetId != null}\"");
    }

    @Test
    @DisplayName("MS-05G7.1: Admin create.html contains focal inputs, compact card, streamlined modal, and script inclusion")
    void createPageContainsFocalCompositionContract() throws Exception {
        String page = read("src/main/resources/templates/admin/wiki/create.html");

        assertThat(page).contains("th:field=\"*{coverPositionX}\"");
        assertThat(page).contains("th:field=\"*{coverPositionY}\"");
        assertThat(page).contains("id=\"wikiCoverCard\"");
        assertThat(page).contains("data-has-persisted-cover=\"false\"");
        assertThat(page).contains("Bỏ ảnh đã chọn");
        assertThat(page).contains("class=\"wiki-cover-compact-frame\"");
        assertThat(page).contains("data-action=\"choose-cover\"");
        assertThat(page).contains("id=\"wikiCoverModal\"");
        assertThat(page).contains("class=\"wiki-cover-workspace\"");
        assertThat(page).contains("class=\"wiki-cover-frame\"");
        assertThat(page).contains("class=\"wiki-cover-focal-badge\"");
        assertThat(page).contains("data-action=\"modal-reset\"");
        assertThat(page).contains("data-action=\"modal-apply\"");
        assertThat(page).doesNotContain("data-action=\"modal-browse\"");
        assertThat(page).doesNotContain("id=\"wikiCoverHasImageActions\"");
        assertThat(page).contains("wiki-cover-editor.js");
    }

    @Test
    @DisplayName("MS-05G7.1: Admin edit.html contains pencil menu, pending removal banner, streamlined modal, and script inclusion")
    void editPageContainsFocalCompositionContract() throws Exception {
        String page = read("src/main/resources/templates/admin/wiki/edit.html");

        assertThat(page).contains("th:field=\"*{coverPositionX}\"");
        assertThat(page).contains("th:field=\"*{coverPositionY}\"");
        assertThat(page).contains("id=\"wikiCoverCard\"");
        assertThat(page).contains("class=\"wiki-cover-compact-frame\"");
        assertThat(page).contains("id=\"wikiCoverEditBtn\"");
        assertThat(page).contains("id=\"wikiCoverActionMenu\"");
        assertThat(page).contains("data-action=\"update-cover\"");
        assertThat(page).contains("data-action=\"reposition-cover\"");
        assertThat(page).contains("data-action=\"remove-cover\"");
        assertThat(page).contains("id=\"wikiCoverPendingRemoval\"");
        assertThat(page).contains("data-action=\"undo-remove\"");
        assertThat(page).contains("id=\"wikiCoverModal\"");
        assertThat(page).contains("class=\"wiki-cover-workspace\"");
        assertThat(page).contains("class=\"wiki-cover-frame\"");
        assertThat(page).contains("class=\"wiki-cover-focal-badge\"");
        assertThat(page).contains("data-action=\"modal-reset\"");
        assertThat(page).contains("data-action=\"modal-apply\"");
        assertThat(page).doesNotContain("data-action=\"modal-browse\"");
        assertThat(page).doesNotContain("id=\"wikiCoverHasImageActions\"");
        assertThat(page).contains("wiki-cover-editor.js");
    }

    @Test
    @DisplayName("MS-05G7: Public index.html card binds coverObjectPosition(), top type badge, and bottom timestamp meta")
    void publicIndexPageContainsFocalCompositionContract() throws Exception {
        String page = read("src/main/resources/templates/wiki/public/index.html");

        assertThat(page).contains("article.coverObjectPosition()");
        assertThat(page).contains("class=\"wiki-public-index-card-media\"");
        assertThat(page).contains("class=\"wiki-public-index-card-top\"");
        assertThat(page).contains("class=\"wiki-public-index-card-type\"");
        assertThat(page).contains("class=\"wiki-public-index-card-meta\"");
    }

    @Test
    @DisplayName("MS-05G6: Public detail.html binds coverObjectPosition() and renders profile topbar + body")
    void publicDetailPageContainsProfileUxContract() throws Exception {
        String page = read("src/main/resources/templates/wiki/public/detail.html");

        assertThat(page).contains("article.coverObjectPosition()");
        assertThat(page).contains("class=\"wiki-public-profile-topbar\"");
        assertThat(page).contains("class=\"wiki-public-profile-body\"");
        assertThat(page).contains("class=\"wiki-public-profile-info\"");
    }

    @Test
    @DisplayName("MS-05G7.3: Admin CSS modal rules contain viewport-bound max-height, min-height: 0, and responsive workspace")
    void adminCssContainsViewportSafeModalContract() throws Exception {
        String css = read("src/main/resources/static/css/wiki/admin.css");

        assertThat(css).contains(".wiki-cover-modal");
        assertThat(css).contains(".wiki-cover-modal-dialog");
        assertThat(css).contains(".wiki-cover-modal-content");
        assertThat(css).contains(".wiki-cover-modal-body");
        assertThat(css).contains(".wiki-cover-workspace");
        assertThat(css).contains(".wiki-cover-frame");
        assertThat(css).contains(".wiki-cover-modal-footer");

        // Viewport-safe bounds
        assertThat(css).contains("max-height: calc(100dvh - 32px);");
        assertThat(css).contains("aspect-ratio: 4 / 5;");
        assertThat(css).contains("flex: 1 1 auto;");
        assertThat(css).contains("min-height: 0;");
        assertThat(css).doesNotContain("height: 480px;");
    }
}
