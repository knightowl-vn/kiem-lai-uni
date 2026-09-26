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
        assertThat(html).contains("th:href=\"@{/admin/wiki/articles/{id}(id=${contribution.articleId})}\"");
        assertThat(html).contains("Xem trang quản trị bài viết ↗");
    }

    @Test
    @DisplayName("Detail template renders Step 1 edit link with sourceContributionId and feedback")
    void shouldRenderStep1EditLinkWithSourceContributionId() throws Exception {
        String html = readTemplate(DETAIL_TEMPLATE_PATH);

        assertThat(html).contains("th:href=\"@{/admin/wiki/articles/{id}/edit(id=${contribution.articleId}, sourceContributionId=${contribution.contributionId})}\"");
        assertThat(html).contains("Chỉnh sửa bài viết & liên kết đóng góp ↗");
        assertThat(html).contains("contribution.hasLinkedArticleUpdate()");
        assertThat(html).contains("contribution.getLatestLinkedArticleContentVersion()");
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
    @DisplayName("Credit attribution card renders states, forms, and validation rules (H8B)")
    void shouldRenderCreditAttributionSectionAndActions() throws Exception {
        String html = readTemplate(DETAIL_TEMPLATE_PATH);

        // Section title
        assertThat(html).contains("Ghi nhận công trạng");

        // Action endpoints
        assertThat(html).contains("th:action=\"@{/admin/wiki/contributions/{id}/credit(id=${contribution.contributionId})}\"");
        assertThat(html).contains("th:action=\"@{/admin/wiki/contributions/{id}/credit/revoke(id=${contribution.contributionId})}\"");

        // Credit note and revocation reason inputs
        assertThat(html).contains("name=\"creditNote\"");
        assertThat(html).contains("name=\"revocationReason\"");
        assertThat(html).contains("minlength=\"5\"");
        assertThat(html).contains("maxlength=\"1000\"");

        // State gating expressions
        assertThat(html).contains("contribution.isTerminal()");
        assertThat(html).contains("contribution.hasCredit()");
        assertThat(html).contains("contribution.credit.isActive()");
        assertThat(html).contains("contribution.credit.isRevoked()");
        assertThat(html).contains("contribution.canGrantCredit(currentAdmin)");
        assertThat(html).contains("contribution.canRevokeCredit(currentAdmin)");

        // Explanatory texts for all terminal states
        assertThat(html).contains("Đóng góp bị từ chối và không đủ điều kiện ghi nhận công trạng.");
        assertThat(html).contains("Đóng góp trùng lặp không đủ điều kiện ghi nhận công trạng.");
        assertThat(html).contains("Đóng góp cũ chưa có kết quả xử lý phù hợp để ghi nhận.");
        assertThat(html).contains("Chỉ quản trị viên đã giải quyết đóng góp hoặc SUPER_ADMIN mới có thể ghi nhận.");
        assertThat(html).contains("Công trạng đã bị thu hồi");
    }

    @Test
    @DisplayName("Credit forms must NOT contain expectedVersion or technical persistence version tokens")
    void creditFormsMustNotContainExpectedVersion() throws Exception {
        String html = readTemplate(DETAIL_TEMPLATE_PATH);

        int creditSectionStart = html.indexOf("SECTION G.1: CREDIT ATTRIBUTION");
        int creditSectionEnd = html.indexOf("SECTION H: WORKFLOW AUDIT EVENTS TIMELINE");
        assertThat(creditSectionStart).isGreaterThan(0);
        assertThat(creditSectionEnd).isGreaterThan(creditSectionStart);

        String creditSectionHtml = html.substring(creditSectionStart, creditSectionEnd);

        // Absolutely no version tokens in credit forms
        assertThat(creditSectionHtml).doesNotContain("expectedVersion");
        assertThat(creditSectionHtml).doesNotContain("persistence_version");
        assertThat(creditSectionHtml).doesNotContain("name=\"version\"");
    }

    @Test
    @DisplayName("Workflow timeline must NOT contain credit events")
    void workflowTimelineMustNotContainCreditEvents() throws Exception {
        String html = readTemplate(DETAIL_TEMPLATE_PATH);

        int timelineStart = html.indexOf("SECTION H: WORKFLOW AUDIT EVENTS TIMELINE");
        assertThat(timelineStart).isGreaterThan(0);
        String timelineHtml = html.substring(timelineStart);

        assertThat(timelineHtml).doesNotContain("CREDIT_GRANTED");
        assertThat(timelineHtml).doesNotContain("CREDIT_REVOKED");
    }

    @Test
    @DisplayName("NON-GOAL VERIFICATION: No arbitrary status dropdowns, gamification, or inline editors")
    void shouldEnforceMilestoneBoundaries() throws Exception {
        String html = readTemplate(DETAIL_TEMPLATE_PATH);

        // No arbitrary status dropdown selector
        assertThat(html).doesNotContain("<select name=\"status\"");

        // No gamification, public contributor card (H9), or anti-abuse features (H10)
        assertThat(html).doesNotContain("reputation");
        assertThat(html).doesNotContain("rewardPoints");
        assertThat(html).doesNotContain("contributor-card");
        assertThat(html).doesNotContain("leaderboard");
        assertThat(html).doesNotContain("gamification");

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
