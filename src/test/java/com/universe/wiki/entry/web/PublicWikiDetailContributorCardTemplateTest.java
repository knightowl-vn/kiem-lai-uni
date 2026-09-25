package com.universe.wiki.entry.web;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("PublicWikiDetailContributorCardTemplateTest — Public Contributor Recognition Template Contract")
class PublicWikiDetailContributorCardTemplateTest {

    private String read(String relativePath) throws Exception {
        return Files.readString(Path.of(relativePath), StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("detail.html contains the visually separated public contributors card with required contract")
    void detailPageContainsContributorCardContract() throws Exception {
        String template = read("src/main/resources/templates/wiki/public/detail.html");

        // 1. Condition: hidden when empty
        assertThat(template).contains("th:if=\"${publicContributors != null and !#lists.isEmpty(publicContributors)}\"");

        // 2. Section card and title
        assertThat(template).contains("class=\"wiki-public-contributors-card\"");
        assertThat(template).contains("Người đóng góp bài viết");
        assertThat(template).contains("id=\"wikiContributorsHeading\"");

        // 3. Loop over publicContributors
        assertThat(template).contains("th:each=\"contributor : ${publicContributors}\"");

        // 4. Contributor display name and avatar rendering
        assertThat(template).contains("th:text=\"${contributor.displayName}\"");
        assertThat(template).contains("th:if=\"${contributor.avatarUrl != null and !contributor.avatarUrl.isBlank()}\"");
        assertThat(template).contains("th:src=\"${contributor.avatarUrl}\"");
        assertThat(template).contains("th:alt=\"${contributor.displayName}\"");
        assertThat(template).contains("th:unless=\"${contributor.avatarUrl != null and !contributor.avatarUrl.isBlank()}\"");

        // 5. Active credit count label
        assertThat(template).contains("th:text=\"${contributor.activeCreditCount == 1 ? '1 đóng góp được ghi nhận' : (contributor.activeCreditCount + ' đóng góp được ghi nhận')}\"");

        // 6. Privacy invariants: template must NEVER contain moderation metadata or public profile links
        assertThat(template).doesNotContain("contributor.creditedBy");
        assertThat(template).doesNotContain("contributor.creditNote");
        assertThat(template).doesNotContain("contributor.revokedBy");
        assertThat(template).doesNotContain("contributor.revocationReason");
        assertThat(template).doesNotContain("contributor.resolutionOutcome");
        assertThat(template).doesNotContain("contributor.userId");
        assertThat(template).doesNotContain("/users/");
        assertThat(template).doesNotContain("/u/");

        // 7. Separation from authorial attribution
        assertThat(template).contains("attributionLine");
    }

    @Test
    @DisplayName("wiki.css contains styles for public contributor card and responsive media queries")
    void stylesheetContainsContributorCardStyles() throws Exception {
        String css = read("src/main/resources/static/css/wiki/wiki.css");

        assertThat(css).contains(".wiki-public-contributors-card");
        assertThat(css).contains(".wiki-public-contributors-header");
        assertThat(css).contains(".wiki-public-contributors-title");
        assertThat(css).contains(".wiki-public-contributors-list");
        assertThat(css).contains(".wiki-public-contributor-item");
        assertThat(css).contains(".wiki-public-contributor-avatar");
        assertThat(css).contains(".wiki-public-contributor-name");
        assertThat(css).contains(".wiki-public-contributor-count");
    }
}
