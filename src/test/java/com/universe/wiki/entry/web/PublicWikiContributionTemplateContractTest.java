package com.universe.wiki.entry.web;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Public Wiki Contribution Template Contract Tests (MS-05H5)")
class PublicWikiContributionTemplateContractTest {

    private static final String TEMPLATE_PATH = "src/main/resources/templates/wiki/public/detail.html";

    @Test
    @DisplayName("Top action toolbar includes [Góp ý] button between Save button and reading settings gear")
    void topUtilityRowIncludesContributionButtonContract() throws Exception {
        String html = readTemplate();

        // 1. Element presence and attributes
        assertThat(html).contains("id=\"wikiTopContributionBtn\"");
        assertThat(html).contains("class=\"wiki-contribute-btn\"");
        assertThat(html).contains("data-article-id=${article.id}");
        assertThat(html).contains("data-content-version=${article.contentVersion}");
        assertThat(html).contains("data-authenticated=");
        assertThat(html).contains("data-login-url=");
        assertThat(html).contains("data-csrf-token=");
        assertThat(html).contains("data-csrf-header=");
        assertThat(html).contains("Góp ý");

        // 2. Relative placement between Save button and Reading Settings gear
        int saveBtnPos = html.indexOf("id=\"wikiSaveArticleBtn\"");
        int contributeBtnPos = html.indexOf("id=\"wikiTopContributionBtn\"");
        int gearTriggerPos = html.indexOf("id=\"wikiReadingSettingsTrigger\"");

        assertThat(saveBtnPos).isGreaterThan(0);
        assertThat(contributeBtnPos).isGreaterThan(saveBtnPos);
        assertThat(gearTriggerPos).isGreaterThan(contributeBtnPos);
    }

    @Test
    @DisplayName("Article body exposes data-article-id and data-content-version for selection snapshots")
    void articleContentExposesDataAttributesContract() throws Exception {
        String html = readTemplate();

        assertThat(html).contains("class=\"wiki-article-content\"");
        assertThat(html).contains("th:attr=\"data-article-id=${article.id}, data-content-version=${article.contentVersion}\"");
    }

    @Test
    @DisplayName("Floating text-selection toolbar includes [Tra cứu] and [Góp ý] buttons")
    void selectionToolbarContract() throws Exception {
        String html = readTemplate();

        assertThat(html).contains("id=\"wikiSelectionToolbar\"");
        assertThat(html).contains("class=\"wiki-selection-toolbar\"");
        assertThat(html).contains("id=\"wikiSelectionLookupBtn\"");
        assertThat(html).contains("Tra cứu");
        assertThat(html).contains("id=\"wikiSelectionContributeBtn\"");
        assertThat(html).contains("class=\"wiki-selection-divider\"");
    }

    @Test
    @DisplayName("Contextual lookup presentation container and backdrop exist for [Tra cứu]")
    void contextualLookupPresentationContract() throws Exception {
        String html = readTemplate();

        assertThat(html).contains("id=\"wikiContextualLookupBackdrop\"");
        assertThat(html).contains("class=\"wiki-contextual-lookup-backdrop\"");
        assertThat(html).contains("id=\"wikiContextualLookupContainer\"");
        assertThat(html).contains("class=\"wiki-contextual-lookup-container\"");
        assertThat(html).contains("id=\"wikiContextualLookupHeaderTitleText\"");
        assertThat(html).contains("id=\"wikiContextualLookupCloseBtn\"");
        assertThat(html).contains("id=\"wikiContextualLookupBody\"");
    }

    @Test
    @DisplayName("Reader contribution modal contains accessible dialog, preview, form controls, and options")
    void contributionModalContract() throws Exception {
        String html = readTemplate();

        // Dialog structure
        assertThat(html).contains("id=\"wikiContributionModal\"");
        assertThat(html).contains("class=\"wiki-contribution-modal\"");
        assertThat(html).contains("role=\"dialog\"");
        assertThat(html).contains("aria-modal=\"true\"");
        assertThat(html).contains("aria-labelledby=\"wikiContributionModalTitle\"");
        assertThat(html).contains("id=\"wikiContributionBackdrop\"");
        assertThat(html).contains("id=\"wikiContributionModalTitle\"");
        assertThat(html).contains("Góp ý bài viết");
        assertThat(html).contains("id=\"wikiContributionCloseBtn\"");

        // Form & Selection preview
        assertThat(html).contains("id=\"wikiContributionForm\"");
        assertThat(html).contains("id=\"wikiContributionSelectionPreview\"");
        assertThat(html).contains("id=\"wikiContributionSelectedText\"");
        assertThat(html).contains("id=\"wikiContributionAnchorHint\"");

        // Contribution type options
        assertThat(html).contains("id=\"wikiContributionTypeSelect\"");
        assertThat(html).contains("value=\"\" disabled selected>Chọn loại góp ý</option>");
        assertThat(html).contains("value=\"INCORRECT_INFORMATION\">Thông tin sai</option>");
        assertThat(html).contains("value=\"MISSING_INFORMATION\">Thiếu thông tin</option>");
        assertThat(html).contains("value=\"OUTDATED_INFORMATION\">Thông tin đã lỗi thời</option>");
        assertThat(html).contains("value=\"WORDING\">Câu chữ / cách diễn đạt</option>");
        assertThat(html).contains("value=\"SOURCE_REFERENCE\">Nguồn tham khảo</option>");
        assertThat(html).contains("value=\"OTHER\">Khác</option>");

        // Message input & counter
        assertThat(html).contains("id=\"wikiContributionMessageInput\"");
        assertThat(html).contains("minlength=\"20\"");
        assertThat(html).contains("maxlength=\"5000\"");
        assertThat(html).contains("id=\"wikiContributionMessageCounter\"");

        // Sources list & add button
        assertThat(html).contains("id=\"wikiContributionSourcesList\"");
        assertThat(html).contains("id=\"wikiContributionSourceCounter\"");
        assertThat(html).contains("id=\"wikiContributionAddSourceBtn\"");

        // Status & Action buttons
        assertThat(html).contains("id=\"wikiContributionStatus\"");
        assertThat(html).contains("id=\"wikiContributionCancelBtn\"");
        assertThat(html).contains("id=\"wikiContributionSubmitBtn\"");
    }

    @Test
    @DisplayName("Public Wiki detail page links wiki-contribution.css and wiki-contribution.js assets")
    void assetLinksContract() throws Exception {
        String html = readTemplate();

        assertThat(html).contains("th:href=\"@{/css/wiki/wiki-contribution.css}\"");
        assertThat(html).contains("th:src=\"@{/js/wiki/wiki-contribution.js}\"");
    }

    private String readTemplate() throws Exception {
        return Files.readString(Path.of(TEMPLATE_PATH), StandardCharsets.UTF_8).replace("\r\n", "\n");
    }
}
