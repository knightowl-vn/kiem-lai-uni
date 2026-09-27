package com.universe.wiki.infrastructure.persistence.article;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class WikiCoverV60MigrationScriptTest {

    @Test
    @DisplayName("Kiểm tra nội dung migration V60 thêm cột cover_media_asset_id và index")
    void shouldVerifyV60MigrationScript() throws Exception {
        ClassPathResource resource = new ClassPathResource("db/migration/V60__add_wiki_articles_cover_media_asset_id.sql");
        assertThat(resource.exists()).isTrue();

        String sql;
        try (InputStream is = resource.getInputStream()) {
            sql = new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }

        assertThat(sql).contains("ALTER TABLE wiki_articles");
        assertThat(sql).contains("ADD COLUMN cover_media_asset_id CHAR(36) NULL");
        assertThat(sql).contains("AFTER content");
        assertThat(sql).contains("CREATE INDEX idx_wiki_articles_cover_media_asset_id");
        assertThat(sql).contains("ON wiki_articles(cover_media_asset_id)");

        // Ensure bounded-context boundary: no cross-module foreign keys
        assertThat(sql).doesNotContain("FOREIGN KEY");
        assertThat(sql).doesNotContain("REFERENCES media_assets");
    }
}
