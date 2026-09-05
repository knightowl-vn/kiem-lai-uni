package com.universe.novel.infrastructure.persistence.narration;

import com.universe.novel.application.ports.ChapterNarrationAudioFailureRepositoryPort;
import com.universe.novel.domain.narration.ChapterNarrationAudioFailure;
import com.universe.novel.domain.narration.NarrationAudioFailureStage;
import com.universe.novel.domain.narration.NarrationAudioOperation;
import com.universe.test.TestDatabaseSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest(properties = {
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.flyway.enabled=true"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(ChapterNarrationAudioFailurePersistenceAdapter.class)
@DisplayName("ChapterNarrationAudioFailure JPA Persistence Integration Tests (MySQL)")
class ChapterNarrationAudioFailureJpaPersistenceIntegrationTest {

    @DynamicPropertySource
    static void configureDataSource(DynamicPropertyRegistry registry) {
        TestDatabaseSupport.configureDynamicProperties(registry);
    }

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private jakarta.persistence.EntityManager entityManager;

    @Autowired
    private ChapterNarrationAudioFailureRepositoryPort repositoryPort;

    private static final UUID USER_ID = UUID.fromString("60000000-0000-0000-0000-000000000001");
    private static final UUID VOLUME_ID = UUID.fromString("60000000-0000-0000-0000-000000000010");
    private static final UUID CHAPTER_ID = UUID.fromString("60000000-0000-0000-0000-000000000101");
    private static final UUID SEGMENT_ID = UUID.fromString("60000000-0000-0000-0000-000000000201");
    private static final UUID VOICE_ID = UUID.fromString("60000000-0000-0000-0000-000000000301");

    @BeforeEach
    void cleanAndSeedData() {
        jdbcTemplate.update("DELETE FROM novel_chapter_narration_audio_failures WHERE segment_id = ?",
                SEGMENT_ID.toString());
        jdbcTemplate.update("DELETE FROM novel_chapter_narration_audio WHERE segment_id = ?",
                SEGMENT_ID.toString());
        jdbcTemplate.update("DELETE FROM novel_chapter_narration_segments WHERE chapter_id = ?",
                CHAPTER_ID.toString());
        jdbcTemplate.update("DELETE FROM novel_managed_voices WHERE id = ?",
                VOICE_ID.toString());
        jdbcTemplate.update("DELETE FROM novel_chapters WHERE id = ?",
                CHAPTER_ID.toString());
        jdbcTemplate.update("DELETE FROM novel_volumes WHERE id = ?",
                VOLUME_ID.toString());
        jdbcTemplate.update("DELETE FROM identity_users WHERE id = ?",
                USER_ID.toString());

        Instant now = Instant.now();

        // 1. User
        jdbcTemplate.update(
                "INSERT INTO identity_users (id, email, password_hash, display_name, status, role, aggregate_version, persistence_version, created_at, updated_at) " +
                        "VALUES (?, 'failure-int@universe.local', '$2a$10$hash', 'Failure Admin', 'ACTIVE', 'ADMIN', 1, 0, ?, ?)",
                USER_ID.toString(), Timestamp.from(now), Timestamp.from(now)
        );

        // 2. Volume
        jdbcTemplate.update(
                "INSERT INTO novel_volumes (id, title, slug, description, sort_order, status, created_by, updated_by, published_by, archived_by, created_at, updated_at, published_at, archived_at, aggregate_version, persistence_version) " +
                        "VALUES (?, 'Quyển Failure Test', 'quyen-failure-test', 'Mô tả', 9970, 'PUBLISHED', ?, ?, ?, NULL, ?, ?, ?, NULL, 1, 0)",
                VOLUME_ID.toString(), USER_ID.toString(), USER_ID.toString(), USER_ID.toString(),
                Timestamp.from(now), Timestamp.from(now), Timestamp.from(now)
        );

        // 3. Chapter
        jdbcTemplate.update(
                "INSERT INTO novel_chapters (id, volume_id, chapter_number, title, slug, summary, content, status, " +
                        "created_by, updated_by, published_by, archived_by, created_at, updated_at, published_at, archived_at, aggregate_version, persistence_version, content_version) " +
                        "VALUES (?, ?, 9971, 'Chương Failure 1', 'chuong-9971-failure', 'Tóm tắt', 'Nội dung', 'PUBLISHED', " +
                        "?, ?, ?, NULL, ?, ?, ?, NULL, 1, 0, 1)",
                CHAPTER_ID.toString(), VOLUME_ID.toString(),
                USER_ID.toString(), USER_ID.toString(), USER_ID.toString(),
                Timestamp.from(now), Timestamp.from(now), Timestamp.from(now)
        );

        // 4. Segment
        String text1 = "Đoạn văn kiểm tra lỗi.";
        String hash1 = "f".repeat(64);
        jdbcTemplate.update(
                "INSERT INTO novel_chapter_narration_segments (id, chapter_id, segment_index, text, character_count, content_hash, status, created_at, updated_at) " +
                        "VALUES (?, ?, 0, ?, ?, ?, 'CURRENT', ?, ?)",
                SEGMENT_ID.toString(), CHAPTER_ID.toString(), text1, text1.length(), hash1,
                Timestamp.from(now), Timestamp.from(now)
        );

        // 5. Managed Voice
        jdbcTemplate.update(
                "INSERT INTO novel_managed_voices (id, voice_key, display_name, provider_voice_id, status, display_order, is_default, synthesis_revision, persistence_version, created_at, updated_at) " +
                        "VALUES (?, 'voice-failure-01', 'Giọng Failure 1', 'provider-fail-01', 'ACTIVE', 1, TRUE, 1, 0, ?, ?)",
                VOICE_ID.toString(), Timestamp.from(now), Timestamp.from(now)
        );
    }

    @Test
    @DisplayName("Should save, retrieve, and delete failure record")
    void shouldSaveRetrieveAndDeleteFailureRecord() {
        UUID failureId = UUID.randomUUID();
        Instant now = Instant.now();

        ChapterNarrationAudioFailure failure = ChapterNarrationAudioFailure.create(
                failureId,
                SEGMENT_ID,
                VOICE_ID,
                NarrationAudioOperation.INITIAL_GENERATION,
                NarrationAudioFailureStage.TTS_SYNTHESIS,
                1L,
                "ConnectException",
                now
        );

        repositoryPort.save(failure);

        Optional<ChapterNarrationAudioFailure> loaded = repositoryPort.findBySegmentIdAndManagedVoiceId(SEGMENT_ID, VOICE_ID);
        assertThat(loaded).isPresent();
        ChapterNarrationAudioFailure f = loaded.get();
        assertThat(f.getId()).isEqualTo(failureId);
        assertThat(f.getSegmentId()).isEqualTo(SEGMENT_ID);
        assertThat(f.getManagedVoiceId()).isEqualTo(VOICE_ID);
        assertThat(f.getOperation()).isEqualTo(NarrationAudioOperation.INITIAL_GENERATION);
        assertThat(f.getStage()).isEqualTo(NarrationAudioFailureStage.TTS_SYNTHESIS);
        assertThat(f.getAttemptedSynthesisRevision()).isEqualTo(1L);
        assertThat(f.getFailureCount()).isEqualTo(1);
        assertThat(f.getErrorType()).isEqualTo("ConnectException");
        assertThat(f.getErrorMessage()).isEqualTo("Narration TTS synthesis failed.");

        // Delete failure record
        repositoryPort.deleteBySegmentIdAndManagedVoiceId(SEGMENT_ID, VOICE_ID);
        entityManager.clear();

        assertThat(repositoryPort.findBySegmentIdAndManagedVoiceId(SEGMENT_ID, VOICE_ID)).isEmpty();
    }

    @Test
    @DisplayName("Should cascade delete failure record when parent segment is deleted")
    void shouldCascadeDeleteFailureWhenSegmentDeleted() {
        UUID failureId = UUID.randomUUID();
        Instant now = Instant.now();

        ChapterNarrationAudioFailure failure = ChapterNarrationAudioFailure.create(
                failureId,
                SEGMENT_ID,
                VOICE_ID,
                NarrationAudioOperation.REGENERATION,
                NarrationAudioFailureStage.MEDIA_UPLOAD,
                2L,
                "IOException",
                now
        );
        repositoryPort.save(failure);

        assertThat(repositoryPort.findBySegmentIdAndManagedVoiceId(SEGMENT_ID, VOICE_ID)).isPresent();

        // Delete segment via SQL
        jdbcTemplate.update("DELETE FROM novel_chapter_narration_segments WHERE id = ?", SEGMENT_ID.toString());
        entityManager.clear();

        // Verify failure was removed by ON DELETE CASCADE
        assertThat(repositoryPort.findBySegmentIdAndManagedVoiceId(SEGMENT_ID, VOICE_ID)).isEmpty();
    }
}
