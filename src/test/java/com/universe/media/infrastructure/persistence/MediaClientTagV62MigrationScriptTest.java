package com.universe.media.infrastructure.persistence;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class MediaClientTagV62MigrationScriptTest {

    @Test
    @DisplayName("Verify V62 migration script adds client_tag column and discovery index")
    void shouldVerifyV62MigrationScript() throws Exception {
        ClassPathResource resource = new ClassPathResource("db/migration/V62__add_media_assets_client_tag.sql");
        assertThat(resource.exists()).isTrue();

        String sql;
        try (InputStream is = resource.getInputStream()) {
            sql = new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }

        assertThat(sql).contains("ALTER TABLE media_assets");
        assertThat(sql).contains("ADD COLUMN client_tag VARCHAR(64) NULL");
        assertThat(sql).contains("idx_media_assets_client_tag_status_created_at");
        assertThat(sql).contains("ON media_assets (client_tag, status, created_at, id)");

        // Bounded-context purity: neutral media table has no cross-module foreign keys
        assertThat(sql).doesNotContain("FOREIGN KEY");
        assertThat(sql).doesNotContain("REFERENCES");
    }
}
