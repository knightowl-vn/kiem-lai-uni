package com.universe.media.infrastructure.persistence;

import com.universe.test.TestDatabaseSupport;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.MigrationState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MediaFlywayRuntimeVerificationTest {

    private static DataSource createDataSource(String dbName) {
        return TestDatabaseSupport.createTestDataSource(dbName);
    }

    private static void resetDatabase(String dbName) {
        TestDatabaseSupport.resetTestDatabase(dbName);
    }

    @Test
    @DisplayName("V31 Flyway migration: Verify schema creation, columns, metadata, PK, FK, and unique constraints")
    void shouldMigrateCleanDatabaseThroughV31AndVerifySchema() {
        String dbName = "kiemlai_media_schema_test";
        resetDatabase(dbName);
        DataSource ds = createDataSource(dbName);

        Flyway flyway = Flyway.configure()
                .dataSource(ds)
                .locations("classpath:db/migration")
                .load();

        int migrationsApplied = flyway.migrate().migrationsExecuted;
        assertThat(migrationsApplied).isGreaterThanOrEqualTo(31);

        MigrationInfo[] info = flyway.info().all();
        assertThat(info).hasSizeGreaterThanOrEqualTo(31);

        MigrationInfo v31Info = null;
        for (MigrationInfo mi : info) {
            assertThat(mi.getState()).isEqualTo(MigrationState.SUCCESS);
            if ("31".equals(mi.getVersion().getVersion())) {
                v31Info = mi;
            }
        }

        assertThat(v31Info).isNotNull();
        assertThat(v31Info.getDescription()).isEqualTo("create media schema");
        assertThat(v31Info.getChecksum()).isNotNull();

        JdbcTemplate jdbc = new JdbcTemplate(ds);

        // 1. Check table existence
        Integer assetTableCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = ? AND table_name = 'media_assets'",
                Integer.class,
                dbName
        );
        assertThat(assetTableCount).isEqualTo(1);

        Integer versionTableCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = ? AND table_name = 'media_asset_versions'",
                Integer.class,
                dbName
        );
        assertThat(versionTableCount).isEqualTo(1);

        // 2. Verify column definitions
        List<String> assetColumns = jdbc.queryForList(
                "SELECT column_name FROM information_schema.columns WHERE table_schema = ? AND table_name = 'media_assets'",
                String.class,
                dbName
        );
        assertThat(assetColumns).containsExactlyInAnyOrder(
                "id",
                "media_type",
                "visibility",
                "status",
                "current_version_number",
                "created_at",
                "updated_at",
                "persistence_version"
        );

        List<String> versionColumns = jdbc.queryForList(
                "SELECT column_name FROM information_schema.columns WHERE table_schema = ? AND table_name = 'media_asset_versions'",
                String.class,
                dbName
        );
        assertThat(versionColumns).containsExactlyInAnyOrder(
                "id",
                "asset_id",
                "version_number",
                "storage_provider_id",
                "storage_key",
                "public_url",
                "content_hash",
                "mime_type",
                "size_bytes",
                "original_filename",
                "created_at"
        );

        // 3. Verify critical column metadata (information_schema)
        Map<String, Object> storageKeyMeta = jdbc.queryForMap(
                "SELECT character_maximum_length, collation_name " +
                        "FROM information_schema.columns " +
                        "WHERE table_schema = ? AND table_name = 'media_asset_versions' AND column_name = 'storage_key'",
                dbName
        );
        assertThat(((Number) storageKeyMeta.get("character_maximum_length")).longValue()).isEqualTo(500L);
        assertThat((String) storageKeyMeta.get("collation_name")).isEqualTo("utf8mb4_0900_bin");

        Map<String, Object> publicUrlMeta = jdbc.queryForMap(
                "SELECT character_maximum_length, is_nullable " +
                        "FROM information_schema.columns " +
                        "WHERE table_schema = ? AND table_name = 'media_asset_versions' AND column_name = 'public_url'",
                dbName
        );
        assertThat(((Number) publicUrlMeta.get("character_maximum_length")).longValue()).isEqualTo(1000L);
        assertThat((String) publicUrlMeta.get("is_nullable")).isEqualTo("YES");

        // 4. Verify Foreign Keys
        List<String> foreignKeys = jdbc.queryForList(
                "SELECT constraint_name FROM information_schema.table_constraints WHERE table_schema = ? AND table_name = 'media_asset_versions' AND constraint_type = 'FOREIGN KEY'",
                String.class,
                dbName
        );
        assertThat(foreignKeys).contains("fk_media_asset_versions_asset");

        // 5. Verify Unique Constraints
        List<String> uniqueConstraints = jdbc.queryForList(
                "SELECT constraint_name FROM information_schema.table_constraints WHERE table_schema = ? AND table_name = 'media_asset_versions' AND constraint_type = 'UNIQUE'",
                String.class,
                dbName
        );
        assertThat(uniqueConstraints).contains(
                "uq_media_asset_versions_asset_version",
                "uq_media_asset_versions_provider_key"
        );
    }

    @Test
    @DisplayName("V31 Database Constraints: storage uniqueness, case sensitivity, no-pad, content hash non-uniqueness, and FK RESTRICT")
    void shouldEnforceMediaDatabaseConstraintsOnRealMySql() {
        String dbName = "kiemlai_media_constraints_test";
        resetDatabase(dbName);
        DataSource ds = createDataSource(dbName);

        Flyway flyway = Flyway.configure()
                .dataSource(ds)
                .locations("classpath:db/migration")
                .load();
        flyway.migrate();

        JdbcTemplate jdbc = new JdbcTemplate(ds);

        UUID assetId1 = UUID.randomUUID();
        UUID assetId2 = UUID.randomUUID();
        Instant now = Instant.now();

        // 1. Insert two valid MediaAssets
        jdbc.update(
                "INSERT INTO media_assets (id, media_type, visibility, status, current_version_number, created_at, updated_at, persistence_version) " +
                        "VALUES (?, 'IMAGE', 'PUBLIC', 'ACTIVE', 1, ?, ?, 0)",
                assetId1.toString(), Timestamp.from(now), Timestamp.from(now)
        );

        jdbc.update(
                "INSERT INTO media_assets (id, media_type, visibility, status, current_version_number, created_at, updated_at, persistence_version) " +
                        "VALUES (?, 'IMAGE', 'PUBLIC', 'ACTIVE', 1, ?, ?, 0)",
                assetId2.toString(), Timestamp.from(now), Timestamp.from(now)
        );

        String sharedHash = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";

        // 2. Insert valid version 1 for asset 1
        UUID version1Id = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO media_asset_versions (id, asset_id, version_number, storage_provider_id, storage_key, public_url, content_hash, mime_type, size_bytes, original_filename, created_at) " +
                        "VALUES (?, ?, 1, 'cloudinary', 'covers/cover-1.webp', 'https://cdn.universe.com/covers/cover-1.webp', ?, 'image/webp', 1024, 'cover-1.webp', ?)",
                version1Id.toString(), assetId1.toString(), sharedHash, Timestamp.from(now)
        );

        // 3. Exact duplicate (storage_provider_id, storage_key) must fail
        UUID versionDupStorageId = UUID.randomUUID();
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO media_asset_versions (id, asset_id, version_number, storage_provider_id, storage_key, public_url, content_hash, mime_type, size_bytes, original_filename, created_at) " +
                        "VALUES (?, ?, 1, 'cloudinary', 'covers/cover-1.webp', NULL, ?, 'image/webp', 1024, 'cover-dup.webp', ?)",
                versionDupStorageId.toString(), assetId2.toString(), sharedHash, Timestamp.from(now)
        )).hasMessageContaining("uq_media_asset_versions_provider_key");

        // 4. Duplicate content_hash with distinct storage_key MUST SUCCEED (content_hash is not unique)
        UUID version2Id = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO media_asset_versions (id, asset_id, version_number, storage_provider_id, storage_key, public_url, content_hash, mime_type, size_bytes, original_filename, created_at) " +
                        "VALUES (?, ?, 1, 's3', 'avatars/avatar-1.webp', NULL, ?, 'image/webp', 1024, 'avatar-1.webp', ?)",
                version2Id.toString(), assetId2.toString(), sharedHash, Timestamp.from(now)
        );

        // 5a. NO-PAD check in utf8mb4_0900_bin: 'covers/cover-1.webp ' vs 'covers/cover-1.webp' are distinct and succeed
        UUID versionTrailingSpaceId = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO media_asset_versions (id, asset_id, version_number, storage_provider_id, storage_key, public_url, content_hash, mime_type, size_bytes, original_filename, created_at) " +
                        "VALUES (?, ?, 2, 'cloudinary', 'covers/cover-1.webp ', NULL, ?, 'image/webp', 2048, 'cover-space.webp', ?)",
                versionTrailingSpaceId.toString(), assetId1.toString(), sharedHash, Timestamp.from(now)
        );

        // 5b. Case-sensitivity check in utf8mb4_0900_bin: 'covers/cover-1.webp' vs 'covers/COVER-1.webp' are distinct and succeed
        UUID versionCaseDiffId = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO media_asset_versions (id, asset_id, version_number, storage_provider_id, storage_key, public_url, content_hash, mime_type, size_bytes, original_filename, created_at) " +
                        "VALUES (?, ?, 3, 'cloudinary', 'covers/COVER-1.webp', NULL, ?, 'image/webp', 2048, 'cover-upper.webp', ?)",
                versionCaseDiffId.toString(), assetId1.toString(), sharedHash, Timestamp.from(now)
        );

        // 6. Check constraint size_bytes > 0 rejects 0 or negative
        UUID versionInvalidSizeId = UUID.randomUUID();
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO media_asset_versions (id, asset_id, version_number, storage_provider_id, storage_key, public_url, content_hash, mime_type, size_bytes, original_filename, created_at) " +
                        "VALUES (?, ?, 4, 'cloudinary', 'covers/cover-zero.webp', NULL, ?, 'image/webp', 0, 'zero.webp', ?)",
                versionInvalidSizeId.toString(), assetId1.toString(), sharedHash, Timestamp.from(now)
        )).hasMessageContaining("chk_media_asset_versions_size_bytes");

        // 7. Check constraint updated_at >= created_at on media_assets rejects updated_at < created_at
        UUID invalidAssetId = UUID.randomUUID();
        Instant past = now.minusSeconds(3600);
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO media_assets (id, media_type, visibility, status, current_version_number, created_at, updated_at, persistence_version) " +
                        "VALUES (?, 'IMAGE', 'PUBLIC', 'ACTIVE', 1, ?, ?, 0)",
                invalidAssetId.toString(), Timestamp.from(now), Timestamp.from(past)
        )).hasMessageContaining("chk_media_assets_updated_at");

        // 8. ON DELETE RESTRICT prevents deleting media_assets when versions exist
        assertThatThrownBy(() -> jdbc.update(
                "DELETE FROM media_assets WHERE id = ?",
                assetId1.toString()
        )).hasMessageContaining("foreign key constraint");
    }
}
