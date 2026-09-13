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

class MediaImageVariantFlywayRuntimeVerificationTest {

    private static DataSource createDataSource(String dbName) {
        return TestDatabaseSupport.createTestDataSource(dbName);
    }

    private static void resetDatabase(String dbName) {
        TestDatabaseSupport.resetTestDatabase(dbName);
    }

    @Test
    @DisplayName("V35 Flyway migration: Verify schema creation, columns, metadata, PK, FK, unique and check constraints")
    void shouldMigrateCleanDatabaseThroughV35AndVerifySchema() {
        String dbName = "kiemlai_media_variant_schema_test";
        resetDatabase(dbName);
        DataSource ds = createDataSource(dbName);

        Flyway flyway = Flyway.configure()
                .dataSource(ds)
                .locations("classpath:db/migration")
                .load();

        int migrationsApplied = flyway.migrate().migrationsExecuted;
        assertThat(migrationsApplied).isGreaterThanOrEqualTo(36);

        MigrationInfo[] info = flyway.info().all();
        assertThat(info).hasSizeGreaterThanOrEqualTo(36);

        MigrationInfo v35Info = null;
        MigrationInfo v36Info = null;
        for (MigrationInfo mi : info) {
            assertThat(mi.getState()).isEqualTo(MigrationState.SUCCESS);
            if ("35".equals(mi.getVersion().getVersion())) {
                v35Info = mi;
            } else if ("36".equals(mi.getVersion().getVersion())) {
                v36Info = mi;
            }
        }

        assertThat(v35Info).isNotNull();
        assertThat(v35Info.getDescription()).isEqualTo("create media image variants");
        assertThat(v35Info.getChecksum()).isNotNull();

        assertThat(v36Info).isNotNull();
        assertThat(v36Info.getDescription()).isEqualTo("align media image variant key collation");
        assertThat(v36Info.getChecksum()).isNotNull();

        JdbcTemplate jdbc = new JdbcTemplate(ds);

        // 1. Table existence
        Integer tableCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = ? AND table_name = 'media_image_variants'",
                Integer.class,
                dbName
        );
        assertThat(tableCount).isEqualTo(1);

        // 2. Column collations (variant_key and storage_key must be utf8mb4_0900_bin)
        String variantKeyCollation = jdbc.queryForObject(
                "SELECT collation_name FROM information_schema.columns WHERE table_schema = ? AND table_name = 'media_image_variants' AND column_name = 'variant_key'",
                String.class,
                dbName
        );
        assertThat(variantKeyCollation).isEqualTo("utf8mb4_0900_bin");

        String storageKeyCollation = jdbc.queryForObject(
                "SELECT collation_name FROM information_schema.columns WHERE table_schema = ? AND table_name = 'media_image_variants' AND column_name = 'storage_key'",
                String.class,
                dbName
        );
        assertThat(storageKeyCollation).isEqualTo("utf8mb4_0900_bin");

        // 3. Columns
        List<String> columns = jdbc.queryForList(
                "SELECT column_name FROM information_schema.columns WHERE table_schema = ? AND table_name = 'media_image_variants'",
                String.class,
                dbName
        );
        assertThat(columns).containsExactlyInAnyOrder(
                "id",
                "version_id",
                "variant_key",
                "target_width",
                "storage_provider_id",
                "storage_key",
                "content_hash",
                "mime_type",
                "size_bytes",
                "width",
                "height",
                "created_at"
        );

        // 4. Foreign key to media_asset_versions
        List<Map<String, Object>> fkList = jdbc.queryForList(
                "SELECT CONSTRAINT_NAME, REFERENCED_TABLE_NAME " +
                        "FROM information_schema.KEY_COLUMN_USAGE " +
                        "WHERE TABLE_SCHEMA = ? AND TABLE_NAME = 'media_image_variants' AND COLUMN_NAME = 'version_id' " +
                        "AND REFERENCED_TABLE_NAME IS NOT NULL",
                dbName
        );
        assertThat(fkList).hasSize(1);
        assertThat(fkList.get(0).get("REFERENCED_TABLE_NAME")).isEqualTo("media_asset_versions");

        // 5. Seed parent data to test constraints
        String assetId = UUID.randomUUID().toString();
        String versionId = UUID.randomUUID().toString();
        Timestamp now = Timestamp.from(Instant.now());

        jdbc.update(
                "INSERT INTO media_assets (id, media_type, visibility, status, current_version_number, created_at, updated_at) " +
                        "VALUES (?, 'IMAGE', 'PUBLIC', 'ACTIVE', 1, ?, ?)",
                assetId, now, now
        );

        jdbc.update(
                "INSERT INTO media_asset_versions (id, asset_id, version_number, storage_provider_id, storage_key, content_hash, mime_type, size_bytes, original_filename, created_at) " +
                        "VALUES (?, ?, 1, 'local', 'objects/source.png', 'e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855', 'image/png', 1000, 'source.png', ?)",
                versionId, assetId, now
        );

