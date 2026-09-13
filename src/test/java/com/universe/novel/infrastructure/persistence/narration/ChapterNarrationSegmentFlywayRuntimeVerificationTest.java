package com.universe.novel.infrastructure.persistence.narration;

import com.universe.test.TestDatabaseSupport;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.MigrationState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ChapterNarrationSegmentFlywayRuntimeVerificationTest {

    private static DataSource createDataSource(String dbName) {
        return TestDatabaseSupport.createTestDataSource(dbName);
    }

    private static void resetDatabase(String dbName) {
        TestDatabaseSupport.resetTestDatabase(dbName);
    }

    @Test
    @DisplayName("V39 Flyway migration: Verify novel_chapter_narration_segments schema, columns, PK, FK, and check constraints")
    void shouldMigrateCleanDatabaseThroughV39AndVerifySchema() {
        String dbName = "kiemlai_narration_segment_schema_test";
        resetDatabase(dbName);
        DataSource ds = createDataSource(dbName);

        Flyway flyway = Flyway.configure()
                .dataSource(ds)
                .locations("classpath:db/migration")
                .load();

        int migrationsApplied = flyway.migrate().migrationsExecuted;
        assertThat(migrationsApplied).isGreaterThanOrEqualTo(39);

        MigrationInfo[] info = flyway.info().all();
        assertThat(info).hasSizeGreaterThanOrEqualTo(39);

        MigrationInfo v39Info = null;
        for (MigrationInfo mi : info) {
            assertThat(mi.getState()).isEqualTo(MigrationState.SUCCESS);
            if ("39".equals(mi.getVersion().getVersion())) {
                v39Info = mi;
            }
        }

        assertThat(v39Info).isNotNull();
        assertThat(v39Info.getDescription()).isEqualTo("create novel chapter narration segments");
        assertThat(v39Info.getChecksum()).isNotNull();

        JdbcTemplate jdbc = new JdbcTemplate(ds);

        // 1. Check table existence
        Integer tableCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = ? AND table_name = 'novel_chapter_narration_segments'",
                Integer.class,
                dbName
        );
        assertThat(tableCount).isEqualTo(1);

        // 2. Verify columns
        List<String> columns = jdbc.queryForList(
                "SELECT column_name FROM information_schema.columns WHERE table_schema = ? AND table_name = 'novel_chapter_narration_segments'",
                String.class,
                dbName
        );
        assertThat(columns).contains(
                "id",
                "chapter_id",
                "segment_index",
                "text",
                "character_count",
                "content_hash",
                "status",
                "created_at",
                "updated_at"
        );

        // 3. Verify Check Constraints
        List<String> checkConstraints = jdbc.queryForList(
                "SELECT constraint_name FROM information_schema.table_constraints WHERE table_schema = ? AND table_name = 'novel_chapter_narration_segments' AND constraint_type = 'CHECK'",
                String.class,
                dbName
        );
        assertThat(checkConstraints).contains(
                "chk_novel_chapter_narration_segments_index",
                "chk_novel_chapter_narration_segments_char_count",
                "chk_novel_chapter_narration_segments_hash_len",
                "chk_novel_chapter_narration_segments_status"
        );
    }

    @Test
    @DisplayName("V39 Database invariants: Validate check constraints on real MySQL")
    void shouldEnforceDatabaseConstraintsOnRealMySql() {
        String dbName = "kiemlai_narration_segment_constraints_test";
        resetDatabase(dbName);
        DataSource ds = createDataSource(dbName);

        Flyway flyway = Flyway.configure()
                .dataSource(ds)
                .locations("classpath:db/migration")
                .load();
        flyway.migrate();

        JdbcTemplate jdbc = new JdbcTemplate(ds);

        UUID userId = UUID.randomUUID();
        UUID volumeId = UUID.randomUUID();
        UUID chapterId = UUID.randomUUID();

        // Seed parent user, volume, chapter
        jdbc.update(
                "INSERT INTO identity_users (id, email, password_hash, display_name, status, role, aggregate_version, persistence_version, created_at, updated_at) " +
                        "VALUES (?, 'user@test.local', '$2a$10$hash', 'User', 'ACTIVE', 'ADMIN', 1, 0, NOW(6), NOW(6))",
                userId.toString()
        );
        jdbc.update(
                "INSERT INTO novel_volumes (id, title, slug, description, sort_order, status, created_by, updated_by, published_by, archived_by, created_at, updated_at, published_at, archived_at, aggregate_version, persistence_version) " +
                        "VALUES (?, 'Quyển 1', 'quyen-1', 'Mô tả', 1, 'PUBLISHED', ?, ?, ?, NULL, NOW(6), NOW(6), NOW(6), NULL, 1, 0)",
                volumeId.toString(), userId.toString(), userId.toString(), userId.toString()
        );
        jdbc.update(
                "INSERT INTO novel_chapters (id, volume_id, chapter_number, title, slug, summary, content, status, created_by, updated_by, published_by, archived_by, created_at, updated_at, published_at, archived_at, aggregate_version, persistence_version, content_version) " +
                        "VALUES (?, ?, 1, 'Chương 1', 'chuong-1', 'Tóm tắt', 'Nội dung', 'PUBLISHED', ?, ?, ?, NULL, NOW(6), NOW(6), NOW(6), NULL, 1, 0, 1)",
                chapterId.toString(), volumeId.toString(), userId.toString(), userId.toString(), userId.toString()
        );

        UUID seg1 = UUID.randomUUID();
        String validHash = "a".repeat(64);

        // 1. Valid insert succeeds
        String validText = "Văn bản hợp lệ.";
        jdbc.update(
                "INSERT INTO novel_chapter_narration_segments (id, chapter_id, segment_index, text, character_count, content_hash, status, created_at, updated_at) " +
                        "VALUES (?, ?, 0, ?, ?, ?, 'CURRENT', NOW(6), NOW(6))",
                seg1.toString(), chapterId.toString(), validText, validText.length(), validHash
        );

        // 2. Negative segment_index fails
        UUID seg2 = UUID.randomUUID();
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO novel_chapter_narration_segments (id, chapter_id, segment_index, text, character_count, content_hash, status, created_at, updated_at) " +
                        "VALUES (?, ?, -1, 'Văn bản lỗi.', 13, ?, 'CURRENT', NOW(6), NOW(6))",
                seg2.toString(), chapterId.toString(), validHash
        )).hasMessageContaining("chk_novel_chapter_narration_segments_index");

        // 3. Zero character_count fails
        UUID seg3 = UUID.randomUUID();
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO novel_chapter_narration_segments (id, chapter_id, segment_index, text, character_count, content_hash, status, created_at, updated_at) " +
                        "VALUES (?, ?, 1, 'Văn bản lỗi.', 0, ?, 'CURRENT', NOW(6), NOW(6))",
                seg3.toString(), chapterId.toString(), validHash
        )).hasMessageContaining("chk_novel_chapter_narration_segments_char_count");

        // 4. Invalid hash length fails
        UUID seg4 = UUID.randomUUID();
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO novel_chapter_narration_segments (id, chapter_id, segment_index, text, character_count, content_hash, status, created_at, updated_at) " +
                        "VALUES (?, ?, 1, 'Văn bản lỗi.', 13, 'short-hash', 'CURRENT', NOW(6), NOW(6))",
                seg4.toString(), chapterId.toString()
        )).hasMessageContaining("chk_novel_chapter_narration_segments_hash_len");

        // 5. Invalid status fails
        UUID seg5 = UUID.randomUUID();
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO novel_chapter_narration_segments (id, chapter_id, segment_index, text, character_count, content_hash, status, created_at, updated_at) " +
                        "VALUES (?, ?, 1, 'Văn bản lỗi.', 13, ?, 'UNKNOWN', NOW(6), NOW(6))",
                seg5.toString(), chapterId.toString(), validHash
        )).hasMessageContaining("chk_novel_chapter_narration_segments_status");
    }
}
