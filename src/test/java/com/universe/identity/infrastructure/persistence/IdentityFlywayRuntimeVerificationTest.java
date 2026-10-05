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
    @DisplayName("V34 & V76 Flyway migration: Verify schema modification on identity_users (avatar_media_asset_id, public_handle and indexes)")
    void shouldMigrateThroughV34AndVerifyIdentityUsersSchema() {
        String dbName = "kiemlai_identity_avatar_schema_test";
        resetDatabase(dbName);
        DataSource ds = createDataSource(dbName);

        Flyway flyway = Flyway.configure()
                .dataSource(ds)
                .locations("classpath:db/migration")
                .load();

        int migrationsApplied = flyway.migrate().migrationsExecuted;
        assertThat(migrationsApplied).isGreaterThanOrEqualTo(76);

        MigrationInfo[] info = flyway.info().all();
        assertThat(info).hasSizeGreaterThanOrEqualTo(76);

        MigrationInfo v76Info = null;
        for (MigrationInfo mi : info) {
            assertThat(mi.getState()).isEqualTo(MigrationState.SUCCESS);
            if ("76".equals(mi.getVersion().getVersion())) {
                v76Info = mi;
            }
        }

        assertThat(v76Info).isNotNull();
        assertThat(v76Info.getDescription()).isEqualTo("add identity users public handle");
        assertThat(v76Info.getChecksum()).isNotNull();

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
                "public_handle",
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

        // 3. Verify public_handle is NOT NULL VARCHAR(40)
        Map<String, Object> handleColInfo = jdbc.queryForMap(
                "SELECT is_nullable, data_type, character_maximum_length " +
                        "FROM information_schema.columns " +
                        "WHERE table_schema = ? AND table_name = 'identity_users' AND column_name = 'public_handle'",
                dbName
        );
        assertThat(handleColInfo.get("is_nullable")).isEqualTo("NO");
        assertThat(handleColInfo.get("data_type")).isEqualTo("varchar");
        assertThat(((Number) handleColInfo.get("character_maximum_length")).longValue()).isEqualTo(40L);

        // 4. Verify unique constraint / index exists on public_handle and avatar_media_asset_id index
        List<String> handleIndexes = jdbc.queryForList(
                "SELECT index_name FROM information_schema.statistics WHERE table_schema = ? AND table_name = 'identity_users' AND column_name = 'public_handle'",
                String.class,
                dbName
        );
        assertThat(handleIndexes).contains("uq_identity_users_public_handle");

        List<String> avatarIndexes = jdbc.queryForList(
                "SELECT index_name FROM information_schema.statistics WHERE table_schema = ? AND table_name = 'identity_users' AND column_name = 'avatar_media_asset_id'",
                String.class,
                dbName
        );
        assertThat(avatarIndexes).contains("idx_identity_users_avatar_media_asset_id");

        // 5. Verify CRUD with media-backed avatar and legacy avatar rows
        UUID userId1 = UUID.randomUUID();
        UUID mediaAssetId = UUID.randomUUID();
        Instant now = Instant.now();

        // Insert media-backed user
        jdbc.update(
                "INSERT INTO identity_users (id, email, password_hash, display_name, public_handle, avatar_url, avatar_media_asset_id, avatar_customized, status, auth_provider, role, aggregate_version, persistence_version, created_at, updated_at) " +
                        "VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'ACTIVE', 'LOCAL', 'USER', 1, 0, ?, ?)",
                userId1.toString(),
                "media_user@example.com",
                "$2a$10$hash",
                "Media User",
                "media_user_handle",
                "/media/assets/" + mediaAssetId + "/content",
                mediaAssetId.toString(),
                true,
                Timestamp.from(now),
                Timestamp.from(now)
        );

        // Insert legacy user (avatar_media_asset_id is NULL)
        UUID userId2 = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO identity_users (id, email, password_hash, display_name, public_handle, avatar_url, avatar_media_asset_id, avatar_customized, status, auth_provider, role, aggregate_version, persistence_version, created_at, updated_at) " +
                        "VALUES (?, ?, ?, ?, ?, ?, NULL, ?, 'ACTIVE', 'GOOGLE', 'USER', 1, 0, ?, ?)",
                userId2.toString(),
                "google_user@example.com",
                null,
                "Google User",
                "google_user_handle",
                "https://lh3.googleusercontent.com/avatar.png",
                false,
                Timestamp.from(now),
                Timestamp.from(now)
        );

        Map<String, Object> mediaRow = jdbc.queryForMap(
                "SELECT id, public_handle, avatar_url, avatar_media_asset_id, avatar_customized FROM identity_users WHERE id = ?",
                userId1.toString()
        );
        assertThat(mediaRow.get("public_handle")).isEqualTo("media_user_handle");
        assertThat(mediaRow.get("avatar_media_asset_id")).isEqualTo(mediaAssetId.toString());
        assertThat(mediaRow.get("avatar_url")).isEqualTo("/media/assets/" + mediaAssetId + "/content");
        assertThat(mediaRow.get("avatar_customized")).isEqualTo(true);

        Map<String, Object> legacyRow = jdbc.queryForMap(
                "SELECT id, public_handle, avatar_url, avatar_media_asset_id, avatar_customized FROM identity_users WHERE id = ?",
                userId2.toString()
        );
        assertThat(legacyRow.get("public_handle")).isEqualTo("google_user_handle");
        assertThat(legacyRow.get("avatar_media_asset_id")).isNull();
        assertThat(legacyRow.get("avatar_url")).isEqualTo("https://lh3.googleusercontent.com/avatar.png");
        assertThat(legacyRow.get("avatar_customized")).isEqualTo(false);
    }

    @Test
    @DisplayName("V75 -> V76 Flyway data migration: Backfill deterministic public_handle for pre-existing users without email leakage")
    void shouldBackfillPublicHandlesWhenMigratingFromV75ToV76() {
        String dbName = "kiemlai_identity_v76_migration_test";
        resetDatabase(dbName);
        DataSource ds = createDataSource(dbName);

        // 1. Migrate up to V75 only
        Flyway flywayV75 = Flyway.configure()
                .dataSource(ds)
                .locations("classpath:db/migration")
                .target("75")
                .load();

        flywayV75.migrate();

        JdbcTemplate jdbc = new JdbcTemplate(ds);

        // 2. Insert pre-V76 accounts without public_handle (the column doesn't exist in V75)
        UUID user1Id = UUID.randomUUID();
        UUID user2Id = UUID.randomUUID();
        UUID user3Id = UUID.randomUUID();
        Instant now = Instant.now();

        jdbc.update(
                "INSERT INTO identity_users (id, email, password_hash, display_name, avatar_url, avatar_media_asset_id, avatar_customized, status, auth_provider, role, aggregate_version, persistence_version, created_at, updated_at) " +
                        "VALUES (?, ?, '$2a$10$hash', ?, NULL, NULL, false, 'ACTIVE', 'LOCAL', 'USER', 1, 0, ?, ?)",
                user1Id.toString(),
                "secret_local_user@example.com",
                "Tiêu Dao Tử",
                Timestamp.from(now),
                Timestamp.from(now)
        );

        jdbc.update(
                "INSERT INTO identity_users (id, email, password_hash, display_name, avatar_url, avatar_media_asset_id, avatar_customized, status, auth_provider, role, aggregate_version, persistence_version, created_at, updated_at) " +
                        "VALUES (?, ?, NULL, ?, 'https://lh3.google.com/a.png', NULL, false, 'ACTIVE', 'GOOGLE', 'USER', 1, 0, ?, ?)",
                user2Id.toString(),
                "google_author@gmail.com",
                "Hàn Lập",
                Timestamp.from(now),
                Timestamp.from(now)
        );

        // Duplicate display name
        jdbc.update(
                "INSERT INTO identity_users (id, email, password_hash, display_name, avatar_url, avatar_media_asset_id, avatar_customized, status, auth_provider, role, aggregate_version, persistence_version, created_at, updated_at) " +
                        "VALUES (?, ?, '$2a$10$hash', ?, NULL, NULL, false, 'ACTIVE', 'LOCAL', 'USER', 1, 0, ?, ?)",
                user3Id.toString(),
                "another_fan@example.com",
                "Hàn Lập",
                Timestamp.from(now),
                Timestamp.from(now)
        );

        // 3. Migrate to V76 (apply V76 migration)
        Flyway flywayV76 = Flyway.configure()
                .dataSource(ds)
                .locations("classpath:db/migration")
                .load();

        flywayV76.migrate();

        // 4. Verify all pre-existing rows have valid, non-null, unique public_handle matching CONCAT('u_', hex)
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT id, email, display_name, public_handle FROM identity_users WHERE id IN (?, ?, ?)",
                user1Id.toString(),
                user2Id.toString(),
                user3Id.toString()
        );

        assertThat(rows).hasSize(3);

        String expectedHandle1 = "u_" + user1Id.toString().replace("-", "").toLowerCase(java.util.Locale.ROOT);
        String expectedHandle2 = "u_" + user2Id.toString().replace("-", "").toLowerCase(java.util.Locale.ROOT);
        String expectedHandle3 = "u_" + user3Id.toString().replace("-", "").toLowerCase(java.util.Locale.ROOT);

        for (Map<String, Object> row : rows) {
            String id = (String) row.get("id");
            String handle = (String) row.get("public_handle");
            String email = (String) row.get("email");

            assertThat(handle).isNotNull();
            assertThat(handle).matches("^[a-z0-9_]{3,40}$");
            // Must NOT contain any email content
            assertThat(handle).doesNotContain("secret");
            assertThat(handle).doesNotContain("google");
            assertThat(handle).doesNotContain("example");
            assertThat(handle).doesNotContain("@");

            if (user1Id.toString().equals(id)) {
                assertThat(handle).isEqualTo(expectedHandle1);
            } else if (user2Id.toString().equals(id)) {
                assertThat(handle).isEqualTo(expectedHandle2);
            } else if (user3Id.toString().equals(id)) {
                assertThat(handle).isEqualTo(expectedHandle3);
            }
        }
    }
}
