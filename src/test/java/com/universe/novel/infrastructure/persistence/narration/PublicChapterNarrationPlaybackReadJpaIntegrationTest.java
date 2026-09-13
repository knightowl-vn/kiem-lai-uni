package com.universe.novel.infrastructure.persistence.narration;

import com.universe.novel.application.ports.PlaybackManagedVoiceQueryPort;
import com.universe.novel.application.ports.PublicChapterNarrationPlaybackQueryPort;
import com.universe.novel.application.ports.PublicChapterNarrationPlaybackQueryPort.PublicChapterNarrationPlaybackSnapshot;
import com.universe.novel.infrastructure.persistence.voice.PlaybackManagedVoiceQueryPersistenceAdapter;
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
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest(properties = {
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.flyway.enabled=true"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({
        PlaybackManagedVoiceQueryPersistenceAdapter.class,
        PublicChapterNarrationPlaybackQueryPersistenceAdapter.class
})
@DisplayName("Public chapter narration playback read JPA integration")
class PublicChapterNarrationPlaybackReadJpaIntegrationTest {

    private static final UUID USER_ID = UUID.fromString("92600000-0000-0000-0000-000000000001");
    private static final UUID VOLUME_ID = UUID.fromString("92600000-0000-0000-0000-000000000002");
    private static final UUID CHAPTER_ID = UUID.fromString("92600000-0000-0000-0000-000000000003");
    private static final UUID FIRST_VOICE_ID = UUID.fromString("92600000-0000-0000-0000-000000000004");
    private static final UUID DEFAULT_VOICE_ID = UUID.fromString("92600000-0000-0000-0000-000000000005");
    private static final UUID DISABLED_VOICE_ID = UUID.fromString("92600000-0000-0000-0000-000000000006");
    private static final UUID PLAYBACK_ID = UUID.fromString("92600000-0000-0000-0000-000000000007");
    private static final UUID ARTIFACT_ID = UUID.fromString("92600000-0000-0000-0000-000000000008");
    private static final UUID MEDIA_ID = UUID.fromString("92600000-0000-0000-0000-000000000009");

    @DynamicPropertySource
    static void configureDataSource(DynamicPropertyRegistry registry) {
        TestDatabaseSupport.configureDynamicProperties(registry);
    }

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PlaybackManagedVoiceQueryPort voiceQueryPort;

    @Autowired
    private PublicChapterNarrationPlaybackQueryPort playbackQueryPort;

    @BeforeEach
    void seedData() {
        Timestamp now = Timestamp.from(Instant.parse("2026-09-10T00:00:00Z"));
        jdbcTemplate.update("UPDATE novel_managed_voices SET is_default = FALSE WHERE is_default = TRUE");
        jdbcTemplate.update(
                "INSERT INTO identity_users (id, email, password_hash, display_name, status, role, aggregate_version, persistence_version, created_at, updated_at) "
                        + "VALUES (?, 'perf-b2b1@universe.local', '$2a$10$hash', 'PERF B2B1', 'ACTIVE', 'ADMIN', 1, 0, ?, ?)",
                USER_ID.toString(), now, now
        );
        jdbcTemplate.update(
                "INSERT INTO novel_volumes (id, title, slug, description, sort_order, status, created_by, updated_by, published_by, archived_by, created_at, updated_at, published_at, archived_at, aggregate_version, persistence_version) "
                        + "VALUES (?, 'PERF B2B1 Volume', 'perf-b2b1-volume', 'Test', 9260001, 'PUBLISHED', ?, ?, ?, NULL, ?, ?, ?, NULL, 1, 0)",
                VOLUME_ID.toString(), USER_ID.toString(), USER_ID.toString(), USER_ID.toString(), now, now, now
        );
        jdbcTemplate.update(
                "INSERT INTO novel_chapters (id, volume_id, chapter_number, title, slug, summary, content, status, created_by, updated_by, published_by, archived_by, created_at, updated_at, published_at, archived_at, aggregate_version, persistence_version, content_version) "
                        + "VALUES (?, ?, 9260001, 'PERF B2B1 Chapter', 'perf-b2b1-chapter', 'Summary', 'Content', 'PUBLISHED', ?, ?, ?, NULL, ?, ?, ?, NULL, 1, 0, 17)",
                CHAPTER_ID.toString(), VOLUME_ID.toString(), USER_ID.toString(), USER_ID.toString(),
                USER_ID.toString(), now, now, now
        );
        seedVoice(FIRST_VOICE_ID, "perf-first", "ACTIVE", 1, false, 3L, now);
        seedVoice(DEFAULT_VOICE_ID, "perf-default", "ACTIVE", 99, true, 5L, now);
        seedVoice(DISABLED_VOICE_ID, "perf-disabled", "DISABLED", 0, false, 7L, now);
    }

    @Test
    void resolvesExplicitVoiceAndPrefersTheActiveDefaultInOneQueryEach() {
        assertThat(voiceQueryPort.findByVoiceKey("perf-disabled")).get()
                .satisfies(voice -> {
                    assertThat(voice.id()).isEqualTo(DISABLED_VOICE_ID);
                    assertThat(voice.isActive()).isFalse();
                    assertThat(voice.synthesisRevision()).isEqualTo(7L);
                });
        assertThat(voiceQueryPort.findPreferredActiveVoice()).get()
                .satisfies(voice -> assertThat(voice.id()).isEqualTo(DEFAULT_VOICE_ID));
    }

