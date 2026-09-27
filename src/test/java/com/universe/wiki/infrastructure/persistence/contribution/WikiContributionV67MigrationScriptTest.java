package com.universe.wiki.infrastructure.persistence.contribution;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Wiki Contribution V67 Migration Script Tests")
class WikiContributionV67MigrationScriptTest {

    @Test
    @DisplayName("Verify V67 migration script adds workflow metadata columns to wiki_contributions table")
    void shouldVerifyV67MigrationScript() throws Exception {
        ClassPathResource resource = new ClassPathResource("db/migration/V67__add_wiki_contribution_workflow_metadata.sql");
        assertThat(resource.exists()).isTrue();

        String sql;
        try (InputStream is = resource.getInputStream()) {
            sql = new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }

        // Alter table statement
        assertThat(sql).contains("ALTER TABLE wiki_contributions");

        // Columns added
        assertThat(sql).contains("resolution_note VARCHAR(2000) NULL");
        assertThat(sql).contains("resolved_by_user_id CHAR(36) NULL");
        assertThat(sql).contains("resolved_at DATETIME(6) NULL");
        assertThat(sql).contains("resolved_article_content_version BIGINT NULL");

        // Ensure no cross-context foreign key to identity
        assertThat(sql).doesNotContain("FOREIGN KEY (resolved_by_user_id)");
        assertThat(sql).doesNotContain("REFERENCES identity_users");

        // Ensure no credit fields (H8 non-goal)
        assertThat(sql).doesNotContain("credited");
        assertThat(sql).doesNotContain("credit");
    }

    @Test
    @DisplayName("Verify milestone migrations exist in classpath")
    void shouldVerifyMilestoneMigrationsExist() {
        assertThat(new ClassPathResource("db/migration/V66__create_wiki_contribution_sources.sql").exists()).isTrue();
        assertThat(new ClassPathResource("db/migration/V65__create_wiki_contributions.sql").exists()).isTrue();
        assertThat(new ClassPathResource("db/migration/V64__add_wiki_cover_orphan_deleting_state.sql").exists()).isTrue();
        assertThat(new ClassPathResource("db/migration/V11__create_wiki_articles.sql").exists()).isTrue();
    }
}
