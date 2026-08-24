package com.universe.novel.infrastructure.persistence.revision;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.MigrationState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ChapterRevisionFlywayRuntimeVerificationTest {

    private static final String MYSQL_HOST = "localhost:3306";
    private static final String DB_USER = "root";
    private static final String DB_PASS = "123456";

    private static DataSource createDataSource(String dbName) {
        DriverManagerDataSource ds = new DriverManagerDataSource();
        ds.setDriverClassName("com.mysql.cj.jdbc.Driver");
        ds.setUrl("jdbc:mysql://" + MYSQL_HOST + "/" + dbName + "?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC");
        ds.setUsername(DB_USER);
        ds.setPassword(DB_PASS);
        return ds;
    }

    private static void resetDatabase(String dbName) {
        DriverManagerDataSource rootDs = new DriverManagerDataSource();
        rootDs.setDriverClassName("com.mysql.cj.jdbc.Driver");
        rootDs.setUrl("jdbc:mysql://" + MYSQL_HOST + "/mysql?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC");
        rootDs.setUsername(DB_USER);
        rootDs.setPassword(DB_PASS);

        JdbcTemplate rootJdbc = new JdbcTemplate(rootDs);
        rootJdbc.execute("DROP DATABASE IF EXISTS " + dbName);
        rootJdbc.execute("CREATE DATABASE " + dbName + " CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci");
    }

    @Test
    @DisplayName("Step 3: Flyway migration từ cơ sở dữ liệu sạch qua V1..V25 và kiểm tra cấu trúc schema V25")
    void shouldMigrateCleanDatabaseThroughV25AndVerifySchema() {
        String dbName = "kiemlai_clean_test";
        resetDatabase(dbName);
        DataSource ds = createDataSource(dbName);

        Flyway flyway = Flyway.configure()
                .dataSource(ds)
                .locations("classpath:db/migration")
                .load();

        int migrationsApplied = flyway.migrate().migrationsExecuted;
        assertThat(migrationsApplied).isGreaterThanOrEqualTo(25);

        MigrationInfo[] info = flyway.info().all();
        assertThat(info).hasSizeGreaterThanOrEqualTo(25);

        MigrationInfo v25Info = null;
        for (MigrationInfo mi : info) {
            assertThat(mi.getState()).isEqualTo(MigrationState.SUCCESS);
            if ("25".equals(mi.getVersion().getVersion())) {
                v25Info = mi;
            }
        }

        assertThat(v25Info).isNotNull();
        assertThat(v25Info.getDescription()).isEqualTo("create novel chapter revisions");
        assertThat(v25Info.getChecksum()).isNotNull();

        // Verify Schema elements in novel_chapter_revisions
        JdbcTemplate jdbc = new JdbcTemplate(ds);

        // Check table exists
        Integer tableCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = ? AND table_name = 'novel_chapter_revisions'",
                Integer.class,
                dbName
        );
        assertThat(tableCount).isEqualTo(1);

        // Verify columns
        List<String> columns = jdbc.queryForList(
                "SELECT column_name FROM information_schema.columns WHERE table_schema = ? AND table_name = 'novel_chapter_revisions'",
                String.class,
                dbName
        );
        assertThat(columns).contains(
                "id", "chapter_id", "volume_id", "revision_number", "content_version",
                "chapter_number", "title", "slug", "summary", "content", "status",
                "change_type", "edit_summary", "edited_by", "created_at"
        );

        // Verify Foreign Keys
        List<String> foreignKeys = jdbc.queryForList(
                "SELECT constraint_name FROM information_schema.table_constraints WHERE table_schema = ? AND table_name = 'novel_chapter_revisions' AND constraint_type = 'FOREIGN KEY'",
                String.class,
                dbName
        );
        assertThat(foreignKeys).contains("fk_novel_chapter_revisions_chapter");

        // Verify Unique Constraint
        List<String> uniqueConstraints = jdbc.queryForList(
                "SELECT constraint_name FROM information_schema.table_constraints WHERE table_schema = ? AND table_name = 'novel_chapter_revisions' AND constraint_type = 'UNIQUE'",
                String.class,
                dbName
        );
        assertThat(uniqueConstraints).contains("uq_novel_chapter_revisions_chapter_number");

        // Verify Check Constraints
        List<String> checkConstraints = jdbc.queryForList(
                "SELECT constraint_name FROM information_schema.table_constraints WHERE table_schema = ? AND table_name = 'novel_chapter_revisions' AND constraint_type = 'CHECK'",
                String.class,
                dbName
        );
        assertThat(checkConstraints).contains(
                "chk_novel_chapter_revisions_revision_number",
                "chk_novel_chapter_revisions_content_version",
                "chk_novel_chapter_revisions_chapter_number",
                "chk_novel_chapter_revisions_title_length",
                "chk_novel_chapter_revisions_slug_length",
                "chk_novel_chapter_revisions_summary_length",
                "chk_novel_chapter_revisions_content_length",
                "chk_novel_chapter_revisions_status",
                "chk_novel_chapter_revisions_change_type",
                "chk_novel_chapter_revisions_edit_summary"
        );
    }

    @Test
    @DisplayName("Step 4: BASELINE backfill khi áp dụng V25 lên database đã có sẵn Chapter từ V24")
    void shouldBackfillBaselineRevisionWhenMigratingFromV24WithExistingChapter() {
        String dbName = "kiemlai_backfill_test";
        resetDatabase(dbName);
        DataSource ds = createDataSource(dbName);

        // 1. Migrate only up to V24
        Flyway flywayV24 = Flyway.configure()
                .dataSource(ds)
                .locations("classpath:db/migration")
                .target("24")
                .load();
        flywayV24.migrate();

        JdbcTemplate jdbc = new JdbcTemplate(ds);

        // 2. Insert test data in V24 schema
        UUID userId = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO identity_users (id, email, password_hash, display_name, status, aggregate_version, persistence_version, created_at, updated_at) " +
                        "VALUES (?, 'admin@universe.local', '$2a$10$hash', 'Admin', 'ACTIVE', 1, 0, NOW(), NOW())",
                userId.toString()
        );

        UUID volumeId = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO novel_volumes (id, title, slug, description, sort_order, status, created_by, updated_by, published_by, archived_by, created_at, updated_at, published_at, archived_at, aggregate_version, persistence_version) " +
                        "VALUES (?, 'Quyển 1: Lũng Trung Đối', 'quyen-1', 'Mở đầu', 1, 'PUBLISHED', ?, ?, ?, NULL, NOW(), NOW(), NOW(), NULL, 1, 0)",
                volumeId.toString(), userId.toString(), userId.toString(), userId.toString()
        );

        UUID chapterId = UUID.randomUUID();
        Instant createdAt = Instant.parse("2026-08-20T10:00:00Z");
        Instant updatedAt = Instant.parse("2026-08-20T12:00:00Z");
        Instant publishedAt = Instant.parse("2026-08-20T12:00:00Z");

        jdbc.update(
                "INSERT INTO novel_chapters (id, volume_id, chapter_number, title, slug, summary, content, status, " +
                        "created_by, updated_by, published_by, archived_by, created_at, updated_at, published_at, archived_at, aggregate_version, persistence_version, content_version) " +
                        "VALUES (?, ?, 1, 'Chương 1: Thiếu niên Trần Bình An', 'quyen-1-chuong-1', 'Tóm tắt chương 1', 'Nội dung chi tiết chương 1', 'PUBLISHED', " +
                        "?, ?, ?, NULL, ?, ?, ?, NULL, 3, 0, 2)",
                chapterId.toString(), volumeId.toString(),
                userId.toString(), userId.toString(), userId.toString(),
                Timestamp.from(createdAt), Timestamp.from(updatedAt), Timestamp.from(publishedAt)
        );

        // 3. Now apply V25
        Flyway flywayV25 = Flyway.configure()
                .dataSource(ds)
                .locations("classpath:db/migration")
                .target("25")
                .load();
        flywayV25.migrate();

        // 4. Verify exactly 1 BASELINE revision was backfilled
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT * FROM novel_chapter_revisions WHERE chapter_id = ?",
                chapterId.toString()
        );

        assertThat(rows).hasSize(1);
        Map<String, Object> baseline = rows.get(0);

        assertThat(baseline.get("chapter_id")).isEqualTo(chapterId.toString());
        assertThat(baseline.get("volume_id")).isEqualTo(volumeId.toString());
        assertThat(((Number) baseline.get("revision_number")).longValue()).isEqualTo(3L);
        assertThat(((Number) baseline.get("content_version")).longValue()).isEqualTo(2L);
        assertThat(((Number) baseline.get("chapter_number")).intValue()).isEqualTo(1);
        assertThat(baseline.get("title")).isEqualTo("Chương 1: Thiếu niên Trần Bình An");
        assertThat(baseline.get("slug")).isEqualTo("quyen-1-chuong-1");
        assertThat(baseline.get("summary")).isEqualTo("Tóm tắt chương 1");
        assertThat(baseline.get("content")).isEqualTo("Nội dung chi tiết chương 1");
        assertThat(baseline.get("status")).isEqualTo("PUBLISHED");
        assertThat(baseline.get("change_type")).isEqualTo("BASELINE");
        assertThat(baseline.get("edit_summary")).isNull();
        assertThat(baseline.get("edited_by")).isEqualTo(userId.toString());
        assertThat(baseline.get("created_at")).isNotNull();
        assertThat(baseline.get("created_at").toString()).contains("2026-08-20");

        // 5. Verify Foreign Key constraint prevents hard-deleting the chapter directly
        assertThatThrownBy(() -> jdbc.update("DELETE FROM novel_chapters WHERE id = ?", chapterId.toString()))
                .hasMessageContaining("foreign key constraint");
    }

    @Test
    @DisplayName("Step 5 & 6: Luồng lưu trữ thực tế, sắp xếp query, và kiểm tra Transaction Rollback")
    void shouldVerifyRealPersistenceFlowAndTransactionRollback() {
        String dbName = "kiemlai_integration_test";
        resetDatabase(dbName);
        DataSource ds = createDataSource(dbName);

        Flyway flyway = Flyway.configure()
                .dataSource(ds)
                .locations("classpath:db/migration")
                .load();
        flyway.migrate();

        JdbcTemplate jdbc = new JdbcTemplate(ds);

        UUID userId = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO identity_users (id, email, password_hash, display_name, status, aggregate_version, persistence_version, created_at, updated_at) " +
                        "VALUES (?, 'admin2@universe.local', '$2a$10$hash', 'Admin 2', 'ACTIVE', 1, 0, NOW(), NOW())",
                userId.toString()
        );

        UUID volumeId = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO novel_volumes (id, title, slug, description, sort_order, status, created_by, updated_by, published_by, archived_by, created_at, updated_at, published_at, archived_at, aggregate_version, persistence_version) " +
                        "VALUES (?, 'Quyển 1', 'quyen-1', 'Mô tả', 1, 'DRAFT', ?, ?, NULL, NULL, NOW(), NOW(), NULL, NULL, 1, 0)",
                volumeId.toString(), userId.toString(), userId.toString()
        );

        UUID chapterId = UUID.randomUUID();
        Instant now = Instant.now();

        // A. CREATE_DRAFT mutation
        jdbc.update(
                "INSERT INTO novel_chapters (id, volume_id, chapter_number, title, slug, summary, content, status, " +
                        "created_by, updated_by, published_by, archived_by, created_at, updated_at, published_at, archived_at, aggregate_version, persistence_version, content_version) " +
                        "VALUES (?, ?, 1, 'Chương 1', 'quyen-1-chuong-1', 'Tóm tắt', 'Nội dung', 'DRAFT', ?, ?, NULL, NULL, ?, ?, NULL, NULL, 1, 0, 1)",
                chapterId.toString(), volumeId.toString(),
                userId.toString(), userId.toString(),
                Timestamp.from(now), Timestamp.from(now)
        );

        jdbc.update(
                "INSERT INTO novel_chapter_revisions (id, chapter_id, volume_id, revision_number, content_version, chapter_number, title, slug, summary, content, status, change_type, edit_summary, edited_by, created_at) " +
                        "VALUES (?, ?, ?, 1, 1, 1, 'Chương 1', 'quyen-1-chuong-1', 'Tóm tắt', 'Nội dung', 'DRAFT', 'CREATE_DRAFT', NULL, ?, ?)",
                UUID.randomUUID().toString(), chapterId.toString(), volumeId.toString(),
                userId.toString(), Timestamp.from(now)
        );

        // B. UPDATE_DRAFT mutation
        Instant updateTime = now.plusSeconds(300);
        jdbc.update(
                "UPDATE novel_chapters SET title = 'Chương 1 - Đã sửa', summary = 'Tóm tắt mới', content = 'Nội dung mới', updated_by = ?, updated_at = ?, aggregate_version = 2, content_version = 2 WHERE id = ?",
                userId.toString(), Timestamp.from(updateTime), chapterId.toString()
        );

        jdbc.update(
                "INSERT INTO novel_chapter_revisions (id, chapter_id, volume_id, revision_number, content_version, chapter_number, title, slug, summary, content, status, change_type, edit_summary, edited_by, created_at) " +
                        "VALUES (?, ?, ?, 2, 2, 1, 'Chương 1 - Đã sửa', 'quyen-1-chuong-1', 'Tóm tắt mới', 'Nội dung mới', 'DRAFT', 'UPDATE_DRAFT', 'Sửa lần 1', ?, ?)",
                UUID.randomUUID().toString(), chapterId.toString(), volumeId.toString(),
                userId.toString(), Timestamp.from(updateTime)
        );

        // C & D. Verify revisions correspond to aggregateVersion and query newest first
        List<Map<String, Object>> revisions = jdbc.queryForList(
                "SELECT revision_number, change_type, title FROM novel_chapter_revisions WHERE chapter_id = ? ORDER BY revision_number DESC",
                chapterId.toString()
        );

        assertThat(revisions).hasSize(2);
        assertThat(((Number) revisions.get(0).get("revision_number")).longValue()).isEqualTo(2L);
        assertThat(revisions.get(0).get("change_type")).isEqualTo("UPDATE_DRAFT");
        assertThat(revisions.get(0).get("title")).isEqualTo("Chương 1 - Đã sửa");

        assertThat(((Number) revisions.get(1).get("revision_number")).longValue()).isEqualTo(1L);
        assertThat(revisions.get(1).get("change_type")).isEqualTo("CREATE_DRAFT");
        assertThat(revisions.get(1).get("title")).isEqualTo("Chương 1");

        // Rollback Verification using explicit manual transaction
        try (Connection conn = ds.getConnection()) {
            conn.setAutoCommit(false);
            try (var stmt = conn.prepareStatement("UPDATE novel_chapters SET title = 'Chương Chưa Commit', aggregate_version = 3 WHERE id = ?")) {
                stmt.setString(1, chapterId.toString());
                stmt.executeUpdate();
            }

            // Force failure (simulating rollback before commit)
            conn.rollback();
            conn.setAutoCommit(true);
        } catch (Exception e) {
            // Handled
        }

        // Verify chapter state remained unchanged after rollback
        String currentTitle = jdbc.queryForObject(
                "SELECT title FROM novel_chapters WHERE id = ?",
                String.class,
                chapterId.toString()
        );
        Long currentVersion = jdbc.queryForObject(
                "SELECT aggregate_version FROM novel_chapters WHERE id = ?",
                Long.class,
                chapterId.toString()
        );

        assertThat(currentTitle).isEqualTo("Chương 1 - Đã sửa");
        assertThat(currentVersion).isEqualTo(2L);

        // Verify no extra revision was recorded
        Integer revCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM novel_chapter_revisions WHERE chapter_id = ?",
                Integer.class,
                chapterId.toString()
        );
        assertThat(revCount).isEqualTo(2);
    }
}
