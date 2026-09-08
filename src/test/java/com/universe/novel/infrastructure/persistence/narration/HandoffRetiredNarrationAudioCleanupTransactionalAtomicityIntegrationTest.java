package com.universe.novel.infrastructure.persistence.narration;

import com.universe.novel.application.narration.EnqueueNarrationMediaCleanupUseCase;
import com.universe.novel.application.narration.HandoffRetiredNarrationAudioCleanupOutcome;
import com.universe.novel.application.narration.HandoffRetiredNarrationAudioCleanupResult;
import com.universe.novel.application.narration.HandoffRetiredNarrationAudioCleanupUseCase;
import com.universe.novel.domain.narration.NarrationMediaCleanupReason;
import com.universe.novel.domain.narration.NarrationMediaCleanupTask;
import com.universe.novel.infrastructure.persistence.chapter.ChapterPersistenceAdapter;
import com.universe.novel.infrastructure.persistence.volume.VolumePersistenceAdapter;
import com.universe.shared.id.UuidGeneratorAdapter;
import com.universe.shared.time.ClockPort;
import com.universe.test.TestDatabaseSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@TestPropertySource(properties = {
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.flyway.enabled=true"
})
@Import({
        VolumePersistenceAdapter.class,
        ChapterPersistenceAdapter.class,
        ChapterNarrationSegmentPersistenceAdapter.class,
        ChapterNarrationAudioPersistenceAdapter.class,
        NarrationMediaCleanupTaskPersistenceAdapter.class,
        UuidGeneratorAdapter.class,
        EnqueueNarrationMediaCleanupUseCase.class,
        HandoffRetiredNarrationAudioCleanupUseCase.class,
        HandoffRetiredNarrationAudioCleanupTransactionalAtomicityIntegrationTest.TestConfig.class
})
@DisplayName("HandoffRetiredNarrationAudioCleanup Transactional Atomicity Integration Tests (MySQL)")
class HandoffRetiredNarrationAudioCleanupTransactionalAtomicityIntegrationTest {

    @DynamicPropertySource
    static void configureDataSource(DynamicPropertyRegistry registry) {
        TestDatabaseSupport.configureDynamicProperties(registry);
    }

    private static final UUID ADMIN_ID = UUID.fromString("88888888-1111-1111-1111-111111111111");
    private static final UUID VOLUME_ID = UUID.fromString("88888888-2222-2222-2222-222222222222");
    private static final UUID CHAPTER_ID = UUID.fromString("88888888-3333-3333-3333-333333333333");
    private static final UUID VOICE_ID = UUID.fromString("88888888-4444-4444-4444-444444444444");

    private static final UUID SEGMENT_RETIRED_ID = UUID.fromString("88888888-5555-5555-5555-555555555551");
    private static final UUID SEGMENT_CURRENT_ID = UUID.fromString("88888888-5555-5555-5555-555555555552");
    private static final UUID SEGMENT_OTHER_ID = UUID.fromString("88888888-5555-5555-5555-555555555553");

    private static final UUID AUDIO_RETIRED_ID = UUID.fromString("88888888-6666-6666-6666-666666666661");
    private static final UUID AUDIO_CURRENT_ID = UUID.fromString("88888888-6666-6666-6666-666666666662");
    private static final UUID AUDIO_OTHER_ID = UUID.fromString("88888888-6666-6666-6666-666666666663");

    private static final UUID MEDIA_ASSET_1_ID = UUID.fromString("88888888-7777-7777-7777-777777777771");
    private static final UUID MEDIA_ASSET_2_ID = UUID.fromString("88888888-7777-7777-7777-777777777772");

    private static final int VOLUME_SORT_ORDER = 5_000_001;
    private static final int CHAPTER_NUMBER = 5_000_001;

    @TestConfiguration
    static class TestConfig {
        @Bean
        public ClockPort clockPort() {
            return Instant::now;
        }
    }

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @SpyBean
    private NarrationMediaCleanupTaskPersistenceAdapter cleanupTaskPersistenceAdapter;

