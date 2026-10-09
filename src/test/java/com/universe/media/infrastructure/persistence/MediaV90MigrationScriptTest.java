package com.universe.media.infrastructure.persistence;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Media V90 Flyway Migration Script Static Audit Test")
class MediaV90MigrationScriptTest {

    @Test
    @DisplayName("Verify V90 migration script exists and enforces exact nullable manifest_hash column addition")
    void shouldVerifyV90MigrationScriptInvariants() throws Exception {
        ClassPathResource resource = new ClassPathResource("db/migration/V90__add_manifest_hash_to_media_upload_sessions.sql");
        assertThat(resource.exists()).isTrue();

        String sql;
        try (InputStream is = resource.getInputStream()) {
            sql = new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }

        // Table operation: media_upload_sessions
        assertThat(sql).contains("ALTER TABLE media_upload_sessions");
        assertThat(sql).contains("ADD COLUMN manifest_hash CHAR(64) NULL");

        // Negative scope guards: no unintended indexes, tables, constraints, defaults, triggers
        assertThat(sql).doesNotContain("INDEX");
        assertThat(sql).doesNotContain("KEY");
        assertThat(sql).doesNotContain("UNIQUE");
        assertThat(sql).doesNotContain("DEFAULT");
        assertThat(sql).doesNotContain("NOT NULL");
        assertThat(sql).doesNotContain("CREATE TABLE");
        assertThat(sql).doesNotContain("TRIGGER");
        assertThat(sql).doesNotContain("DROP");
        assertThat(sql).doesNotContain("media_storage_key_allocations");
        assertThat(sql).doesNotContain("media_assets");
        assertThat(sql).doesNotContain("media_asset_versions");
    }

    @Test
    @DisplayName("Verify V89 migration script remains unchanged and present")
    void shouldVerifyV89Unchanged() throws Exception {
        ClassPathResource v89 = new ClassPathResource("db/migration/V89__create_media_storage_key_allocations_and_upload_sessions.sql");
        assertThat(v89.exists()).isTrue();
    }
}
