package com.universe.novel.infrastructure.persistence.narration;

import com.universe.novel.application.ports.ChapterNarrationAudioRepositoryPort;
import com.universe.novel.domain.narration.ChapterNarrationAudio;
import com.universe.test.TestDatabaseSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest(properties = {
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.flyway.enabled=true"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(ChapterNarrationAudioPersistenceAdapter.class)
@DisplayName("ChapterNarrationAudio JPA Persistence Integration Tests (MySQL)")
class ChapterNarrationAudioJpaPersistenceIntegrationTest {

    @DynamicPropertySource
    static void configureDataSource(DynamicPropertyRegistry registry) {
        TestDatabaseSupport.configureDynamicProperties(registry);
    }

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private jakarta.persistence.EntityManager entityManager;

    @Autowired
    private ChapterNarrationAudioRepositoryPort repositoryPort;

    private static final UUID USER_ID = UUID.fromString("60000000-0000-0000-0000-000000000001");
    private static final UUID VOLUME_ID = UUID.fromString("60000000-0000-0000-0000-000000000010");
    private static final UUID CHAPTER_ID = UUID.fromString("60000000-0000-0000-0000-000000000101");
    private static final UUID SEGMENT_1_ID = UUID.fromString("60000000-0000-0000-0000-000000000201");
    private static final UUID SEGMENT_2_ID = UUID.fromString("60000000-0000-0000-0000-000000000202");
    private static final UUID VOICE_1_ID = UUID.fromString("60000000-0000-0000-0000-000000000301");
    private static final UUID VOICE_2_ID = UUID.fromString("60000000-0000-0000-0000-000000000302");
    private static final UUID MEDIA_ASSET_ID = UUID.fromString("60000000-0000-0000-0000-000000000401");

    @BeforeEach
    void cleanAndSeedData() {
        jdbcTemplate.update("DELETE FROM novel_chapter_narration_audio WHERE segment_id IN (?, ?)",
                SEGMENT_1_ID.toString(), SEGMENT_2_ID.toString());
        jdbcTemplate.update("DELETE FROM novel_chapter_narration_segments WHERE chapter_id = ?",
                CHAPTER_ID.toString());
        jdbcTemplate.update("DELETE FROM novel_managed_voices WHERE id IN (?, ?)",
                VOICE_1_ID.toString(), VOICE_2_ID.toString());
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
                        "VALUES (?, 'audio-admin@universe.local', '$2a$10$hash', 'Audio Admin', 'ACTIVE', 'ADMIN', 1, 0, ?, ?)",
                USER_ID.toString(), Timestamp.from(now), Timestamp.from(now)
        );

        // 2. Volume
        jdbcTemplate.update(
                "INSERT INTO novel_volumes (id, title, slug, description, sort_order, status, created_by, updated_by, published_by, archived_by, created_at, updated_at, published_at, archived_at, aggregate_version, persistence_version) " +
                        "VALUES (?, 'Quyển Audio Test', 'quyen-audio-test', 'Mô tả', 9960, 'PUBLISHED', ?, ?, ?, NULL, ?, ?, ?, NULL, 1, 0)",
                VOLUME_ID.toString(), USER_ID.toString(), USER_ID.toString(), USER_ID.toString(),
                Timestamp.from(now), Timestamp.from(now), Timestamp.from(now)
        );

        // 3. Chapter
        jdbcTemplate.update(
                "INSERT INTO novel_chapters (id, volume_id, chapter_number, title, slug, summary, content, status, " +
                        "created_by, updated_by, published_by, archived_by, created_at, updated_at, published_at, archived_at, aggregate_version, persistence_version, content_version) " +
                        "VALUES (?, ?, 9961, 'Chương Audio 1', 'chuong-9961-audio', 'Tóm tắt', 'Nội dung', 'PUBLISHED', " +
                        "?, ?, ?, NULL, ?, ?, ?, NULL, 1, 0, 1)",
                CHAPTER_ID.toString(), VOLUME_ID.toString(),
                USER_ID.toString(), USER_ID.toString(), USER_ID.toString(),
                Timestamp.from(now), Timestamp.from(now), Timestamp.from(now)
        );

        // 4. Segments
        String text1 = "Đoạn văn thứ nhất.";
        String hash1 = "a".repeat(64);
        jdbcTemplate.update(
                "INSERT INTO novel_chapter_narration_segments (id, chapter_id, segment_index, text, character_count, content_hash, status, created_at, updated_at) " +
                        "VALUES (?, ?, 0, ?, ?, ?, 'CURRENT', ?, ?)",
                SEGMENT_1_ID.toString(), CHAPTER_ID.toString(), text1, text1.length(), hash1,
                Timestamp.from(now), Timestamp.from(now)
        );

        String text2 = "Đoạn văn thứ hai.";
        String hash2 = "b".repeat(64);
        jdbcTemplate.update(
                "INSERT INTO novel_chapter_narration_segments (id, chapter_id, segment_index, text, character_count, content_hash, status, created_at, updated_at) " +
                        "VALUES (?, ?, 1, ?, ?, ?, 'CURRENT', ?, ?)",
                SEGMENT_2_ID.toString(), CHAPTER_ID.toString(), text2, text2.length(), hash2,
                Timestamp.from(now), Timestamp.from(now)
        );

        // 5. Managed Voices
        jdbcTemplate.update(
                "INSERT INTO novel_managed_voices (id, voice_key, display_name, provider_voice_id, status, display_order, is_default, synthesis_revision, persistence_version, created_at, updated_at) " +
                        "VALUES (?, 'voice-test-01', 'Giọng Thử Nghiệm 1', 'provider-voice-01', 'ACTIVE', 1, TRUE, 1, 0, ?, ?)",
                VOICE_1_ID.toString(), Timestamp.from(now), Timestamp.from(now)
        );

        jdbcTemplate.update(
                "INSERT INTO novel_managed_voices (id, voice_key, display_name, provider_voice_id, status, display_order, is_default, synthesis_revision, persistence_version, created_at, updated_at) " +
                        "VALUES (?, 'voice-test-02', 'Giọng Thử Nghiệm 2', 'provider-voice-02', 'ACTIVE', 2, FALSE, 2, 0, ?, ?)",
                VOICE_2_ID.toString(), Timestamp.from(now), Timestamp.from(now)
        );
    }

    @Test
    @DisplayName("Should save and retrieve audio assignment by segment and managed voice")
    void shouldSaveAndRetrieveAudioAssignment() {
        UUID audioId = UUID.randomUUID();
        Instant now = Instant.now();

        ChapterNarrationAudio audio = ChapterNarrationAudio.create(
                audioId,
                SEGMENT_1_ID,
                VOICE_1_ID,
                MEDIA_ASSET_ID,
                1L,
                now
        );

        repositoryPort.save(audio);

        Optional<ChapterNarrationAudio> loaded = repositoryPort.findBySegmentIdAndManagedVoiceId(SEGMENT_1_ID, VOICE_1_ID);
        assertThat(loaded).isPresent();
        ChapterNarrationAudio a = loaded.get();
        assertThat(a.getId()).isEqualTo(audioId);
        assertThat(a.getSegmentId()).isEqualTo(SEGMENT_1_ID);
        assertThat(a.getManagedVoiceId()).isEqualTo(VOICE_1_ID);
        assertThat(a.getMediaAssetId()).isEqualTo(MEDIA_ASSET_ID);
        assertThat(a.getGeneratedSynthesisRevision()).isEqualTo(1L);
        assertThat(a.isCompatibleWith(1L)).isTrue();
        assertThat(a.isCompatibleWith(2L)).isFalse();
    }

    @Test
    @DisplayName("Should enforce uniqueness on (segment_id, managed_voice_id)")
    void shouldEnforceUniqueSegmentVoiceConstraint() {
        Instant now = Instant.now();
        UUID audioId1 = UUID.randomUUID();
        UUID audioId2 = UUID.randomUUID();

        ChapterNarrationAudio audio1 = ChapterNarrationAudio.create(
                audioId1, SEGMENT_1_ID, VOICE_1_ID, MEDIA_ASSET_ID, 1L, now
        );
        repositoryPort.save(audio1);

        ChapterNarrationAudio audio2 = ChapterNarrationAudio.create(
                audioId2, SEGMENT_1_ID, VOICE_1_ID, UUID.randomUUID(), 1L, now
        );

        assertThatThrownBy(() -> repositoryPort.save(audio2))
                .isInstanceOf(com.universe.novel.application.exceptions.ChapterNarrationAudioAlreadyExistsException.class);
    }

    @Test
    @DisplayName("Should cascade delete narration audio when parent segment is deleted")
    void shouldCascadeDeleteAudioWhenSegmentDeleted() {
        Instant now = Instant.now();
        UUID audioId = UUID.randomUUID();

        ChapterNarrationAudio audio = ChapterNarrationAudio.create(
                audioId, SEGMENT_1_ID, VOICE_1_ID, MEDIA_ASSET_ID, 1L, now
        );
        repositoryPort.save(audio);

        assertThat(repositoryPort.findById(audioId)).isPresent();

        // Delete parent segment via SQL
        jdbcTemplate.update("DELETE FROM novel_chapter_narration_segments WHERE id = ?", SEGMENT_1_ID.toString());
        entityManager.clear();

        // Verify audio assignment is removed via MySQL ON DELETE CASCADE
        assertThat(repositoryPort.findById(audioId)).isEmpty();
        assertThat(repositoryPort.findBySegmentId(SEGMENT_1_ID)).isEmpty();
    }
}