    @SpyBean
    private ChapterNarrationAudioPersistenceAdapter audioPersistenceAdapter;

    @Autowired
    private HandoffRetiredNarrationAudioCleanupUseCase handoffUseCase;

    @BeforeEach
    void setUp() {
        Mockito.reset(cleanupTaskPersistenceAdapter, audioPersistenceAdapter);
        cleanupDatabase();
        seedBaseData();
    }

    @AfterEach
    void tearDown() {
        Mockito.reset(cleanupTaskPersistenceAdapter, audioPersistenceAdapter);
        cleanupDatabase();
    }

    private void cleanupDatabase() {
        jdbcTemplate.update("DELETE FROM novel_narration_media_cleanup_tasks WHERE media_asset_id IN (?, ?)",
                MEDIA_ASSET_1_ID.toString(), MEDIA_ASSET_2_ID.toString());
        jdbcTemplate.update("DELETE FROM novel_chapter_narration_audio WHERE id IN (?, ?, ?)",
                AUDIO_RETIRED_ID.toString(), AUDIO_CURRENT_ID.toString(), AUDIO_OTHER_ID.toString());
        jdbcTemplate.update("DELETE FROM novel_chapter_narration_segments WHERE id IN (?, ?, ?)",
                SEGMENT_RETIRED_ID.toString(), SEGMENT_CURRENT_ID.toString(), SEGMENT_OTHER_ID.toString());
        jdbcTemplate.update("DELETE FROM novel_managed_voices WHERE id = ?", VOICE_ID.toString());
        jdbcTemplate.update("DELETE FROM novel_chapters WHERE id = ? OR chapter_number = ?", CHAPTER_ID.toString(), CHAPTER_NUMBER);
        jdbcTemplate.update("DELETE FROM novel_volumes WHERE id = ? OR sort_order = ?", VOLUME_ID.toString(), VOLUME_SORT_ORDER);
        jdbcTemplate.update("DELETE FROM identity_users WHERE id = ?", ADMIN_ID.toString());
    }

    private void seedBaseData() {
        Instant now = Instant.now();

        // 1. User
        jdbcTemplate.update(
                "INSERT INTO identity_users (id, email, password_hash, display_name, status, role, aggregate_version, persistence_version, created_at, updated_at) " +
                        "VALUES (?, 'handoff-admin@universe.local', '$2a$10$hash', 'Handoff Admin', 'ACTIVE', 'ADMIN', 1, 0, ?, ?)",
                ADMIN_ID.toString(), Timestamp.from(now), Timestamp.from(now)
        );

        // 2. Volume (PUBLISHED)
        jdbcTemplate.update(
                "INSERT INTO novel_volumes (id, title, slug, description, sort_order, status, created_by, updated_by, published_by, archived_by, created_at, updated_at, published_at, archived_at, aggregate_version, persistence_version) " +
                        "VALUES (?, 'Quyển Handoff Test', 'quyen-handoff-test', 'Mô tả quyển test', ?, 'PUBLISHED', ?, ?, ?, NULL, ?, ?, ?, NULL, 1, 0)",
                VOLUME_ID.toString(), VOLUME_SORT_ORDER, ADMIN_ID.toString(), ADMIN_ID.toString(), ADMIN_ID.toString(),
                Timestamp.from(now), Timestamp.from(now), Timestamp.from(now)
        );

        // 3. Chapter (PUBLISHED)
        jdbcTemplate.update(
                "INSERT INTO novel_chapters (id, volume_id, chapter_number, title, slug, summary, content, status, " +
                        "created_by, updated_by, published_by, archived_by, created_at, updated_at, published_at, archived_at, aggregate_version, persistence_version, content_version) " +
                        "VALUES (?, ?, ?, 'Chương 1: Handoff Test', 'chuong-1-handoff-test', 'Tóm tắt', 'Nội dung', 'PUBLISHED', " +
                        "?, ?, ?, NULL, ?, ?, ?, NULL, 1, 0, 1)",
                CHAPTER_ID.toString(), VOLUME_ID.toString(), CHAPTER_NUMBER,
                ADMIN_ID.toString(), ADMIN_ID.toString(), ADMIN_ID.toString(),
                Timestamp.from(now), Timestamp.from(now), Timestamp.from(now)
        );

        // 4. Managed Voice (ACTIVE)
        jdbcTemplate.update(
                "INSERT INTO novel_managed_voices (id, voice_key, display_name, provider_voice_id, status, display_order, is_default, synthesis_revision, persistence_version, created_at, updated_at) " +
                        "VALUES (?, 'voice-handoff', 'Giọng Handoff', 'provider-handoff', 'ACTIVE', 1, 1, 1, 0, ?, ?)",
                VOICE_ID.toString(), Timestamp.from(now), Timestamp.from(now)
        );
    }

