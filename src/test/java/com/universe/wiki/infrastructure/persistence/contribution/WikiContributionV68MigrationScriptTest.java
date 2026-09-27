package com.universe.wiki.infrastructure.persistence.contribution;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Wiki Contribution V68 Migration Script Tests")
class WikiContributionV68MigrationScriptTest {

    @Test
    @DisplayName("Verify V68 migration script adds multi-admin accountability, revision link, and workflow events table")
    void shouldVerifyV68MigrationScript() throws Exception {
        ClassPathResource resource = new ClassPathResource("db/migration/V68__add_multi_admin_review_accountability_and_attribution.sql");
        assertThat(resource.exists()).isTrue();

        String sql;
        try (InputStream is = resource.getInputStream()) {
            sql = new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }

        // Alter wiki_contributions statement
        assertThat(sql).contains("ALTER TABLE wiki_contributions");
        assertThat(sql).contains("assigned_to_user_id CHAR(36) NULL");
        assertThat(sql).contains("assigned_at DATETIME(6) NULL");
        assertThat(sql).contains("review_started_by_user_id CHAR(36) NULL");
        assertThat(sql).contains("review_started_at DATETIME(6) NULL");
        assertThat(sql).contains("review_started_article_content_version BIGINT NULL");
        assertThat(sql).contains("resolution_outcome VARCHAR(30) NULL");
        assertThat(sql).contains("chk_wiki_contributions_resolution_outcome");
        assertThat(sql).contains("idx_wiki_contributions_assignee");

        // Alter wiki_article_revisions statement
        assertThat(sql).contains("ALTER TABLE wiki_article_revisions");
        assertThat(sql).contains("source_contribution_id CHAR(36) NULL");
        assertThat(sql).contains("idx_wiki_article_revisions_source_contribution");

        // Create wiki_contribution_workflow_events table statement
        assertThat(sql).contains("CREATE TABLE wiki_contribution_workflow_events");
        assertThat(sql).contains("fk_wiki_contribution_events_contribution");
        assertThat(sql).contains("chk_wiki_contribution_events_type");
        assertThat(sql).contains("chk_wiki_contribution_events_outcome");
        assertThat(sql).contains("idx_wiki_contribution_events_timeline");

        // ON DELETE RESTRICT constraint verification
        assertThat(sql).contains("ON DELETE RESTRICT");

        // Ensure no cross-context foreign key to identity
        assertThat(sql).doesNotContain("FOREIGN KEY (assigned_to_user_id)");
        assertThat(sql).doesNotContain("FOREIGN KEY (actor_user_id)");
        assertThat(sql).doesNotContain("FOREIGN KEY (target_user_id)");
        assertThat(sql).doesNotContain("REFERENCES identity_users");

        // Ensure no credit fields (H8 non-goal)
        assertThat(sql).doesNotContain("credited");
        assertThat(sql).doesNotContain("credit");
    }

    @Test
    @DisplayName("Verify milestone migrations exist in classpath")
    void shouldVerifyMilestoneMigrationsExist() {
        assertThat(new ClassPathResource("db/migration/V67__add_wiki_contribution_workflow_metadata.sql").exists()).isTrue();
        assertThat(new ClassPathResource("db/migration/V66__create_wiki_contribution_sources.sql").exists()).isTrue();
        assertThat(new ClassPathResource("db/migration/V65__create_wiki_contributions.sql").exists()).isTrue();
        assertThat(new ClassPathResource("db/migration/V11__create_wiki_articles.sql").exists()).isTrue();
    }
}
