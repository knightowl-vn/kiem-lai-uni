package com.universe.community.infrastructure.persistence;

import com.universe.test.TestDatabaseSupport;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.MigrationState;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CommunityFlywayRuntimeVerificationTest {

    private static final String DB_NAME = "kiemlai_community_posts_schema_test";
    private static DataSource dataSource;
    private static JdbcTemplate jdbc;

    @BeforeAll
    static void setUpDatabase() {
        TestDatabaseSupport.resetTestDatabase(DB_NAME);
        TestDatabaseSupport.resetTestDatabase(TestDatabaseSupport.resolveDatabaseName());
        dataSource = TestDatabaseSupport.createTestDataSource(DB_NAME);
        Flyway flyway = Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .load();
        flyway.migrate();
        jdbc = new JdbcTemplate(dataSource);
    }

    @Test
    @DisplayName("1. Migration V77 execution and description verification")
    void shouldVerifyV77MigrationExecutedSuccessfully() {
        Flyway flyway = Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .load();

        MigrationInfo[] info = flyway.info().all();
        assertThat(info).hasSizeGreaterThanOrEqualTo(77);

        MigrationInfo v77Info = null;
        for (MigrationInfo mi : info) {
            if ("77".equals(mi.getVersion().getVersion())) {
                v77Info = mi;
                break;
            }
        }

        assertThat(v77Info).isNotNull();
        assertThat(v77Info.getState()).isEqualTo(MigrationState.SUCCESS);
        assertThat(v77Info.getDescription()).isEqualTo("create community posts");
    }

    @Test
    @DisplayName("2. Verify community_posts table structure, columns and datatypes")
    void shouldVerifyCommunityPostsTableStructure() {
        List<String> columns = jdbc.queryForList(
                "SELECT column_name FROM information_schema.columns WHERE table_schema = ? AND table_name = 'community_posts'",
                String.class,
                DB_NAME
        );

        assertThat(columns).contains(
                "id",
                "author_user_id",
                "caption",
                "image_media_asset_id",
                "content_version",
                "created_at",
                "updated_at"
        );

        Map<String, Object> idCol = jdbc.queryForMap(
                "SELECT is_nullable, data_type, character_maximum_length FROM information_schema.columns WHERE table_schema = ? AND table_name = 'community_posts' AND column_name = 'id'",
                DB_NAME
        );
        assertThat(idCol.get("is_nullable")).isEqualTo("NO");
        assertThat(idCol.get("data_type")).isEqualTo("char");
        assertThat(((Number) idCol.get("character_maximum_length")).longValue()).isEqualTo(36L);

        Map<String, Object> authorCol = jdbc.queryForMap(
                "SELECT is_nullable, data_type, character_maximum_length FROM information_schema.columns WHERE table_schema = ? AND table_name = 'community_posts' AND column_name = 'author_user_id'",
                DB_NAME
        );
        assertThat(authorCol.get("is_nullable")).isEqualTo("NO");
        assertThat(authorCol.get("data_type")).isEqualTo("char");
        assertThat(((Number) authorCol.get("character_maximum_length")).longValue()).isEqualTo(36L);

        Map<String, Object> imageCol = jdbc.queryForMap(
                "SELECT is_nullable, data_type, character_maximum_length FROM information_schema.columns WHERE table_schema = ? AND table_name = 'community_posts' AND column_name = 'image_media_asset_id'",
                DB_NAME
        );
        assertThat(imageCol.get("is_nullable")).isEqualTo("YES");
        assertThat(imageCol.get("data_type")).isEqualTo("char");
        assertThat(((Number) imageCol.get("character_maximum_length")).longValue()).isEqualTo(36L);

        Map<String, Object> versionCol = jdbc.queryForMap(
                "SELECT is_nullable, data_type, column_default FROM information_schema.columns WHERE table_schema = ? AND table_name = 'community_posts' AND column_name = 'content_version'",
                DB_NAME
        );
        assertThat(versionCol.get("is_nullable")).isEqualTo("NO");
        assertThat(versionCol.get("data_type")).isEqualTo("int");
        assertThat(versionCol.get("column_default")).isEqualTo("0");
    }

    @Test
    @DisplayName("3. Verify community_posts feed and author indexes")
    void shouldVerifyCommunityPostsIndexes() {
        List<String> indexes = jdbc.queryForList(
                "SELECT DISTINCT index_name FROM information_schema.statistics WHERE table_schema = ? AND table_name = 'community_posts'",
                String.class,
                DB_NAME
        );

        assertThat(indexes).contains(
                "PRIMARY",
                "idx_community_posts_feed_created",
                "idx_community_posts_author_feed"
        );
    }

    @Test
    @DisplayName("4. Verify community_post_revisions table structure, FK and unique constraints")
    void shouldVerifyCommunityPostRevisionsTableStructure() {
        List<String> columns = jdbc.queryForList(
                "SELECT column_name FROM information_schema.columns WHERE table_schema = ? AND table_name = 'community_post_revisions'",
                String.class,
                DB_NAME
        );

        assertThat(columns).contains(
                "id",
                "post_id",
                "revision_number",
                "editor_user_id",
                "previous_caption",
                "caption",
                "edited_at"
        );

        List<String> indexes = jdbc.queryForList(
                "SELECT DISTINCT index_name FROM information_schema.statistics WHERE table_schema = ? AND table_name = 'community_post_revisions'",
                String.class,
                DB_NAME
        );

        assertThat(indexes).contains(
                "PRIMARY",
                "uq_community_post_revisions_post_number"
        );
        assertThat(indexes).doesNotContain("idx_community_post_revisions_post_rev");

        // Verify foreign key references community_posts
        List<Map<String, Object>> foreignKeys = jdbc.queryForList(
                """
                SELECT CONSTRAINT_NAME, REFERENCED_TABLE_NAME, REFERENCED_COLUMN_NAME
                FROM information_schema.KEY_COLUMN_USAGE
                WHERE TABLE_SCHEMA = ?
                  AND TABLE_NAME = 'community_post_revisions'
                  AND REFERENCED_TABLE_NAME IS NOT NULL
                """,
                DB_NAME
        );

        assertThat(foreignKeys).hasSize(1);
        assertThat(foreignKeys.get(0).get("REFERENCED_TABLE_NAME")).isEqualTo("community_posts");
        assertThat(foreignKeys.get(0).get("REFERENCED_COLUMN_NAME")).isEqualTo("id");
    }

    @Test
    @DisplayName("5. Verify zero cross-context foreign keys on author_user_id and image_media_asset_id")
    void shouldVerifyZeroCrossContextForeignKeys() {
        List<Map<String, Object>> foreignKeys = jdbc.queryForList(
                """
                SELECT CONSTRAINT_NAME, COLUMN_NAME, REFERENCED_TABLE_NAME
                FROM information_schema.KEY_COLUMN_USAGE
                WHERE TABLE_SCHEMA = ?
                  AND TABLE_NAME = 'community_posts'
                  AND REFERENCED_TABLE_NAME IS NOT NULL
                """,
                DB_NAME
        );

        assertThat(foreignKeys).isEmpty();
    }

    @Test
    @DisplayName("6. Verify ON DELETE CASCADE from community_posts to community_post_revisions")
    void shouldVerifyCascadeDeleteOnPost() {
        UUID postId = UUID.randomUUID();
        UUID authorId = UUID.randomUUID();
        UUID revisionId = UUID.randomUUID();
        Instant now = Instant.now();

        jdbc.update(
                "INSERT INTO community_posts (id, author_user_id, caption, image_media_asset_id, content_version, status, created_at, updated_at) " +
                        "VALUES (?, ?, 'Test caption', NULL, 1, 'PUBLISHED', ?, ?)",
                postId.toString(),
                authorId.toString(),
                Timestamp.from(now),
                Timestamp.from(now)
        );

        jdbc.update(
                "INSERT INTO community_post_revisions (id, post_id, revision_number, editor_user_id, previous_caption, caption, edited_at) " +
                        "VALUES (?, ?, 1, ?, 'Old caption', 'Test caption', ?)",
                revisionId.toString(),
                postId.toString(),
                authorId.toString(),
                Timestamp.from(now)
        );

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM community_post_revisions WHERE post_id = ?", Integer.class, postId.toString())).isEqualTo(1);

        // Delete post
        jdbc.update("DELETE FROM community_posts WHERE id = ?", postId.toString());

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM community_posts WHERE id = ?", Integer.class, postId.toString())).isEqualTo(0);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM community_post_revisions WHERE post_id = ?", Integer.class, postId.toString())).isEqualTo(0);
    }

    @Test
    @DisplayName("7. Verify database check constraints and unique collision rejection")
    void shouldVerifyConstraintsAndUniqueCollision() {
        UUID postId = UUID.randomUUID();
        UUID authorId = UUID.randomUUID();
        Instant now = Instant.now();

        // 1. Caption length > 2000 should be rejected by DB constraint
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO community_posts (id, author_user_id, caption, image_media_asset_id, content_version, status, created_at, updated_at) " +
                        "VALUES (?, ?, ?, NULL, 0, 'PUBLISHED', ?, ?)",
                UUID.randomUUID().toString(),
                authorId.toString(),
                "x".repeat(2001),
                Timestamp.from(now),
                Timestamp.from(now)
        )).isInstanceOf(DataAccessException.class);

        // 2. Insert valid post
        jdbc.update(
                "INSERT INTO community_posts (id, author_user_id, caption, image_media_asset_id, content_version, status, created_at, updated_at) " +
                        "VALUES (?, ?, 'Valid caption', NULL, 0, 'PUBLISHED', ?, ?)",
                postId.toString(),
                authorId.toString(),
                Timestamp.from(now),
                Timestamp.from(now)
        );

        // 3. Insert revision 1
        jdbc.update(
                "INSERT INTO community_post_revisions (id, post_id, revision_number, editor_user_id, previous_caption, caption, edited_at) " +
                        "VALUES (?, ?, 1, ?, 'Old caption', 'Valid caption', ?)",
                UUID.randomUUID().toString(),
                postId.toString(),
                authorId.toString(),
                Timestamp.from(now)
        );

        // 4. Duplicate (post_id, revision_number) should be rejected by UNIQUE constraint
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO community_post_revisions (id, post_id, revision_number, editor_user_id, previous_caption, caption, edited_at) " +
                        "VALUES (?, ?, 1, ?, 'Another Old', 'Valid caption 2', ?)",
                UUID.randomUUID().toString(),
                postId.toString(),
                authorId.toString(),
                Timestamp.from(now)
        )).isInstanceOf(DataAccessException.class);
    }
}