    private void seedRetiredSegmentAndAudio(UUID segmentId, UUID audioId, UUID mediaAssetId) {
        Instant now = Instant.now();
        String text = "Đoạn văn bản đã retire";
        int charCount = text.length();
        String contentHash = com.universe.novel.domain.narration.NarrationTextSegment.computeSha256(text);
        // RETIRED segment
        jdbcTemplate.update(
                "INSERT INTO novel_chapter_narration_segments (id, chapter_id, segment_index, text, character_count, content_hash, status, created_at, updated_at) " +
                        "VALUES (?, ?, 0, ?, ?, ?, 'RETIRED', ?, ?)",
                segmentId.toString(), CHAPTER_ID.toString(), text, charCount, contentHash, Timestamp.from(now), Timestamp.from(now)
        );
        // ChapterNarrationAudio row
        jdbcTemplate.update(
                "INSERT INTO novel_chapter_narration_audio (id, segment_id, managed_voice_id, media_asset_id, generated_synthesis_revision, version, created_at, updated_at) " +
                        "VALUES (?, ?, ?, ?, 1, 0, ?, ?)",
                audioId.toString(), segmentId.toString(), VOICE_ID.toString(), mediaAssetId.toString(), Timestamp.from(now), Timestamp.from(now)
        );
    }

    private void seedCurrentSegmentAndAudio(UUID segmentId, UUID audioId, UUID mediaAssetId) {
        Instant now = Instant.now();
        String text = "Đoạn văn bản đang current";
        int charCount = text.length();
        String contentHash = com.universe.novel.domain.narration.NarrationTextSegment.computeSha256(text);
        // CURRENT segment
        jdbcTemplate.update(
                "INSERT INTO novel_chapter_narration_segments (id, chapter_id, segment_index, text, character_count, content_hash, status, created_at, updated_at) " +
                        "VALUES (?, ?, 0, ?, ?, ?, 'CURRENT', ?, ?)",
                segmentId.toString(), CHAPTER_ID.toString(), text, charCount, contentHash, Timestamp.from(now), Timestamp.from(now)
        );
        // ChapterNarrationAudio row
        jdbcTemplate.update(
                "INSERT INTO novel_chapter_narration_audio (id, segment_id, managed_voice_id, media_asset_id, generated_synthesis_revision, version, created_at, updated_at) " +
                        "VALUES (?, ?, ?, ?, 1, 0, ?, ?)",
                audioId.toString(), segmentId.toString(), VOICE_ID.toString(), mediaAssetId.toString(), Timestamp.from(now), Timestamp.from(now)
        );
    }

