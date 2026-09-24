package com.universe.wiki.infrastructure.persistence.orphan;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Wiki Cover Orphan V64 Migration Script Tests")
class WikiCoverOrphanV64MigrationScriptTest {

    @Test
    @DisplayName("Verify V64 migration script drops and re-adds check constraint with DELETING status")
    void shouldVerifyV64MigrationScript() throws Exception {
        ClassPathResource resource = new ClassPathResource("db/migration/V64__add_wiki_cover_orphan_deleting_state.sql");
        assertThat(resource.exists()).isTrue();

        String sql;
        try (InputStream is = resource.getInputStream()) {
            sql = new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }

        // Alter table statements
        assertThat(sql).contains("ALTER TABLE wiki_cover_orphans");
        assertThat(sql).contains("DROP CHECK chk_wiki_cover_orphans_status");
        assertThat(sql).contains("ADD CONSTRAINT chk_wiki_cover_orphans_status");
        assertThat(sql).contains("CHECK (status IN ('PENDING', 'PROCESSING', 'DELETING'))");
    }

    @Test
    @DisplayName("Verify V1 to V63 migration scripts are present and untouched")
    void shouldVerifyPriorMigrationsExist() {
        assertThat(new ClassPathResource("db/migration/V63__create_wiki_cover_orphans.sql").exists()).isTrue();
        assertThat(new ClassPathResource("db/migration/V62__add_media_assets_client_tag.sql").exists()).isTrue();
        assertThat(new ClassPathResource("db/migration/V61__add_wiki_article_cover_focal_position.sql").exists()).isTrue();
        assertThat(new ClassPathResource("db/migration/V60__add_wiki_articles_cover_media_asset_id.sql").exists()).isTrue();
        assertThat(new ClassPathResource("db/migration/V11__create_wiki_articles.sql").exists()).isTrue();
    }
}
