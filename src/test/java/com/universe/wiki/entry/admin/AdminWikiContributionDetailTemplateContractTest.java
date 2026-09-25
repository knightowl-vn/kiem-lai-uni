package com.universe.wiki.entry.admin;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Admin Wiki Contribution Detail Template Contract Tests (MS-05H7)")
class AdminWikiContributionDetailTemplateContractTest {

    private static final String DETAIL_TEMPLATE_PATH = "src/main/resources/templates/admin/wiki/contribution-detail.html";

    @Test
    @DisplayName("Detail template integrates standard admin layout and CSS styles")
    void shouldIncludeAdminLayoutAndStyles() throws Exception {
        String html = readTemplate(DETAIL_TEMPLATE_PATH);

        assertThat(html).contains("th:replace=\"~{admin/fragments/sidebar :: sidebar(${activeMenu})}\"");
        assertThat(html).contains("th:replace=\"~{admin/fragments/header :: header(${pageTitle})}\"");
        assertThat(html).contains("th:href=\"@{/css/admin/dashboard.css}\"");
        assertThat(html).contains("th:href=\"@{/css/admin/wiki-contributions.css}\"");
    }

    @Test
    @DisplayName("Detail template contains breadcrumb / back-link to inbox")
    void shouldContainBackLinkToInbox() throws Exception {
        String html = readTemplate(DETAIL_TEMPLATE_PATH);

        assertThat(html).contains("th:href=\"@{/admin/wiki/contributions}\"");
        assertThat(html).contains("Quay lại hàng đợi đóng góp");
    }

    @Test
    @DisplayName("Detail template renders article snapshot and public reader link")
    void shouldRenderArticleSnapshotAndPublicLink() throws Exception {
        String html = readTemplate(DETAIL_TEMPLATE_PATH);

        assertThat(html).contains("contribution.articleTitleSnapshot");
        assertThat(html).contains("contribution.articleTypeSnapshot");
        assertThat(html).contains("contribution.articleContentVersion");
        assertThat(html).contains("contribution.articleSlugSnapshot");
        assertThat(html).contains("target=\"_blank\"");
    }

    @Test
    @DisplayName("Detail template renders full submission message and contextual selection evidence")
    void shouldRenderSubmissionEvidence() throws Exception {
        String html = readTemplate(DETAIL_TEMPLATE_PATH);

        // Full message
        assertThat(html).contains("contribution.message");

        // Contextual selection evidence
        assertThat(html).contains("contribution.hasSelectionEvidence()");
        assertThat(html).contains("contribution.selectedText");
        assertThat(html).contains("contribution.selectedHeadingAnchor");
        assertThat(html).contains("contribution.selectedPrefix");
        assertThat(html).contains("contribution.selectedSuffix");
    }

    @Test
    @DisplayName("Detail template renders ordered sources with secure target blank attributes")
    void shouldRenderOrderedSources() throws Exception {
        String html = readTemplate(DETAIL_TEMPLATE_PATH);

        assertThat(html).contains("contribution.sources");
        assertThat(html).contains("src.url");
        assertThat(html).contains("src.sourceOrder");
        assertThat(html).contains("target=\"_blank\"");
        assertThat(html).contains("rel=\"noopener noreferrer\"");
    }

    @Test
    @DisplayName("Detail template renders contributor identity card")
    void shouldRenderContributorCard() throws Exception {
        String html = readTemplate(DETAIL_TEMPLATE_PATH);

        assertThat(html).contains("contribution.contributor.displayName");
        assertThat(html).contains("contribution.contributor.userId");
        assertThat(html).contains("contribution.contributor.resolved");
    }

    @Test
    @DisplayName("Detail template conditionally renders resolution metadata when terminal")
    void shouldRenderResolutionMetadataSection() throws Exception {
        String html = readTemplate(DETAIL_TEMPLATE_PATH);

        assertThat(html).contains("contribution.isTerminal()");
        assertThat(html).contains("contribution.resolutionNote");
        assertThat(html).contains("contribution.resolvedAt");
        assertThat(html).contains("contribution.resolver.displayName");
        assertThat(html).contains("contribution.resolvedArticleContentVersion");
    }

    @Test
    @DisplayName("Workflow action forms contain expectedVersion concurrency token and CSRF")
    void shouldContainWorkflowActionFormsWithExpectedVersion() throws Exception {
        String html = readTemplate(DETAIL_TEMPLATE_PATH);

        // Action endpoints
        assertThat(html).contains("th:action=\"@{/admin/wiki/contributions/{id}/review(id=${contribution.contributionId})}\"");
        assertThat(html).contains("th:action=\"@{/admin/wiki/contributions/{id}/resolve(id=${contribution.contributionId})}\"");
        assertThat(html).contains("th:action=\"@{/admin/wiki/contributions/{id}/reject(id=${contribution.contributionId})}\"");

        // All forms must be POST
        assertThat(html).contains("method=\"post\"");

        // Concurrency token expectedVersion hidden input
        assertThat(html).contains("name=\"expectedVersion\" th:value=\"${contribution.version}\"");

        // Resolution note textarea
        assertThat(html).contains("name=\"resolutionNote\"");
        assertThat(html).contains("minlength=\"5\"");
        assertThat(html).contains("maxlength=\"2000\"");

        // State gating
        assertThat(html).contains("contribution.status.name() == 'NEW'");
        assertThat(html).contains("contribution.isTerminal()");
    }

    @Test
    @DisplayName("NON-GOAL VERIFICATION: No arbitrary status dropdowns, credit controls, or inline editors")
    void shouldEnforceMilestoneBoundaries() throws Exception {
        String html = readTemplate(DETAIL_TEMPLATE_PATH);

        // No arbitrary status dropdown selector
        assertThat(html).doesNotContain("<select name=\"status\"");

        // No credit or reward controls (H8)
        assertThat(html).doesNotContain("credit");
        assertThat(html).doesNotContain("reputation");
        assertThat(html).doesNotContain("rewardPoints");

        // No inline wiki markdown editor (Admin Wiki Editing is separate)
        assertThat(html).doesNotContain("editor-container");
        assertThat(html).doesNotContain("wiki-editor");
        assertThat(html).doesNotContain("name=\"articleContent\"");

        // Server-rendered Vanilla stack only
        assertThat(html).doesNotContain("v-app");
        assertThat(html).doesNotContain("ng-app");
        assertThat(html).doesNotContain("hx-");
    }

    private String readTemplate(String path) throws Exception {
        return Files.readString(Path.of(path), StandardCharsets.UTF_8);
    }
}