    @Test
    void returnsPublishedChapterWithNullLeftJoinFieldsWhenPlaybackIsMissing() {
        PublicChapterNarrationPlaybackSnapshot result = playbackQueryPort
                .findPublishedPlayback(CHAPTER_ID, DEFAULT_VOICE_ID)
                .orElseThrow();
        PublicChapterNarrationPlaybackSnapshot unresolvedVoiceResult = playbackQueryPort
                .findPublishedPlayback(CHAPTER_ID, null)
                .orElseThrow();

        assertThat(result.chapterId()).isEqualTo(CHAPTER_ID);
        assertThat(result.chapterContentVersion()).isEqualTo(17L);
        assertThat(result.playbackId()).isNull();
        assertThat(result.currentArtifactId()).isNull();
        assertThat(result.artifactId()).isNull();
        assertThat(unresolvedVoiceResult.chapterId()).isEqualTo(CHAPTER_ID);
        assertThat(unresolvedVoiceResult.playbackId()).isNull();
    }

    @Test
    void enforcesBothChapterAndVolumePublicationPredicates() {
        jdbcTemplate.update(
                "UPDATE novel_chapters SET status = 'DRAFT', published_by = NULL, published_at = NULL WHERE id = ?",
                CHAPTER_ID.toString()
        );
        assertThat(playbackQueryPort.findPublishedPlayback(CHAPTER_ID, DEFAULT_VOICE_ID)).isEmpty();

        Timestamp now = Timestamp.from(Instant.parse("2026-09-10T00:00:00Z"));
        jdbcTemplate.update(
                "UPDATE novel_chapters SET status = 'PUBLISHED', published_by = ?, published_at = ? WHERE id = ?",
                USER_ID.toString(), now, CHAPTER_ID.toString()
        );
        jdbcTemplate.update(
                "UPDATE novel_volumes SET status = 'DRAFT', published_by = NULL, published_at = NULL WHERE id = ?",
                VOLUME_ID.toString()
        );
        assertThat(playbackQueryPort.findPublishedPlayback(CHAPTER_ID, DEFAULT_VOICE_ID)).isEmpty();
    }

    @Test
    void preservesPlaybackWhenCurrentArtifactPointerIsNull() {
        insertPlayback(null);

        PublicChapterNarrationPlaybackSnapshot result = playbackQueryPort
                .findPublishedPlayback(CHAPTER_ID, DEFAULT_VOICE_ID)
                .orElseThrow();

        assertThat(result.playbackId()).isEqualTo(PLAYBACK_ID);
        assertThat(result.currentArtifactId()).isNull();
        assertThat(result.artifactId()).isNull();
    }

    @Test
    void faithfullyProjectsTheValidCurrentArtifactAndOwnershipFields() {
        Timestamp now = Timestamp.from(Instant.parse("2026-09-10T00:00:00Z"));
        insertPlayback(null);
        jdbcTemplate.update(
                "INSERT INTO novel_chapter_narration_playback_artifacts (id, playback_id, chapter_id, managed_voice_id, source_content_version, synthesis_revision, manifest_hash, media_asset_id, duration_millis, cue_count, codec_mime_type, created_at, source_fingerprint) "
                        + "VALUES (?, ?, ?, ?, 16, 4, ?, ?, 8200, 3, 'audio/mpeg', ?, ?)",
                ARTIFACT_ID.toString(), PLAYBACK_ID.toString(), CHAPTER_ID.toString(), DEFAULT_VOICE_ID.toString(),
                "a".repeat(64), MEDIA_ID.toString(), now, "b".repeat(64)
        );
        jdbcTemplate.update(
                "UPDATE novel_chapter_narration_playbacks SET current_artifact_id = ? WHERE id = ?",
                ARTIFACT_ID.toString(), PLAYBACK_ID.toString()
        );

        PublicChapterNarrationPlaybackSnapshot result = playbackQueryPort
                .findPublishedPlayback(CHAPTER_ID, DEFAULT_VOICE_ID)
                .orElseThrow();

        assertThat(result).isEqualTo(new PublicChapterNarrationPlaybackSnapshot(
                CHAPTER_ID, 17L, PLAYBACK_ID, ARTIFACT_ID, ARTIFACT_ID, PLAYBACK_ID,
                CHAPTER_ID, DEFAULT_VOICE_ID, 16L, 4L, MEDIA_ID, 8_200L, 3, "audio/mpeg"
        ));
    }

    private void seedVoice(
            UUID id,
            String voiceKey,
            String status,
            int displayOrder,
            boolean defaultVoice,
            long synthesisRevision,
            Timestamp now
    ) {
        jdbcTemplate.update(
                "INSERT INTO novel_managed_voices (id, voice_key, display_name, provider_voice_id, status, display_order, is_default, synthesis_revision, persistence_version, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, 0, ?, ?)",
                id.toString(), voiceKey, voiceKey, "provider-" + voiceKey, status, displayOrder,
                defaultVoice, synthesisRevision, now, now
        );
    }

    private void insertPlayback(UUID currentArtifactId) {
        Timestamp now = Timestamp.from(Instant.parse("2026-09-10T00:00:00Z"));
        jdbcTemplate.update(
                "INSERT INTO novel_chapter_narration_playbacks (id, chapter_id, managed_voice_id, current_artifact_id, version, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, 0, ?, ?)",
                PLAYBACK_ID.toString(), CHAPTER_ID.toString(), DEFAULT_VOICE_ID.toString(),
                currentArtifactId == null ? null : currentArtifactId.toString(), now, now
        );
    }
}