    @Test
    @DisplayName("CASE A: Successful handoff -> cleanup task committed + narration audio row removed atomically")
    void shouldAtomicallyCommitCleanupTaskAndRemoveAudioAssignment() {
        seedRetiredSegmentAndAudio(SEGMENT_RETIRED_ID, AUDIO_RETIRED_ID, MEDIA_ASSET_1_ID);

        HandoffRetiredNarrationAudioCleanupResult result = handoffUseCase.execute(AUDIO_RETIRED_ID);

        assertThat(result.outcome()).isEqualTo(HandoffRetiredNarrationAudioCleanupOutcome.HANDED_OFF);
        assertThat(result.narrationAudioId()).isEqualTo(AUDIO_RETIRED_ID);
        assertThat(result.segmentId()).isEqualTo(SEGMENT_RETIRED_ID);
        assertThat(result.mediaAssetId()).isEqualTo(MEDIA_ASSET_1_ID);

        // 1. Assert ChapterNarrationAudio row is deleted
        Integer audioCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM novel_chapter_narration_audio WHERE id = ?",
                Integer.class,
                AUDIO_RETIRED_ID.toString()
        );
        assertThat(audioCount).isEqualTo(0);

        // 2. Assert NarrationMediaCleanupTask row is created with OBSOLETE_RETIRED_SEGMENT_AUDIO
        Map<String, Object> taskRow = jdbcTemplate.queryForMap(
                "SELECT media_asset_id, reason, attempt_count, last_error_type FROM novel_narration_media_cleanup_tasks WHERE media_asset_id = ?",
                MEDIA_ASSET_1_ID.toString()
        );
        assertThat(taskRow.get("media_asset_id")).isEqualTo(MEDIA_ASSET_1_ID.toString());
        assertThat(taskRow.get("reason")).isEqualTo(NarrationMediaCleanupReason.OBSOLETE_RETIRED_SEGMENT_AUDIO.name());
        assertThat(((Number) taskRow.get("attempt_count")).intValue()).isEqualTo(0);
        assertThat(taskRow.get("last_error_type")).isNull();

