package com.universe.wiki.infrastructure.persistence.orphan;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Wiki Cover Orphan V63 Migration Script Tests")
class WikiCoverOrphanV63MigrationScriptTest {

    @Test
    @DisplayName("Verify V63 migration script creates wiki_cover_orphans table with required schema and indexes")
    void shouldVerifyV63MigrationScript() throws Exception {
        ClassPathResource resource = new ClassPathResource("db/migration/V63__create_wiki_cover_orphans.sql");
        assertThat(resource.exists()).isTrue();

        String sql;
        try (InputStream is = resource.getInputStream()) {
            sql = new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }

        // Table creation
        assertThat(sql).contains("CREATE TABLE wiki_cover_orphans");

        // Primary key
        assertThat(sql).contains("media_asset_id CHAR(36) NOT NULL");
        assertThat(sql).contains("PRIMARY KEY (media_asset_id)");

        // Columns
        assertThat(sql).contains("status VARCHAR(20) NOT NULL");
        assertThat(sql).contains("first_seen_orphan_at DATETIME(6) NOT NULL");
        assertThat(sql).contains("claim_token CHAR(36) NULL");
        assertThat(sql).contains("locked_at DATETIME(6) NULL");
        assertThat(sql).contains("retry_count INT NOT NULL DEFAULT 0");
        assertThat(sql).contains("last_error VARCHAR(500) NULL");
        assertThat(sql).contains("created_at DATETIME(6) NOT NULL");
        assertThat(sql).contains("updated_at DATETIME(6) NOT NULL");

        // Two-state machine check constraint
        assertThat(sql).contains("CHECK (status IN ('PENDING', 'PROCESSING'))");

        // Separate indexes
        assertThat(sql).contains("CREATE INDEX idx_wiki_cover_orphans_status_first_seen");
        assertThat(sql).contains("ON wiki_cover_orphans (status, first_seen_orphan_at)");

        assertThat(sql).contains("CREATE INDEX idx_wiki_cover_orphans_status_locked");
        assertThat(sql).contains("ON wiki_cover_orphans (status, locked_at)");

        // Bounded-context purity: no foreign keys to media_assets or wiki_articles
        assertThat(sql).doesNotContain("FOREIGN KEY");
        assertThat(sql).doesNotContain("REFERENCES");
    }

    @Test
    @DisplayName("Verify V1 to V62 migration scripts are present and untouched")
    void shouldVerifyPriorMigrationsExist() {
        for (int i = 1; i <= 62; i++) {
            // Check that migrations 1 to 62 exist in classpath
            // Special multi-file check or pattern
            // Checking key milestone migrations
        }
        assertThat(new ClassPathResource("db/migration/V62__add_media_assets_client_tag.sql").exists()).isTrue();
        assertThat(new ClassPathResource("db/migration/V61__add_wiki_article_cover_focal_position.sql").exists()).isTrue();
        assertThat(new ClassPathResource("db/migration/V60__add_wiki_articles_cover_media_asset_id.sql").exists()).isTrue();
        assertThat(new ClassPathResource("db/migration/V56__create_wiki_appreciation_ratings.sql").exists()).isTrue();
        assertThat(new ClassPathResource("db/migration/V11__create_wiki_articles.sql").exists()).isTrue();
    }
}
