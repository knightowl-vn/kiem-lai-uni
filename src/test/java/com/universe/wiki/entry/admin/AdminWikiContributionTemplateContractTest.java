package com.universe.wiki.entry.admin;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Admin Wiki Contribution Template Contract Tests (MS-05H6)")
class AdminWikiContributionTemplateContractTest {

    private static final String INBOX_TEMPLATE_PATH = "src/main/resources/templates/admin/wiki/contributions.html";
    private static final String SIDEBAR_TEMPLATE_PATH = "src/main/resources/templates/admin/fragments/sidebar.html";

    @Test
    @DisplayName("Inbox template uses standard admin layout and CSS styles")
    void shouldIncludeAdminLayoutAndStyles() throws Exception {
        String html = readTemplate(INBOX_TEMPLATE_PATH);

        assertThat(html).contains("th:replace=\"~{admin/fragments/sidebar :: sidebar(${activeMenu})}\"");
        assertThat(html).contains("th:replace=\"~{admin/fragments/header :: header(${pageTitle})}\"");
        assertThat(html).contains("th:href=\"@{/css/admin/dashboard.css}\"");
        assertThat(html).contains("th:href=\"@{/css/admin/wiki-contributions.css}\"");
    }

    @Test
    @DisplayName("Status tabs cover NEW, REVIEWING, RESOLVED, REJECTED, ALL and include count badge")
    void shouldRenderStatusTabs() throws Exception {
        String html = readTemplate(INBOX_TEMPLATE_PATH);

        assertThat(html).contains("class=\"wiki-contributions-tabs\"");
        assertThat(html).contains("status='NEW'");
        assertThat(html).contains("status='REVIEWING'");
        assertThat(html).contains("status='RESOLVED'");
        assertThat(html).contains("status='REJECTED'");
        assertThat(html).contains("status='ALL'");

        // Badge for count of NEW contributions
        assertThat(html).contains("th:if=\"${newContributionCount != null and newContributionCount > 0}\"");
        assertThat(html).contains("th:text=\"${newContributionCount}\"");
    }

    @Test
    @DisplayName("Filter form submits GET request with keyword, type, and status")
    void shouldRenderFilterForm() throws Exception {
        String html = readTemplate(INBOX_TEMPLATE_PATH);

        assertThat(html).contains("method=\"get\"");
        assertThat(html).contains("th:action=\"@{/admin/wiki/contributions}\"");
        assertThat(html).contains("name=\"status\"");
        assertThat(html).contains("name=\"keyword\"");
        assertThat(html).contains("name=\"type\"");
        assertThat(html).contains("th:each=\"cType : ${contributionTypes}\"");
    }

    @Test
    @DisplayName("Contribution card renders snapshot fields, context, message, contributor, and sources")
    void shouldRenderContributionCardFields() throws Exception {
        String html = readTemplate(INBOX_TEMPLATE_PATH);

        // Snapshot fields
        assertThat(html).contains("row.item.articleTypeSnapshot");
        assertThat(html).contains("row.item.articleTitleSnapshot");
        assertThat(html).contains("row.item.articleContentVersion");
        assertThat(html).contains("row.item.articleSlugSnapshot");

        // Context and Type badges
        assertThat(html).contains("row.item.contextType");
        assertThat(html).contains("row.item.contributionType");

        // Evidence indicator and message preview (full evidence deferred to H7 detail)
        assertThat(html).contains("row.item.hasSelectedText");
        assertThat(html).contains("row.item.messagePreview");
        assertThat(html).doesNotContain("row.item.selectedText");
        assertThat(html).doesNotContain("row.item.selectedPrefix");
        assertThat(html).doesNotContain("row.item.selectedSuffix");
        assertThat(html).doesNotContain("row.item.selectedHeadingAnchor");

        // Contributor and sources
        assertThat(html).contains("row.contributor.displayName");
        assertThat(html).contains("row.item.submittedByUserId");
        assertThat(html).contains("row.item.hasSources");
        assertThat(html).contains("row.item.sourceCount");
    }

    @Test
    @DisplayName("CRITICAL: Inbox is strictly READ ONLY - contains ZERO workflow mutation buttons or action forms")
    void shouldNotContainAnyWorkflowMutationButtons() throws Exception {
        String html = readTemplate(INBOX_TEMPLATE_PATH);

        // No resolve, reject, reviewing action endpoints or workflow mutation forms
        assertThat(html).doesNotContain("/resolve");
        assertThat(html).doesNotContain("/reject");
        assertThat(html).doesNotContain("/review");
        assertThat(html).doesNotContain("resolutionNote");
        assertThat(html).doesNotContain("action=\"/admin/wiki/contributions/resolve");
        assertThat(html).doesNotContain("action=\"/admin/wiki/contributions/reject");
        assertThat(html).doesNotContain("action=\"/admin/wiki/contributions/review");

        // No POST/PUT/DELETE forms
        assertThat(html).doesNotContain("method=\"post\"");
        assertThat(html).doesNotContain("method=\"put\"");
        assertThat(html).doesNotContain("method=\"delete\"");
    }

    @Test
    @DisplayName("Sidebar template includes discoverable link and badge for Wiki Contributions")
    void shouldIncludeSidebarLinkAndBadge() throws Exception {
        String html = readTemplate(SIDEBAR_TEMPLATE_PATH);

        assertThat(html).contains("th:href=\"@{/admin/wiki/contributions}\"");
        assertThat(html).contains("Đóng góp Wiki");
        assertThat(html).contains("activeMenu == 'wiki-contributions'");
        assertThat(html).contains("th:if=\"${newContributionCount != null and newContributionCount > 0}\"");
    }

    private String readTemplate(String path) throws Exception {
        return Files.readString(Path.of(path), StandardCharsets.UTF_8);
    }
}