        // 3. Assert segment row is preserved and remains RETIRED
        Map<String, Object> segmentRow = jdbcTemplate.queryForMap(
                "SELECT status FROM novel_chapter_narration_segments WHERE id = ?",
                SEGMENT_RETIRED_ID.toString()
        );
        assertThat(segmentRow.get("status")).isEqualTo("RETIRED");
    }

    @Test
    @DisplayName("CASE B: Cleanup task persistence failure -> entire transaction rolls back, audio assignment remains")
    void shouldRollbackWhenCleanupTaskPersistenceFails() {
        seedRetiredSegmentAndAudio(SEGMENT_RETIRED_ID, AUDIO_RETIRED_ID, MEDIA_ASSET_1_ID);

        Mockito.doThrow(new RuntimeException("Simulated failure in NarrationMediaCleanupTaskRepositoryPort.save"))
                .when(cleanupTaskPersistenceAdapter)
                .save(any(NarrationMediaCleanupTask.class));

        assertThatThrownBy(() -> handoffUseCase.execute(AUDIO_RETIRED_ID))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Simulated failure in NarrationMediaCleanupTaskRepositoryPort.save");

        // Fresh Database Assertions outside the failed transaction
        // 1. Audio assignment remains present
        Integer audioCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM novel_chapter_narration_audio WHERE id = ?",
                Integer.class,
                AUDIO_RETIRED_ID.toString()
        );
        assertThat(audioCount).isEqualTo(1);

        // 2. No cleanup task created
        Integer taskCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM novel_narration_media_cleanup_tasks WHERE media_asset_id = ?",
                Integer.class,
                MEDIA_ASSET_1_ID.toString()
        );
        assertThat(taskCount).isEqualTo(0);
    }

    @Test
    @DisplayName("CASE C: Force audio-assignment deletion failure AFTER cleanup task creation -> rolls back task creation and audio remains")
    void shouldRollbackEntireTransactionWhenAudioDeletionFails() {
        seedRetiredSegmentAndAudio(SEGMENT_RETIRED_ID, AUDIO_RETIRED_ID, MEDIA_ASSET_1_ID);

        Mockito.doThrow(new RuntimeException("Simulated failure in ChapterNarrationAudioRepositoryPort.deleteById"))
                .when(audioPersistenceAdapter)
                .deleteById(AUDIO_RETIRED_ID);

        assertThatThrownBy(() -> handoffUseCase.execute(AUDIO_RETIRED_ID))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Simulated failure in ChapterNarrationAudioRepositoryPort.deleteById");

        // Fresh Database Assertions: Atomicity Invariant
        // 1. Cleanup task must NOT commit
        Integer taskCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM novel_narration_media_cleanup_tasks WHERE media_asset_id = ?",
                Integer.class,
                MEDIA_ASSET_1_ID.toString()
        );
        assertThat(taskCount).isEqualTo(0);

        // 2. Audio assignment remains present
        Integer audioCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM novel_chapter_narration_audio WHERE id = ?",
                Integer.class,
                AUDIO_RETIRED_ID.toString()
        );
        assertThat(audioCount).isEqualTo(1);
    }

    @Test
    @DisplayName("CASE D: Locked segment is CURRENT -> returns SKIPPED_NOT_RETIRED, no cleanup task, audio row remains")
    void shouldSkipAndPreserveAudioWhenSegmentIsCurrent() {
        seedCurrentSegmentAndAudio(SEGMENT_CURRENT_ID, AUDIO_CURRENT_ID, MEDIA_ASSET_1_ID);

        HandoffRetiredNarrationAudioCleanupResult result = handoffUseCase.execute(AUDIO_CURRENT_ID);

        assertThat(result.outcome()).isEqualTo(HandoffRetiredNarrationAudioCleanupOutcome.SKIPPED_NOT_RETIRED);
        assertThat(result.isSkipped()).isTrue();

        // 1. Audio assignment remains present
        Integer audioCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM novel_chapter_narration_audio WHERE id = ?",
                Integer.class,
                AUDIO_CURRENT_ID.toString()
        );
        assertThat(audioCount).isEqualTo(1);

        // 2. No cleanup task created
        Integer taskCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM novel_narration_media_cleanup_tasks WHERE media_asset_id = ?",
                Integer.class,
                MEDIA_ASSET_1_ID.toString()
        );
        assertThat(taskCount).isEqualTo(0);
    }

    @Test
    @DisplayName("CASE E: Shared Media reference -> returns SKIPPED_SHARED_MEDIA_REFERENCE, no cleanup task, audio rows remain")
    void shouldSkipAndPreserveAudioWhenMediaIsShared() {
        // Two audio assignments share the same Media asset (MEDIA_ASSET_1_ID)
        seedRetiredSegmentAndAudio(SEGMENT_RETIRED_ID, AUDIO_RETIRED_ID, MEDIA_ASSET_1_ID);
        seedCurrentSegmentAndAudio(SEGMENT_OTHER_ID, AUDIO_OTHER_ID, MEDIA_ASSET_1_ID);

        HandoffRetiredNarrationAudioCleanupResult result = handoffUseCase.execute(AUDIO_RETIRED_ID);

        assertThat(result.outcome()).isEqualTo(HandoffRetiredNarrationAudioCleanupOutcome.SKIPPED_SHARED_MEDIA_REFERENCE);
        assertThat(result.isSkipped()).isTrue();

        // Both audio assignments remain present
        Integer audioCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM novel_chapter_narration_audio WHERE id IN (?, ?)",
                Integer.class,
                AUDIO_RETIRED_ID.toString(), AUDIO_OTHER_ID.toString()
        );
        assertThat(audioCount).isEqualTo(2);

        // No cleanup task created
        Integer taskCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM novel_narration_media_cleanup_tasks WHERE media_asset_id = ?",
                Integer.class,
                MEDIA_ASSET_1_ID.toString()
        );
        assertThat(taskCount).isEqualTo(0);
    }
}
