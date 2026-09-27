package com.universe.wiki.infrastructure.persistence.article;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class WikiCoverV61MigrationScriptTest {

    @Test
    @DisplayName("Kiểm tra nội dung migration V61 thêm cover_position_x, cover_position_y và CHECK constraints")
    void shouldVerifyV61MigrationScript() throws Exception {
        ClassPathResource resource = new ClassPathResource("db/migration/V61__add_wiki_article_cover_focal_position.sql");
        assertThat(resource.exists()).isTrue();

        String sql;
        try (InputStream is = resource.getInputStream()) {
            sql = new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }

        assertThat(sql).contains("ALTER TABLE wiki_articles");
        assertThat(sql).contains("cover_position_x TINYINT UNSIGNED NOT NULL DEFAULT 50");
        assertThat(sql).contains("cover_position_y TINYINT UNSIGNED NOT NULL DEFAULT 50");
        assertThat(sql).contains("chk_wiki_articles_cover_position_x");
        assertThat(sql).contains("chk_wiki_articles_cover_position_y");
        assertThat(sql).contains("CHECK (cover_position_x BETWEEN 0 AND 100)");
        assertThat(sql).contains("CHECK (cover_position_y BETWEEN 0 AND 100)");

        // Ensure bounded-context boundary: no cross-module foreign keys
        assertThat(sql).doesNotContain("FOREIGN KEY");
        assertThat(sql).doesNotContain("REFERENCES");
    }
}
