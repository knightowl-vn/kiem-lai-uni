package com.universe.novel.infrastructure.persistence.narration;

import com.universe.test.TestDatabaseSupport;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.util.Arrays;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ChapterNarrationAudioFailureFlywayRuntimeVerificationTest {

    private static DataSource createDataSource(String dbName) {
        return TestDatabaseSupport.createTestDataSource(dbName);
    }

    private static void resetDatabase(String dbName) {
        TestDatabaseSupport.resetTestDatabase(dbName);
    }

    @Test
    @DisplayName("V41 Migration: Clean database migration up to V41")
    void shouldMigrateCleanDatabaseUpToV41() {
        String dbName = "kiemlai_narration_failure_schema_test";
        resetDatabase(dbName);
        DataSource ds = createDataSource(dbName);

        Flyway flyway = Flyway.configure()
                .dataSource(ds)
                .locations("classpath:db/migration")
                .load();

        int applied = flyway.migrate().migrationsExecuted;
        assertThat(applied).isGreaterThanOrEqualTo(41);

        MigrationInfo current = flyway.info().current();
        assertThat(current).isNotNull();
        assertThat(Integer.parseInt(current.getVersion().getVersion())).isGreaterThanOrEqualTo(41);

        MigrationInfo v41 = Arrays.stream(flyway.info().applied())
                .filter(m -> "41".equals(m.getVersion().getVersion()))
                .findFirst()
                .orElse(null);

        assertThat(v41).isNotNull();
        assertThat(v41.getDescription()).isEqualTo("create novel chapter narration audio failures");
    }

    @Test
    @DisplayName("V41 Database invariants: Validate check, unique and foreign key constraints on real MySQL")
    void shouldEnforceDatabaseConstraintsOnRealMySql() {
        String dbName = "kiemlai_narration_failure_constraints_test";
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
        UUID segmentId = UUID.randomUUID();
        UUID voiceId = UUID.randomUUID();

        // 1. Seed user, volume, chapter, segment, managed voice
        jdbc.update(
                "INSERT INTO identity_users (id, email, password_hash, display_name, status, role, aggregate_version, persistence_version, created_at, updated_at) " +
                        "VALUES (?, 'failure-test@universe.local', '$2a$10$hash', 'Failure Tester', 'ACTIVE', 'ADMIN', 1, 0, NOW(6), NOW(6))",
                userId.toString()
        );
        jdbc.update(
                "INSERT INTO novel_volumes (id, title, slug, description, sort_order, status, created_by, updated_by, published_by, archived_by, created_at, updated_at, published_at, archived_at, aggregate_version, persistence_version) " +
                        "VALUES (?, 'Quyển Lỗi', 'quyen-loi', 'Mô tả', 1, 'PUBLISHED', ?, ?, ?, NULL, NOW(6), NOW(6), NOW(6), NULL, 1, 0)",
                volumeId.toString(), userId.toString(), userId.toString(), userId.toString()
        );
        jdbc.update(
                "INSERT INTO novel_chapters (id, volume_id, chapter_number, title, slug, summary, content, status, created_by, updated_by, published_by, archived_by, created_at, updated_at, published_at, archived_at, aggregate_version, persistence_version, content_version) " +
                        "VALUES (?, ?, 1, 'Chương 1', 'chuong-1', 'Tóm tắt', 'Nội dung', 'PUBLISHED', ?, ?, ?, NULL, NOW(6), NOW(6), NOW(6), NULL, 1, 0, 1)",
                chapterId.toString(), volumeId.toString(), userId.toString(), userId.toString(), userId.toString()
        );

        String validText = "Văn bản phân đoạn.";
        String validHash = "a".repeat(64);
        jdbc.update(
                "INSERT INTO novel_chapter_narration_segments (id, chapter_id, segment_index, text, character_count, content_hash, status, created_at, updated_at) " +
                        "VALUES (?, ?, 0, ?, ?, ?, 'CURRENT', NOW(6), NOW(6))",
                segmentId.toString(), chapterId.toString(), validText, validText.length(), validHash
        );

        jdbc.update(
                "INSERT INTO novel_managed_voices (id, voice_key, display_name, provider_voice_id, status, display_order, is_default, synthesis_revision, persistence_version, created_at, updated_at) " +
                        "VALUES (?, 'voice-test', 'Giọng Thử Nghiệm', 'provider-01', 'ACTIVE', 1, TRUE, 1, 0, NOW(6), NOW(6))",
                voiceId.toString()
        );

        // 2. Valid insert succeeds
        UUID failureId1 = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO novel_chapter_narration_audio_failures (id, segment_id, managed_voice_id, operation, stage, attempted_synthesis_revision, failure_count, error_type, error_message, first_failed_at, last_failed_at) " +
                        "VALUES (?, ?, ?, 'INITIAL_GENERATION', 'TTS_SYNTHESIS', 1, 1, 'ConnectException', 'Timeout', NOW(6), NOW(6))",
                failureId1.toString(), segmentId.toString(), voiceId.toString()
        );

        // 3. Invalid attempted_synthesis_revision (< 1) fails CHECK constraint
        UUID failureId2 = UUID.randomUUID();
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO novel_chapter_narration_audio_failures (id, segment_id, managed_voice_id, operation, stage, attempted_synthesis_revision, failure_count, error_type, error_message, first_failed_at, last_failed_at) " +
                        "VALUES (?, ?, ?, 'INITIAL_GENERATION', 'TTS_SYNTHESIS', 0, 1, 'ConnectException', 'Timeout', NOW(6), NOW(6))",
                failureId2.toString(), segmentId.toString(), voiceId.toString()
        )).hasMessageContaining("chk_novel_narration_failure_revision");

        // 4. Invalid failure_count (< 1) fails CHECK constraint
        UUID failureId3 = UUID.randomUUID();
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO novel_chapter_narration_audio_failures (id, segment_id, managed_voice_id, operation, stage, attempted_synthesis_revision, failure_count, error_type, error_message, first_failed_at, last_failed_at) " +
                        "VALUES (?, ?, ?, 'INITIAL_GENERATION', 'TTS_SYNTHESIS', 1, 0, 'ConnectException', 'Timeout', NOW(6), NOW(6))",
                failureId3.toString(), segmentId.toString(), voiceId.toString()
        )).hasMessageContaining("chk_novel_narration_failure_count");

        // 5. Duplicate (segment_id, managed_voice_id) fails UNIQUE constraint
        UUID failureId4 = UUID.randomUUID();
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO novel_chapter_narration_audio_failures (id, segment_id, managed_voice_id, operation, stage, attempted_synthesis_revision, failure_count, error_type, error_message, first_failed_at, last_failed_at) " +
                        "VALUES (?, ?, ?, 'REGENERATION', 'MEDIA_UPLOAD', 2, 1, 'IOException', 'Disk Full', NOW(6), NOW(6))",
                failureId4.toString(), segmentId.toString(), voiceId.toString()
        )).hasMessageContaining("uq_novel_chapter_narration_audio_failures_segment_voice");

        // 6. Invalid foreign key to segment fails
        UUID failureId5 = UUID.randomUUID();
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO novel_chapter_narration_audio_failures (id, segment_id, managed_voice_id, operation, stage, attempted_synthesis_revision, failure_count, error_type, error_message, first_failed_at, last_failed_at) " +
                        "VALUES (?, ?, ?, 'INITIAL_GENERATION', 'TTS_SYNTHESIS', 1, 1, 'ConnectException', 'Timeout', NOW(6), NOW(6))",
                failureId5.toString(), UUID.randomUUID().toString(), voiceId.toString()
        )).hasMessageContaining("fk_novel_chapter_narration_audio_failures_segment");

        // 7. Invalid foreign key to managed voice fails
        UUID failureId6 = UUID.randomUUID();
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO novel_chapter_narration_audio_failures (id, segment_id, managed_voice_id, operation, stage, attempted_synthesis_revision, failure_count, error_type, error_message, first_failed_at, last_failed_at) " +
                        "VALUES (?, ?, ?, 'INITIAL_GENERATION', 'TTS_SYNTHESIS', 1, 1, 'ConnectException', 'Timeout', NOW(6), NOW(6))",
                failureId6.toString(), segmentId.toString(), UUID.randomUUID().toString()
        )).hasMessageContaining("fk_novel_chapter_narration_audio_failures_voice");
    }
}
