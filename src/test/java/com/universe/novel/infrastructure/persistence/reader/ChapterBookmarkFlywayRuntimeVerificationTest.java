package com.universe.novel.infrastructure.persistence.reader;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.MigrationState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import javax.sql.DataSource;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ChapterBookmarkFlywayRuntimeVerificationTest {

    private static String resolveHost() {
        String host = System.getProperty("test.mysql.host");
        if (host != null && !host.isBlank()) {
            return host.trim();
        }
        String envHost = System.getenv("TEST_MYSQL_HOST");
        if (envHost != null && !envHost.isBlank()) {
            return envHost.trim();
        }
        return "localhost:3306";
    }

    private static String resolveUser() {
        String user = System.getProperty("test.mysql.user");
        if (user != null && !user.isBlank()) {
            return user.trim();
        }
        String envUser = System.getenv("TEST_MYSQL_USER");
        if (envUser != null && !envUser.isBlank()) {
            return envUser.trim();
        }
        return "root";
    }

    private static String resolvePassword() {
        String pass = System.getProperty("test.mysql.pass");
        if (pass != null && !pass.isBlank()) {
            return pass;
        }
        String envPass = System.getenv("TEST_MYSQL_PASS");
        if (envPass != null && !envPass.isBlank()) {
            return envPass;
        }
        String rootPass = System.getenv("MYSQL_ROOT_PASSWORD");
        if (rootPass != null && !rootPass.isBlank()) {
            return rootPass;
        }
        String dbPass = System.getenv("DB_PASSWORD");
        if (dbPass != null && !dbPass.isBlank()) {
            return dbPass;
        }
        return "";
    }

    private static DataSource createDataSource(String dbName) {
        DriverManagerDataSource ds = new DriverManagerDataSource();
        ds.setDriverClassName("com.mysql.cj.jdbc.Driver");
        ds.setUrl("jdbc:mysql://" + resolveHost() + "/" + dbName + "?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC");
        ds.setUsername(resolveUser());
        ds.setPassword(resolvePassword());
        return ds;
    }

    private static void resetDatabase(String dbName) {
        DriverManagerDataSource rootDs = new DriverManagerDataSource();
        rootDs.setDriverClassName("com.mysql.cj.jdbc.Driver");
        rootDs.setUrl("jdbc:mysql://" + resolveHost() + "/mysql?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC");
        rootDs.setUsername(resolveUser());
        rootDs.setPassword(resolvePassword());

        JdbcTemplate rootJdbc = new JdbcTemplate(rootDs);
        rootJdbc.execute("DROP DATABASE IF EXISTS " + dbName);
        rootJdbc.execute("CREATE DATABASE " + dbName + " CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci");
    }

    @Test
    @DisplayName("V27 Flyway migration: Xác thực schema novel_chapter_bookmarks, cột, khóa chính, unique, và foreign key constraint")
    void shouldMigrateCleanDatabaseThroughV27AndVerifySchema() {
        String dbName = "kiemlai_chapter_bookmarks_schema_test";
        resetDatabase(dbName);
        DataSource ds = createDataSource(dbName);

        Flyway flyway = Flyway.configure()
                .dataSource(ds)
                .locations("classpath:db/migration")
                .load();

        int migrationsApplied = flyway.migrate().migrationsExecuted;
        assertThat(migrationsApplied).isGreaterThanOrEqualTo(27);

        MigrationInfo[] info = flyway.info().all();
        assertThat(info).hasSizeGreaterThanOrEqualTo(27);

        MigrationInfo v27Info = null;
        for (MigrationInfo mi : info) {
            assertThat(mi.getState()).isEqualTo(MigrationState.SUCCESS);
            if ("27".equals(mi.getVersion().getVersion())) {
                v27Info = mi;
            }
        }

        assertThat(v27Info).isNotNull();
        assertThat(v27Info.getDescription()).isEqualTo("create novel chapter bookmarks");
        assertThat(v27Info.getChecksum()).isNotNull();

        JdbcTemplate jdbc = new JdbcTemplate(ds);

        // 1. Check table existence
        Integer tableCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = ? AND table_name = 'novel_chapter_bookmarks'",
                Integer.class,
                dbName
        );
        assertThat(tableCount).isEqualTo(1);

        // 2. Verify column definitions
        List<String> columns = jdbc.queryForList(
                "SELECT column_name FROM information_schema.columns WHERE table_schema = ? AND table_name = 'novel_chapter_bookmarks'",
                String.class,
                dbName
        );
        assertThat(columns).containsExactlyInAnyOrder(
                "id",
                "user_id",
                "chapter_id",
                "created_at"
        );

        // 3. Verify Foreign Keys
        List<String> foreignKeys = jdbc.queryForList(
                "SELECT constraint_name FROM information_schema.table_constraints WHERE table_schema = ? AND table_name = 'novel_chapter_bookmarks' AND constraint_type = 'FOREIGN KEY'",
                String.class,
                dbName
        );
        assertThat(foreignKeys).contains("fk_novel_chapter_bookmarks_chapter");

        // 4. Verify Unique Constraints
        List<String> uniqueConstraints = jdbc.queryForList(
                "SELECT constraint_name FROM information_schema.table_constraints WHERE table_schema = ? AND table_name = 'novel_chapter_bookmarks' AND constraint_type = 'UNIQUE'",
                String.class,
                dbName
        );
        assertThat(uniqueConstraints).contains("uq_novel_chapter_bookmarks_user_chapter");
    }

    @Test
    @DisplayName("V27 Constraints enforcement: UNIQUE(user_id, chapter_id), FK(chapter_id) và ON DELETE RESTRICT thực thi đúng trên MySQL")
    void shouldEnforceDatabaseConstraintsOnRealMySql() {
        String dbName = "kiemlai_chapter_bookmarks_constraints_test";
        resetDatabase(dbName);
        DataSource ds = createDataSource(dbName);

        Flyway flyway = Flyway.configure()
                .dataSource(ds)
                .locations("classpath:db/migration")
                .load();
        flyway.migrate();

        JdbcTemplate jdbc = new JdbcTemplate(ds);

        UUID user1Id = UUID.randomUUID();
        UUID user2Id = UUID.randomUUID();
        UUID volumeId = UUID.randomUUID();
        UUID chapter1Id = UUID.randomUUID();
        UUID chapter2Id = UUID.randomUUID();
        Instant now = Instant.now();

        // Seed users, volume, chapters
        jdbc.update(
                "INSERT INTO identity_users (id, email, password_hash, display_name, status, aggregate_version, persistence_version, created_at, updated_at) " +
                        "VALUES (?, 'user1@universe.local', '$2a$10$hash', 'User 1', 'ACTIVE', 1, 0, NOW(), NOW())",
                user1Id.toString()
        );
        jdbc.update(
                "INSERT INTO identity_users (id, email, password_hash, display_name, status, aggregate_version, persistence_version, created_at, updated_at) " +
                        "VALUES (?, 'user2@universe.local', '$2a$10$hash', 'User 2', 'ACTIVE', 1, 0, NOW(), NOW())",
                user2Id.toString()
        );

        jdbc.update(
                "INSERT INTO novel_volumes (id, title, slug, description, sort_order, status, created_by, updated_by, published_by, archived_by, created_at, updated_at, published_at, archived_at, aggregate_version, persistence_version) " +
                        "VALUES (?, 'Quyển 1', 'quyen-1', 'Mô tả', 1, 'PUBLISHED', ?, ?, ?, NULL, NOW(), NOW(), NOW(), NULL, 1, 0)",
                volumeId.toString(), user1Id.toString(), user1Id.toString(), user1Id.toString()
        );

        jdbc.update(
                "INSERT INTO novel_chapters (id, volume_id, chapter_number, title, slug, summary, content, status, " +
                        "created_by, updated_by, published_by, archived_by, created_at, updated_at, published_at, archived_at, aggregate_version, persistence_version, content_version) " +
                        "VALUES (?, ?, 1, 'Chương 1', 'chuong-1', 'Tóm tắt', 'Nội dung', 'PUBLISHED', ?, ?, ?, NULL, ?, ?, ?, NULL, 1, 0, 1)",
                chapter1Id.toString(), volumeId.toString(),
                user1Id.toString(), user1Id.toString(), user1Id.toString(),
                Timestamp.from(now), Timestamp.from(now), Timestamp.from(now)
        );

        jdbc.update(
                "INSERT INTO novel_chapters (id, volume_id, chapter_number, title, slug, summary, content, status, " +
                        "created_by, updated_by, published_by, archived_by, created_at, updated_at, published_at, archived_at, aggregate_version, persistence_version, content_version) " +
                        "VALUES (?, ?, 2, 'Chương 2', 'chuong-2', 'Tóm tắt', 'Nội dung', 'PUBLISHED', ?, ?, ?, NULL, ?, ?, ?, NULL, 1, 0, 1)",
                chapter2Id.toString(), volumeId.toString(),
                user1Id.toString(), user1Id.toString(), user1Id.toString(),
                Timestamp.from(now), Timestamp.from(now), Timestamp.from(now)
        );

        // 1. Initial valid bookmark insert
        UUID bookmark1Id = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO novel_chapter_bookmarks (id, user_id, chapter_id, created_at) " +
                        "VALUES (?, ?, ?, NOW())",
                bookmark1Id.toString(), user1Id.toString(), chapter1Id.toString()
        );

        // 2. Duplicate user_id + chapter_id must fail due to UNIQUE constraint
        UUID bookmarkDupId = UUID.randomUUID();
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO novel_chapter_bookmarks (id, user_id, chapter_id, created_at) " +
                        "VALUES (?, ?, ?, NOW())",
                bookmarkDupId.toString(), user1Id.toString(), chapter1Id.toString()
        )).hasMessageContaining("uq_novel_chapter_bookmarks_user_chapter");

        // 3. Different user bookmarking same chapter succeeds
        UUID bookmark2Id = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO novel_chapter_bookmarks (id, user_id, chapter_id, created_at) " +
                        "VALUES (?, ?, ?, NOW())",
                bookmark2Id.toString(), user2Id.toString(), chapter1Id.toString()
        );

        // 4. Same user bookmarking different chapter succeeds
        UUID bookmark3Id = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO novel_chapter_bookmarks (id, user_id, chapter_id, created_at) " +
                        "VALUES (?, ?, ?, NOW())",
                bookmark3Id.toString(), user1Id.toString(), chapter2Id.toString()
        );

        // 5. Invalid FK (non-existent chapter) must fail
        UUID nonExistentChapterId = UUID.randomUUID();
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO novel_chapter_bookmarks (id, user_id, chapter_id, created_at) " +
                        "VALUES (?, ?, ?, NOW())",
                UUID.randomUUID().toString(), user1Id.toString(), nonExistentChapterId.toString()
        )).hasMessageContaining("fk_novel_chapter_bookmarks_chapter");

        // 6. Hard-deleting chapter with existing bookmark must be blocked by ON DELETE RESTRICT
        assertThatThrownBy(() -> jdbc.update(
                "DELETE FROM novel_chapters WHERE id = ?",
                chapter1Id.toString()
        )).hasMessageContaining("foreign key constraint");
    }
}
