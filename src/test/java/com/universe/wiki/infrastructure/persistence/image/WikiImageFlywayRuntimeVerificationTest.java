package com.universe.wiki.infrastructure.persistence.image;

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

class WikiImageFlywayRuntimeVerificationTest {

    private static DataSource createDataSource(String dbName) {
        return TestDatabaseSupport.createTestDataSource(dbName);
    }

    private static void resetDatabase(String dbName) {
        TestDatabaseSupport.resetTestDatabase(dbName);
    }

    @Test
    @DisplayName("V33 Flyway migration: Verify schema modification on wiki_images (media_asset_id, nullable public_id, and index)")
    void shouldMigrateThroughV33AndVerifyWikiImagesSchema() {
        String dbName = "kiemlai_wiki_image_schema_test";
        resetDatabase(dbName);
        DataSource ds = createDataSource(dbName);

        Flyway flyway = Flyway.configure()
                .dataSource(ds)
                .locations("classpath:db/migration")
                .load();

        int migrationsApplied = flyway.migrate().migrationsExecuted;
        assertThat(migrationsApplied).isGreaterThanOrEqualTo(33);

        MigrationInfo[] info = flyway.info().all();
        assertThat(info).hasSizeGreaterThanOrEqualTo(33);

        MigrationInfo v33Info = null;
        for (MigrationInfo mi : info) {
            assertThat(mi.getState()).isEqualTo(MigrationState.SUCCESS);
            if ("33".equals(mi.getVersion().getVersion())) {
                v33Info = mi;
            }
        }

        assertThat(v33Info).isNotNull();
        assertThat(v33Info.getDescription()).isEqualTo("add wiki images media reference");
        assertThat(v33Info.getChecksum()).isNotNull();

        JdbcTemplate jdbc = new JdbcTemplate(ds);

        // 1. Verify column definitions on wiki_images
        List<String> columns = jdbc.queryForList(
                "SELECT column_name FROM information_schema.columns WHERE table_schema = ? AND table_name = 'wiki_images'",
                String.class,
                dbName
        );
        assertThat(columns).contains(
                "id",
                "content_hash",
                "public_id",
                "media_asset_id",
                "url",
                "source_content_type",
                "size_bytes",
                "created_at"
        );

        // 2. Verify media_asset_id is nullable and char(36)
        Map<String, Object> mediaAssetIdMeta = jdbc.queryForMap(
                "SELECT character_maximum_length, is_nullable "
                        + "FROM information_schema.columns "
                        + "WHERE table_schema = ? AND table_name = 'wiki_images' AND column_name = 'media_asset_id'",
                dbName
        );
        assertThat(((Number) mediaAssetIdMeta.get("character_maximum_length")).longValue()).isEqualTo(36L);
        assertThat((String) mediaAssetIdMeta.get("is_nullable")).isEqualTo("YES");

        // 3. Verify public_id is now nullable
        Map<String, Object> publicIdMeta = jdbc.queryForMap(
                "SELECT is_nullable "
                        + "FROM information_schema.columns "
                        + "WHERE table_schema = ? AND table_name = 'wiki_images' AND column_name = 'public_id'",
                dbName
        );
        assertThat((String) publicIdMeta.get("is_nullable")).isEqualTo("YES");

        // 4. Verify index on media_asset_id exists
        List<String> indexes = jdbc.queryForList(
                "SELECT index_name FROM information_schema.statistics WHERE table_schema = ? AND table_name = 'wiki_images' AND column_name = 'media_asset_id'",
                String.class,
                dbName
        );
        assertThat(indexes).contains("idx_wiki_images_media_asset_id");

        // 5. Verify insertion of legacy row (public_id != null, media_asset_id == null)
        UUID legacyId = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO wiki_images (id, content_hash, public_id, media_asset_id, url, source_content_type, size_bytes, created_at) "
                        + "VALUES (?, 'hash_legacy_1', 'legacy_pub_1', NULL, 'https://res.cloudinary.com/legacy.jpg', 'image/jpeg', 1024, ?)",
                legacyId.toString(),
                Timestamp.from(Instant.now())
        );

        // 6. Verify insertion of Media-backed row (public_id == null, media_asset_id != null)
        UUID mediaImageId = UUID.randomUUID();
        UUID mediaAssetId = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO wiki_images (id, content_hash, public_id, media_asset_id, url, source_content_type, size_bytes, created_at) "
                        + "VALUES (?, 'hash_media_2', NULL, ?, ?, 'image/png', 2048, ?)",
                mediaImageId.toString(),
                mediaAssetId.toString(),
                "/media/assets/" + mediaAssetId + "/content",
                Timestamp.from(Instant.now())
        );
    }
}
