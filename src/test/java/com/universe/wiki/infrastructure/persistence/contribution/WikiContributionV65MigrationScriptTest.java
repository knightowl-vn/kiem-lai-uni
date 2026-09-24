package com.universe.wiki.infrastructure.persistence.contribution;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Wiki Contribution V65 Migration Script Tests")
class WikiContributionV65MigrationScriptTest {

    @Test
    @DisplayName("Verify V65 migration script creates wiki_contributions table with correct schema, constraints and indexes")
    void shouldVerifyV65MigrationScript() throws Exception {
        ClassPathResource resource = new ClassPathResource("db/migration/V65__create_wiki_contributions.sql");
        assertThat(resource.exists()).isTrue();

        String sql;
        try (InputStream is = resource.getInputStream()) {
            sql = new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }

        // Table creation
        assertThat(sql).contains("CREATE TABLE wiki_contributions");

        // Primary key
        assertThat(sql).contains("id CHAR(36) NOT NULL");
        assertThat(sql).contains("PRIMARY KEY (id)");

        // Snapshot & reference columns
        assertThat(sql).contains("article_id CHAR(36) NOT NULL");
        assertThat(sql).contains("article_type_snapshot VARCHAR(30) NOT NULL");
        assertThat(sql).contains("article_title_snapshot VARCHAR(200) NOT NULL");
        assertThat(sql).contains("article_slug_snapshot VARCHAR(180) NOT NULL");
        assertThat(sql).contains("article_content_version BIGINT NOT NULL");
        assertThat(sql).contains("submitted_by_user_id CHAR(36) NOT NULL");

        // Content & anchor columns
        assertThat(sql).contains("context_type VARCHAR(20) NOT NULL");
        assertThat(sql).contains("contribution_type VARCHAR(32) NOT NULL");
        assertThat(sql).contains("message TEXT NOT NULL");
        assertThat(sql).contains("selected_text VARCHAR(1000) NULL");
        assertThat(sql).contains("selected_prefix VARCHAR(100) NULL");
        assertThat(sql).contains("selected_suffix VARCHAR(100) NULL");
        assertThat(sql).contains("selected_heading_anchor VARCHAR(255) NULL");

        // Status, optimistic lock version & timestamps
        assertThat(sql).contains("status VARCHAR(20) NOT NULL DEFAULT 'NEW'");
        assertThat(sql).contains("version BIGINT NOT NULL DEFAULT 0");
        assertThat(sql).contains("created_at DATETIME(6) NOT NULL");
        assertThat(sql).contains("updated_at DATETIME(6) NOT NULL");

        // Check constraints
        assertThat(sql).contains("CONSTRAINT chk_wiki_contributions_context_type");
        assertThat(sql).contains("CHECK (context_type IN ('GENERAL', 'TEXT_SELECTION'))");

        assertThat(sql).contains("CONSTRAINT chk_wiki_contributions_type");
        assertThat(sql).contains("CHECK (contribution_type IN ('INCORRECT_INFORMATION', 'MISSING_INFORMATION', 'OUTDATED_INFORMATION', 'WORDING', 'SOURCE_REFERENCE', 'OTHER'))");

        assertThat(sql).contains("CONSTRAINT chk_wiki_contributions_status");
        assertThat(sql).contains("CHECK (status IN ('NEW', 'REVIEWING', 'RESOLVED', 'REJECTED'))");

        // Indexes
        assertThat(sql).contains("CREATE INDEX idx_wiki_contributions_status_created_id");
        assertThat(sql).contains("ON wiki_contributions (status, created_at, id)");

        assertThat(sql).contains("CREATE INDEX idx_wiki_contributions_article_created_id");
        assertThat(sql).contains("ON wiki_contributions (article_id, created_at, id)");

        assertThat(sql).contains("CREATE INDEX idx_wiki_contributions_user_created_id");
        assertThat(sql).contains("ON wiki_contributions (submitted_by_user_id, created_at, id)");

        // Bounded-context independence: No foreign key to wiki_articles or identity_users
        assertThat(sql).doesNotContain("FOREIGN KEY");
        assertThat(sql).doesNotContain("REFERENCES");
    }

    @Test
    @DisplayName("Verify milestone migrations exist in classpath")
    void shouldVerifyMilestoneMigrationsExist() {
        assertThat(new ClassPathResource("db/migration/V64__add_wiki_cover_orphan_deleting_state.sql").exists()).isTrue();
        assertThat(new ClassPathResource("db/migration/V63__create_wiki_cover_orphans.sql").exists()).isTrue();
        assertThat(new ClassPathResource("db/migration/V62__add_media_assets_client_tag.sql").exists()).isTrue();
        assertThat(new ClassPathResource("db/migration/V61__add_wiki_article_cover_focal_position.sql").exists()).isTrue();
        assertThat(new ClassPathResource("db/migration/V60__add_wiki_articles_cover_media_asset_id.sql").exists()).isTrue();
        assertThat(new ClassPathResource("db/migration/V48__create_wiki_saved_articles.sql").exists()).isTrue();
        assertThat(new ClassPathResource("db/migration/V11__create_wiki_articles.sql").exists()).isTrue();
    }
}