        // 6. Valid insert into media_image_variants
        String variantId1 = UUID.randomUUID().toString();
        jdbc.update(
                "INSERT INTO media_image_variants (id, version_id, variant_key, target_width, storage_provider_id, storage_key, content_hash, mime_type, size_bytes, width, height, created_at) " +
                        "VALUES (?, ?, 'w300', 300, 'local', 'objects/variants/w300.png', 'e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855', 'image/png', 500, 300, 450, ?)",
                variantId1, versionId, now
        );

        // 7. Test exact collation in SQL query
        Integer exactCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM media_image_variants WHERE version_id = ? AND variant_key = 'w300'",
                Integer.class,
                versionId
        );
        assertThat(exactCount).isEqualTo(1);

        Integer uppercaseCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM media_image_variants WHERE version_id = ? AND variant_key = 'W300'",
                Integer.class,
                versionId
        );
        assertThat(uppercaseCount).isEqualTo(0);

        Integer leadingSpaceCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM media_image_variants WHERE version_id = ? AND variant_key = ' w300'",
                Integer.class,
                versionId
        );
        assertThat(leadingSpaceCount).isEqualTo(0);

        Integer trailingSpaceCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM media_image_variants WHERE version_id = ? AND variant_key = 'w300 '",
                Integer.class,
                versionId
        );
        assertThat(trailingSpaceCount).isEqualTo(0);

        // 8. Test UNIQUE(version_id, variant_key)
        String variantId2 = UUID.randomUUID().toString();
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO media_image_variants (id, version_id, variant_key, target_width, storage_provider_id, storage_key, content_hash, mime_type, size_bytes, width, height, created_at) " +
                        "VALUES (?, ?, 'w300', 300, 'local', 'objects/variants/w300-diff.png', 'e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855', 'image/png', 500, 300, 450, ?)",
                variantId2, versionId, now
        )).hasMessageContaining("uq_media_image_variants_version_key");

        // 9. Test UNIQUE(storage_provider_id, storage_key)
        String variantId3 = UUID.randomUUID().toString();
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO media_image_variants (id, version_id, variant_key, target_width, storage_provider_id, storage_key, content_hash, mime_type, size_bytes, width, height, created_at) " +
                        "VALUES (?, ?, 'w800', 800, 'local', 'objects/variants/w300.png', 'e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855', 'image/png', 500, 800, 1200, ?)",
                variantId3, versionId, now
        )).hasMessageContaining("uq_media_image_variants_provider_key");

        // 10. Test CHECK constraints: target_width bounds [16, 7680]
        String variantIdBelowMin = UUID.randomUUID().toString();
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO media_image_variants (id, version_id, variant_key, target_width, storage_provider_id, storage_key, content_hash, mime_type, size_bytes, width, height, created_at) " +
                        "VALUES (?, ?, 'w15', 15, 'local', 'objects/variants/w15.png', 'e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855', 'image/png', 500, 15, 20, ?)",
                variantIdBelowMin, versionId, now
        )).hasMessageContaining("chk_media_image_variants_target_width");

        String variantIdAboveMax = UUID.randomUUID().toString();
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO media_image_variants (id, version_id, variant_key, target_width, storage_provider_id, storage_key, content_hash, mime_type, size_bytes, width, height, created_at) " +
                        "VALUES (?, ?, 'w7681', 7681, 'local', 'objects/variants/w7681.png', 'e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855', 'image/png', 500, 7681, 1000, ?)",
                variantIdAboveMax, versionId, now
        )).hasMessageContaining("chk_media_image_variants_target_width");

        // 11. Test CHECK constraints: width > 0 AND width <= target_width AND height > 0
        String variantIdWidthExceeds = UUID.randomUUID().toString();
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO media_image_variants (id, version_id, variant_key, target_width, storage_provider_id, storage_key, content_hash, mime_type, size_bytes, width, height, created_at) " +
                        "VALUES (?, ?, 'w300', 300, 'local', 'objects/variants/w300-wide.png', 'e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855', 'image/png', 500, 301, 450, ?)",
                variantIdWidthExceeds, versionId, now
        )).hasMessageContaining("chk_media_image_variants_dimensions");

        // 12. Valid insert with actual width smaller than target_width (proportional resize)
        String variantIdSmallerWidth = UUID.randomUUID().toString();
        int inserted = jdbc.update(
                "INSERT INTO media_image_variants (id, version_id, variant_key, target_width, storage_provider_id, storage_key, content_hash, mime_type, size_bytes, width, height, created_at) " +
                        "VALUES (?, ?, 'w800', 800, 'local', 'objects/variants/w800-small.png', 'e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855', 'image/png', 500, 640, 960, ?)",
                variantIdSmallerWidth, versionId, now
        );
        assertThat(inserted).isEqualTo(1);
    }
}
