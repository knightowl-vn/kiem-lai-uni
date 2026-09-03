package com.universe.identity.infrastructure.persistence;

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

class IdentityFlywayRuntimeVerificationTest {

    private static DataSource createDataSource(String dbName) {
        return TestDatabaseSupport.createTestDataSource(dbName);
    }

    private static void resetDatabase(String dbName) {
        TestDatabaseSupport.resetTestDatabase(dbName);
    }

    @Test
    @DisplayName("V34 Flyway migration: Verify schema modification on identity_users (avatar_media_asset_id and index)")
    void shouldMigrateThroughV34AndVerifyIdentityUsersSchema() {
        String dbName = "kiemlai_identity_avatar_schema_test";
        resetDatabase(dbName);
        DataSource ds = createDataSource(dbName);

        Flyway flyway = Flyway.configure()
                .dataSource(ds)
                .locations("classpath:db/migration")
                .load();

        int migrationsApplied = flyway.migrate().migrationsExecuted;
        assertThat(migrationsApplied).isGreaterThanOrEqualTo(34);

        MigrationInfo[] info = flyway.info().all();
        assertThat(info).hasSizeGreaterThanOrEqualTo(34);

        MigrationInfo v34Info = null;
        for (MigrationInfo mi : info) {
            assertThat(mi.getState()).isEqualTo(MigrationState.SUCCESS);
            if ("34".equals(mi.getVersion().getVersion())) {
                v34Info = mi;
            }
        }

        assertThat(v34Info).isNotNull();
        assertThat(v34Info.getDescription()).isEqualTo("add identity users avatar media asset id");
        assertThat(v34Info.getChecksum()).isNotNull();

        JdbcTemplate jdbc = new JdbcTemplate(ds);

        // 1. Verify column definitions on identity_users
        List<String> columns = jdbc.queryForList(
                "SELECT column_name FROM information_schema.columns WHERE table_schema = ? AND table_name = 'identity_users'",
                String.class,
                dbName
        );
        assertThat(columns).contains(
                "id",
                "email",
                "password_hash",
                "display_name",
                "avatar_url",
                "avatar_media_asset_id",
                "avatar_customized",
                "bio",
                "status",
                "auth_provider",
                "provider_subject",
                "role",
                "aggregate_version",
                "persistence_version",
                "created_at",
                "updated_at"
        );

        // 2. Verify avatar_media_asset_id is nullable CHAR(36)
        Map<String, Object> colInfo = jdbc.queryForMap(
                "SELECT is_nullable, data_type, character_maximum_length " +
                        "FROM information_schema.columns " +
                        "WHERE table_schema = ? AND table_name = 'identity_users' AND column_name = 'avatar_media_asset_id'",
                dbName
        );
        assertThat(colInfo.get("is_nullable")).isEqualTo("YES");
        assertThat(colInfo.get("data_type")).isEqualTo("char");
        assertThat(((Number) colInfo.get("character_maximum_length")).longValue()).isEqualTo(36L);

        // 3. Verify index exists on avatar_media_asset_id
        List<String> indexes = jdbc.queryForList(
                "SELECT index_name FROM information_schema.statistics WHERE table_schema = ? AND table_name = 'identity_users' AND column_name = 'avatar_media_asset_id'",
                String.class,
                dbName
        );
        assertThat(indexes).contains("idx_identity_users_avatar_media_asset_id");

        // 4. Verify CRUD with media-backed avatar and legacy avatar rows
        UUID userId1 = UUID.randomUUID();
        UUID mediaAssetId = UUID.randomUUID();
        Instant now = Instant.now();

        // Insert media-backed user
        jdbc.update(
                "INSERT INTO identity_users (id, email, password_hash, display_name, avatar_url, avatar_media_asset_id, avatar_customized, status, auth_provider, role, aggregate_version, persistence_version, created_at, updated_at) " +
                        "VALUES (?, ?, ?, ?, ?, ?, ?, 'ACTIVE', 'LOCAL', 'USER', 1, 0, ?, ?)",
                userId1.toString(),
                "media_user@example.com",
                "$2a$10$hash",
                "Media User",
                "/media/assets/" + mediaAssetId + "/content",
                mediaAssetId.toString(),
                true,
                Timestamp.from(now),
                Timestamp.from(now)
        );

        // Insert legacy user (avatar_media_asset_id is NULL)
        UUID userId2 = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO identity_users (id, email, password_hash, display_name, avatar_url, avatar_media_asset_id, avatar_customized, status, auth_provider, role, aggregate_version, persistence_version, created_at, updated_at) " +
                        "VALUES (?, ?, ?, ?, ?, NULL, ?, 'ACTIVE', 'GOOGLE', 'USER', 1, 0, ?, ?)",
                userId2.toString(),
                "google_user@example.com",
                null,
                "Google User",
                "https://lh3.googleusercontent.com/avatar.png",
                false,
                Timestamp.from(now),
                Timestamp.from(now)
        );

        Map<String, Object> mediaRow = jdbc.queryForMap(
                "SELECT id, avatar_url, avatar_media_asset_id, avatar_customized FROM identity_users WHERE id = ?",
                userId1.toString()
        );
        assertThat(mediaRow.get("avatar_media_asset_id")).isEqualTo(mediaAssetId.toString());
        assertThat(mediaRow.get("avatar_url")).isEqualTo("/media/assets/" + mediaAssetId + "/content");
        assertThat(mediaRow.get("avatar_customized")).isEqualTo(true);

        Map<String, Object> legacyRow = jdbc.queryForMap(
                "SELECT id, avatar_url, avatar_media_asset_id, avatar_customized FROM identity_users WHERE id = ?",
                userId2.toString()
        );
        assertThat(legacyRow.get("avatar_media_asset_id")).isNull();
        assertThat(legacyRow.get("avatar_url")).isEqualTo("https://lh3.googleusercontent.com/avatar.png");
        assertThat(legacyRow.get("avatar_customized")).isEqualTo(false);
    }
}
