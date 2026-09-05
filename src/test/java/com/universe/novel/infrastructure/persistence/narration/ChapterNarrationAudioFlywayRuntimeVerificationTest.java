package com.universe.novel.infrastructure.persistence.narration;

import com.universe.test.TestDatabaseSupport;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.Arrays;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ChapterNarrationAudioFlywayRuntimeVerificationTest {

    private static DataSource createDataSource(String dbName) {
        return TestDatabaseSupport.createTestDataSource(dbName);
    }

    private static void resetDatabase(String dbName) {
        TestDatabaseSupport.resetTestDatabase(dbName);
    }

    @Test
    @DisplayName("V40 Migration: Clean database migration up to V40")
    void shouldMigrateCleanDatabaseUpToV40() {
        String dbName = "kiemlai_narration_audio_schema_test";
        resetDatabase(dbName);
        DataSource ds = createDataSource(dbName);

        Flyway flyway = Flyway.configure()
                .dataSource(ds)
                .locations("classpath:db/migration")
                .load();

        int applied = flyway.migrate().migrationsExecuted;
        assertThat(applied).isGreaterThanOrEqualTo(40);

        MigrationInfo current = flyway.info().current();
        assertThat(current).isNotNull();
        assertThat(Integer.parseInt(current.getVersion().getVersion())).isGreaterThanOrEqualTo(40);

        MigrationInfo v40 = Arrays.stream(flyway.info().applied())
                .filter(m -> "40".equals(m.getVersion().getVersion()))
                .findFirst()
                .orElse(null);

        assertThat(v40).isNotNull();
        assertThat(v40.getDescription()).isEqualTo("create novel chapter narration audio");
    }

    @Test
    @DisplayName("V40 Database invariants: Validate check, unique and foreign key constraints on real MySQL")
    void shouldEnforceDatabaseConstraintsOnRealMySql() {
        String dbName = "kiemlai_narration_audio_constraints_test";
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
        UUID mediaAssetId = UUID.randomUUID();

        // 1. Seed user, volume, chapter, segment, managed voice
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
        UUID audioId1 = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO novel_chapter_narration_audio (id, segment_id, managed_voice_id, media_asset_id, generated_synthesis_revision, created_at, updated_at) " +
                        "VALUES (?, ?, ?, ?, 1, NOW(6), NOW(6))",
                audioId1.toString(), segmentId.toString(), voiceId.toString(), mediaAssetId.toString()
        );

        // 3. Invalid revision (< 1) fails
        UUID audioId2 = UUID.randomUUID();
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO novel_chapter_narration_audio (id, segment_id, managed_voice_id, media_asset_id, generated_synthesis_revision, created_at, updated_at) " +
                        "VALUES (?, ?, ?, ?, 0, NOW(6), NOW(6))",
                audioId2.toString(), segmentId.toString(), voiceId.toString(), mediaAssetId.toString()
        )).hasMessageContaining("chk_novel_chapter_narration_audio_revision");

        // 4. Duplicate (segment_id, managed_voice_id) fails UNIQUE constraint
        UUID audioId3 = UUID.randomUUID();
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO novel_chapter_narration_audio (id, segment_id, managed_voice_id, media_asset_id, generated_synthesis_revision, created_at, updated_at) " +
                        "VALUES (?, ?, ?, ?, 2, NOW(6), NOW(6))",
                audioId3.toString(), segmentId.toString(), voiceId.toString(), UUID.randomUUID().toString()
        )).hasMessageContaining("uq_novel_chapter_narration_audio_segment_voice");

        // 5. Invalid foreign key to segment fails
        UUID audioId4 = UUID.randomUUID();
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO novel_chapter_narration_audio (id, segment_id, managed_voice_id, media_asset_id, generated_synthesis_revision, created_at, updated_at) " +
                        "VALUES (?, ?, ?, ?, 1, NOW(6), NOW(6))",
                audioId4.toString(), UUID.randomUUID().toString(), voiceId.toString(), mediaAssetId.toString()
        )).hasMessageContaining("fk_novel_chapter_narration_audio_segment");

        // 6. Invalid foreign key to managed voice fails
        UUID audioId5 = UUID.randomUUID();
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO novel_chapter_narration_audio (id, segment_id, managed_voice_id, media_asset_id, generated_synthesis_revision, created_at, updated_at) " +
                        "VALUES (?, ?, ?, ?, 1, NOW(6), NOW(6))",
                audioId5.toString(), segmentId.toString(), UUID.randomUUID().toString(), mediaAssetId.toString()
        )).hasMessageContaining("fk_novel_chapter_narration_audio_voice");
    }
}
