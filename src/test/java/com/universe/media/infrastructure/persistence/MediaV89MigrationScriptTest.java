package com.universe.media.infrastructure.persistence;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Media V89 Flyway Migration Script Static Audit Test")
class MediaV89MigrationScriptTest {

    @Test
    @DisplayName("Verify V89 migration script exists and enforces storage-key registry, sessions and provenance invariants")
    void shouldVerifyV89MigrationScriptInvariants() throws Exception {
        ClassPathResource resource = new ClassPathResource("db/migration/V89__create_media_storage_key_allocations_and_upload_sessions.sql");
        assertThat(resource.exists()).isTrue();

        String sql;
        try (InputStream is = resource.getInputStream()) {
            sql = new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }

        // 1. Table: media_storage_key_allocations
        assertThat(sql).contains("CREATE TABLE media_storage_key_allocations");
        assertThat(sql).contains("id CHAR(36) NOT NULL");
        assertThat(sql).contains("storage_provider_id VARCHAR(50) NOT NULL");
        assertThat(sql).contains("storage_bucket_name VARCHAR(100) NOT NULL");
        assertThat(sql).contains("storage_key VARCHAR(500)");
        assertThat(sql).contains("COLLATE utf8mb4_0900_bin");
        assertThat(sql).contains("allocation_status VARCHAR(20) NOT NULL");
        assertThat(sql).contains("allocated_by_type VARCHAR(50) NOT NULL");
        assertThat(sql).contains("allocated_by_id CHAR(36) NOT NULL");
        assertThat(sql).contains("uq_media_storage_key_allocations_provider_key");
        assertThat(sql).contains("UNIQUE (storage_provider_id, storage_key)");
        assertThat(sql).contains("uq_media_storage_key_allocations_identity");
        assertThat(sql).contains("UNIQUE (id, storage_provider_id, storage_key)");
        assertThat(sql).contains("uq_media_storage_key_allocations_full_location");
        assertThat(sql).contains("UNIQUE (id, storage_provider_id, storage_bucket_name, storage_key)");
        assertThat(sql).contains("chk_media_storage_key_allocations_bucket_not_blank");
        assertThat(sql).contains("chk_media_storage_key_allocations_b2_bucket");
        assertThat(sql).contains("CHECK (storage_provider_id != 'b2' OR storage_bucket_name != 'NONE')");
        assertThat(sql).contains("CHECK (allocation_status IN ('RESERVED', 'COMMITTED', 'TOMBSTONE'))");
        assertThat(sql).contains("CHECK (allocated_by_type IN ('UPLOAD_SESSION', 'ASSET_VERSION', 'IMAGE_VARIANT'))");
        assertThat(sql).contains("persistence_version BIGINT NOT NULL DEFAULT 0");
        assertThat(sql).contains("chk_media_storage_key_allocations_persistence_version");

        // 2. Table: media_upload_sessions
        assertThat(sql).contains("CREATE TABLE media_upload_sessions");
        assertThat(sql).contains("initiator_principal_id VARCHAR(100) NOT NULL");
        assertThat(sql).contains("consumer_context VARCHAR(50) NOT NULL");
        assertThat(sql).contains("target_entity_type VARCHAR(50) NOT NULL");
        assertThat(sql).contains("target_entity_id CHAR(36) NOT NULL");
        assertThat(sql).contains("idempotency_key VARCHAR(100) NOT NULL");
        assertThat(sql).contains("storage_provider_id VARCHAR(50) NOT NULL");
        assertThat(sql).contains("storage_bucket_name VARCHAR(100) NOT NULL");
        assertThat(sql).contains("part_size_bytes BIGINT NOT NULL DEFAULT 26214400");
        assertThat(sql).contains("expected_part_count INT NOT NULL");
        assertThat(sql).contains("provider_upload_id VARCHAR(255) NULL");
        assertThat(sql).contains("declared_content_hash CHAR(64) NULL");
        assertThat(sql).contains("lease_owner VARCHAR(100) NULL");
        assertThat(sql).contains("lease_expires_at DATETIME(6) NULL");
        assertThat(sql).contains("cleanup_status VARCHAR(20) NOT NULL DEFAULT 'NONE'");
        assertThat(sql).contains("cleanup_retry_count INT NOT NULL DEFAULT 0");

        // Constraints and Invariants
        assertThat(sql).contains("uq_media_upload_sessions_idempotency");
        assertThat(sql).contains("UNIQUE (initiator_principal_id, idempotency_key)");
        assertThat(sql).contains("uq_media_upload_sessions_allocation");
        assertThat(sql).contains("uq_media_upload_sessions_provider_key");
        assertThat(sql).contains("fk_media_upload_sessions_allocation_location");
        assertThat(sql).contains("FOREIGN KEY (storage_allocation_id, storage_provider_id, storage_bucket_name, storage_key)");
        assertThat(sql).contains("REFERENCES media_storage_key_allocations (id, storage_provider_id, storage_bucket_name, storage_key)");
        assertThat(sql).doesNotContain("FOREIGN KEY (storage_allocation_id, storage_provider_id, storage_key)");
        assertThat(sql).doesNotContain("fk_media_upload_sessions_allocation_bucket");
        assertThat(sql).contains("chk_media_upload_sessions_bucket_not_blank");
        assertThat(sql).contains("chk_media_upload_sessions_b2_bucket");
        assertThat(sql).contains("chk_media_upload_sessions_media_type");
        assertThat(sql).contains("CHECK (media_type = 'VIDEO')");
        assertThat(sql).contains("chk_media_upload_sessions_mime_type");
        assertThat(sql).contains("CHECK (declared_mime_type = 'video/mp4')");
        assertThat(sql).contains("chk_media_upload_sessions_size_bytes");
        assertThat(sql).contains("CHECK (declared_size_bytes > 0 AND declared_size_bytes <= 53687091200)");
        assertThat(sql).contains("chk_media_upload_sessions_part_size");
        assertThat(sql).contains("CHECK (part_size_bytes = 26214400)");
        assertThat(sql).contains("chk_media_upload_sessions_expected_parts");
        assertThat(sql).contains("expected_part_count = CEIL(declared_size_bytes / part_size_bytes)");
        assertThat(sql).contains("chk_media_upload_sessions_provider_upload_id");
        assertThat(sql).contains("chk_media_upload_sessions_completion");

        // Bounded-context purity: no references or foreign keys to Screen tables
        assertThat(sql).doesNotContain("screen_entries");
        assertThat(sql).doesNotContain("screen_titles");

        // 3. Provenance alteration on media_asset_versions
        assertThat(sql).contains("ALTER TABLE media_asset_versions");
        assertThat(sql).contains("ADD COLUMN content_hash_status VARCHAR(30) NULL");
        assertThat(sql).contains("chk_media_asset_versions_content_hash_status");
        assertThat(sql).contains("'SERVER_COMPUTED', 'CLIENT_DECLARED_UNVERIFIED'");

        // 4. Safe migration: NO automated inline data backfill in V89
        assertThat(sql).doesNotContain("INSERT INTO media_storage_key_allocations");
    }
}
