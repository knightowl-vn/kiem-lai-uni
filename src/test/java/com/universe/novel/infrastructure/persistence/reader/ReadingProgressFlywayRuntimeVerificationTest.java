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

import com.universe.test.TestDatabaseSupport;

class ReadingProgressFlywayRuntimeVerificationTest {

    private static DataSource createDataSource(String dbName) {
        return TestDatabaseSupport.createTestDataSource(dbName);
    }

    private static void resetDatabase(String dbName) {
        TestDatabaseSupport.resetTestDatabase(dbName);
    }

    @Test
    @DisplayName("V26 Flyway migration: Xác thực schema novel_reading_progress, cột, khóa chính, unique, foreign key và check constraints")
    void shouldMigrateCleanDatabaseThroughV26AndVerifySchema() {
        String dbName = "kiemlai_reading_progress_schema_test";
        resetDatabase(dbName);
        DataSource ds = createDataSource(dbName);

        Flyway flyway = Flyway.configure()
                .dataSource(ds)
                .locations("classpath:db/migration")
                .load();

        int migrationsApplied = flyway.migrate().migrationsExecuted;
        assertThat(migrationsApplied).isGreaterThanOrEqualTo(26);

        MigrationInfo[] info = flyway.info().all();
        assertThat(info).hasSizeGreaterThanOrEqualTo(26);

        MigrationInfo v26Info = null;
        for (MigrationInfo mi : info) {
            assertThat(mi.getState()).isEqualTo(MigrationState.SUCCESS);
            if ("26".equals(mi.getVersion().getVersion())) {
                v26Info = mi;
            }
        }

        assertThat(v26Info).isNotNull();
        assertThat(v26Info.getDescription()).isEqualTo("create novel reading progress");
        assertThat(v26Info.getChecksum()).isNotNull();

        JdbcTemplate jdbc = new JdbcTemplate(ds);

        // 1. Check table existence
        Integer tableCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = ? AND table_name = 'novel_reading_progress'",
                Integer.class,
                dbName
        );
        assertThat(tableCount).isEqualTo(1);

        // 2. Verify column definitions
        List<String> columns = jdbc.queryForList(
                "SELECT column_name FROM information_schema.columns WHERE table_schema = ? AND table_name = 'novel_reading_progress'",
                String.class,
                dbName
        );
        assertThat(columns).containsExactlyInAnyOrder(
                "id",
                "user_id",
                "last_opened_chapter_id",
                "highest_reached_chapter_number",
                "persistence_version",
                "created_at",
                "updated_at"
        );

        // 3. Verify Foreign Keys
        List<String> foreignKeys = jdbc.queryForList(
                "SELECT constraint_name FROM information_schema.table_constraints WHERE table_schema = ? AND table_name = 'novel_reading_progress' AND constraint_type = 'FOREIGN KEY'",
                String.class,
                dbName
        );
        assertThat(foreignKeys).contains("fk_novel_reading_progress_last_chapter");

        // 4. Verify Unique Constraints
        List<String> uniqueConstraints = jdbc.queryForList(
                "SELECT constraint_name FROM information_schema.table_constraints WHERE table_schema = ? AND table_name = 'novel_reading_progress' AND constraint_type = 'UNIQUE'",
                String.class,
                dbName
        );
        assertThat(uniqueConstraints).contains("uq_novel_reading_progress_user");

        // 5. Verify Check Constraints
        List<String> checkConstraints = jdbc.queryForList(
                "SELECT constraint_name FROM information_schema.table_constraints WHERE table_schema = ? AND table_name = 'novel_reading_progress' AND constraint_type = 'CHECK'",
                String.class,
                dbName
        );
        assertThat(checkConstraints).contains(
                "chk_novel_reading_progress_highest_num",
                "chk_novel_reading_progress_persistence_version"
        );
    }

    @Test
    @DisplayName("V26 Constraints enforcement: UNIQUE(user_id), FK(last_opened_chapter_id), và CHECK constraints thực thi đúng trên MySQL")
    void shouldEnforceDatabaseConstraintsOnRealMySql() {
        String dbName = "kiemlai_reading_progress_constraints_test";
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
        Instant now = Instant.now();

        // Seed user, volume, chapter
        jdbc.update(
                "INSERT INTO identity_users (id, email, password_hash, display_name, status, aggregate_version, persistence_version, created_at, updated_at) " +
                        "VALUES (?, 'reader@universe.local', '$2a$10$hash', 'Reader', 'ACTIVE', 1, 0, NOW(), NOW())",
                userId.toString()
        );

        jdbc.update(
                "INSERT INTO novel_volumes (id, title, slug, description, sort_order, status, created_by, updated_by, published_by, archived_by, created_at, updated_at, published_at, archived_at, aggregate_version, persistence_version) " +
                        "VALUES (?, 'Quyển 1', 'quyen-1', 'Mô tả', 1, 'PUBLISHED', ?, ?, ?, NULL, NOW(), NOW(), NOW(), NULL, 1, 0)",
                volumeId.toString(), userId.toString(), userId.toString(), userId.toString()
        );

        jdbc.update(
                "INSERT INTO novel_chapters (id, volume_id, chapter_number, title, slug, summary, content, status, " +
                        "created_by, updated_by, published_by, archived_by, created_at, updated_at, published_at, archived_at, aggregate_version, persistence_version, content_version) " +
                        "VALUES (?, ?, 1, 'Chương 1', 'chuong-1', 'Tóm tắt', 'Nội dung', 'PUBLISHED', " +
                        "?, ?, ?, NULL, ?, ?, ?, NULL, 1, 0, 1)",
                chapterId.toString(), volumeId.toString(),
                userId.toString(), userId.toString(), userId.toString(),
                Timestamp.from(now), Timestamp.from(now), Timestamp.from(now)
        );

        // 1. Initial valid progress insert
        UUID progressId1 = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO novel_reading_progress (id, user_id, last_opened_chapter_id, highest_reached_chapter_number, persistence_version, created_at, updated_at) " +
                        "VALUES (?, ?, ?, 1, 0, NOW(), NOW())",
                progressId1.toString(), userId.toString(), chapterId.toString()
        );

        // 2. Duplicate user_id must fail due to UNIQUE constraint
        UUID progressId2 = UUID.randomUUID();
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO novel_reading_progress (id, user_id, last_opened_chapter_id, highest_reached_chapter_number, persistence_version, created_at, updated_at) " +
                        "VALUES (?, ?, ?, 2, 0, NOW(), NOW())",
                progressId2.toString(), userId.toString(), chapterId.toString()
        )).hasMessageContaining("uq_novel_reading_progress_user");

        // 3. Invalid foreign key must fail
        UUID nonExistentChapterId = UUID.randomUUID();
        UUID otherUserId = UUID.randomUUID();
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO novel_reading_progress (id, user_id, last_opened_chapter_id, highest_reached_chapter_number, persistence_version, created_at, updated_at) " +
                        "VALUES (?, ?, ?, 1, 0, NOW(), NOW())",
                UUID.randomUUID().toString(), otherUserId.toString(), nonExistentChapterId.toString()
        )).hasMessageContaining("fk_novel_reading_progress_last_chapter");

        // 4. Invalid highest_reached_chapter_number (0) must fail check constraint
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO novel_reading_progress (id, user_id, last_opened_chapter_id, highest_reached_chapter_number, persistence_version, created_at, updated_at) " +
                        "VALUES (?, ?, ?, 0, 0, NOW(), NOW())",
                UUID.randomUUID().toString(), otherUserId.toString(), chapterId.toString()
        )).hasMessageContaining("chk_novel_reading_progress_highest_num");
    }
}
