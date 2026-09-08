package com.universe.novel.infrastructure.persistence.narration;

import com.universe.novel.application.ports.ChapterNarrationPlaybackArtifactRepositoryPort;
import com.universe.novel.application.ports.ChapterNarrationPlaybackCueRepositoryPort;
import com.universe.novel.application.ports.ChapterNarrationPlaybackRepositoryPort;
import com.universe.novel.domain.narration.ChapterNarrationPlayback;
import com.universe.novel.domain.narration.ChapterNarrationPlaybackArtifact;
import com.universe.novel.domain.narration.ChapterNarrationPlaybackCue;
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
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest(properties = {
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.flyway.enabled=true"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({
        ChapterNarrationPlaybackPersistenceAdapter.class,
        ChapterNarrationPlaybackArtifactPersistenceAdapter.class,
        ChapterNarrationPlaybackCuePersistenceAdapter.class
})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@DisplayName("ChapterNarrationPlayback JPA Persistence Integration Tests (MySQL)")
class ChapterNarrationPlaybackJpaPersistenceIntegrationTest {

    @DynamicPropertySource
    static void configureDataSource(DynamicPropertyRegistry registry) {
        TestDatabaseSupport.configureDynamicProperties(registry);
    }

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private jakarta.persistence.EntityManager entityManager;

    @Autowired
    private ChapterNarrationPlaybackRepositoryPort playbackRepository;

    @Autowired
    private ChapterNarrationPlaybackArtifactRepositoryPort artifactRepository;

    @Autowired
    private ChapterNarrationPlaybackCueRepositoryPort cueRepository;

    private static final UUID USER_ID = UUID.fromString("70000000-0000-0000-0000-000000000001");
    private static final UUID VOLUME_ID = UUID.fromString("70000000-0000-0000-0000-000000000010");
    private static final UUID CHAPTER_ID = UUID.fromString("70000000-0000-0000-0000-000000000101");
    private static final UUID SEGMENT_1_ID = UUID.fromString("70000000-0000-0000-0000-000000000201");
    private static final UUID SEGMENT_2_ID = UUID.fromString("70000000-0000-0000-0000-000000000202");
    private static final UUID VOICE_ID = UUID.fromString("70000000-0000-0000-0000-000000000301");
    private static final UUID MEDIA_ASSET_ID = UUID.fromString("70000000-0000-0000-0000-000000000401");
    private static final String MANIFEST_HASH = "b".repeat(64);

    @BeforeEach
    void cleanAndSeedData() {
        jdbcTemplate.update(
                "UPDATE novel_chapter_narration_playbacks SET current_artifact_id = NULL WHERE chapter_id = ?",
                CHAPTER_ID.toString()
        );
        jdbcTemplate.update(
                "DELETE FROM novel_chapter_narration_playback_cues WHERE artifact_id IN " +
                        "(SELECT id FROM novel_chapter_narration_playback_artifacts WHERE chapter_id = ?)",
                CHAPTER_ID.toString()
        );
        jdbcTemplate.update(
                "DELETE FROM novel_chapter_narration_playback_artifacts WHERE chapter_id = ?",
                CHAPTER_ID.toString()
        );
        jdbcTemplate.update(
                "DELETE FROM novel_chapter_narration_playbacks WHERE chapter_id = ?",
                CHAPTER_ID.toString()
        );
        jdbcTemplate.update(
                "DELETE FROM novel_chapter_narration_segments WHERE chapter_id = ?",
                CHAPTER_ID.toString()
        );
        jdbcTemplate.update("DELETE FROM novel_managed_voices WHERE id = ?", VOICE_ID.toString());
        jdbcTemplate.update("DELETE FROM novel_chapters WHERE id = ?", CHAPTER_ID.toString());
        jdbcTemplate.update("DELETE FROM novel_volumes WHERE id = ?", VOLUME_ID.toString());
        jdbcTemplate.update("DELETE FROM identity_users WHERE id = ?", USER_ID.toString());

        Instant now = Instant.parse("2026-09-08T03:00:00Z");
        Timestamp timestamp = Timestamp.from(now);

        jdbcTemplate.update(
                "INSERT INTO identity_users (id, email, password_hash, display_name, status, role, aggregate_version, persistence_version, created_at, updated_at) " +
                        "VALUES (?, 'playback-admin@universe.local', '$2a$10$hash', 'Playback Admin', 'ACTIVE', 'ADMIN', 1, 0, ?, ?)",
                USER_ID.toString(), timestamp, timestamp
        );
        jdbcTemplate.update(
                "INSERT INTO novel_volumes (id, title, slug, description, sort_order, status, created_by, updated_by, published_by, archived_by, created_at, updated_at, published_at, archived_at, aggregate_version, persistence_version) " +
                        "VALUES (?, 'Playback Volume', 'playback-volume', 'Description', 9970, 'PUBLISHED', ?, ?, ?, NULL, ?, ?, ?, NULL, 1, 0)",
                VOLUME_ID.toString(), USER_ID.toString(), USER_ID.toString(), USER_ID.toString(),
                timestamp, timestamp, timestamp
        );
        jdbcTemplate.update(
                "INSERT INTO novel_chapters (id, volume_id, chapter_number, title, slug, summary, content, status, " +
                        "created_by, updated_by, published_by, archived_by, created_at, updated_at, published_at, archived_at, aggregate_version, persistence_version, content_version) " +
                        "VALUES (?, ?, 9971, 'Playback Chapter', 'playback-chapter', 'Summary', 'Content', 'PUBLISHED', " +
                        "?, ?, ?, NULL, ?, ?, ?, NULL, 1, 0, 1)",
                CHAPTER_ID.toString(), VOLUME_ID.toString(),
                USER_ID.toString(), USER_ID.toString(), USER_ID.toString(),
                timestamp, timestamp, timestamp
        );
        jdbcTemplate.update(
                "INSERT INTO novel_managed_voices (id, voice_key, display_name, provider_voice_id, status, display_order, is_default, synthesis_revision, persistence_version, created_at, updated_at) " +
                        "VALUES (?, 'playback-voice-01', 'Playback Voice', 'provider-playback-01', 'ACTIVE', 1, TRUE, 1, 0, ?, ?)",
                VOICE_ID.toString(), timestamp, timestamp
        );
        seedSegment(SEGMENT_1_ID, 0, "First segment.", "c".repeat(64), timestamp);
        seedSegment(SEGMENT_2_ID, 1, "Second segment.", "d".repeat(64), timestamp);
    }

    @Test
    @DisplayName("Should save and load stable playback with null current artifact")
    void shouldSaveAndLoadStablePlaybackWithNullCurrentArtifact() {
        UUID playbackId = UUID.randomUUID();
        Instant now = Instant.now();

        ChapterNarrationPlayback saved = playbackRepository.save(
                ChapterNarrationPlayback.create(playbackId, CHAPTER_ID, VOICE_ID, now)
        );

        assertThat(saved.getVersion()).isEqualTo(0L);
        assertThat(saved.getCurrentArtifactId()).isNull();

        Optional<ChapterNarrationPlayback> loaded =
                playbackRepository.findByChapterIdAndManagedVoiceId(CHAPTER_ID, VOICE_ID);
        assertThat(loaded).isPresent();
        assertThat(loaded.get().getId()).isEqualTo(playbackId);
        assertThat(loaded.get().getCurrentArtifactId()).isNull();
    }

    @Test
    @DisplayName("Should save artifact, load ordered cues, and switch current artifact")
    void shouldSaveArtifactCuesAndSwitchCurrentArtifact() {
        Instant now = Instant.now();
        UUID playbackId = UUID.randomUUID();
        UUID artifactId = UUID.randomUUID();

        ChapterNarrationPlayback playback = playbackRepository.save(
                ChapterNarrationPlayback.create(playbackId, CHAPTER_ID, VOICE_ID, now)
        );
        ChapterNarrationPlaybackArtifact artifact = artifactRepository.insert(
                artifact(artifactId, playback, now)
        );
        cueRepository.insertAll(List.of(
                ChapterNarrationPlaybackCue.create(artifactId, 1, SEGMENT_2_ID, 1, 1_100L, 2_000L),
                ChapterNarrationPlaybackCue.create(artifactId, 0, SEGMENT_1_ID, 0, 0L, 1_000L)
        ));

        playback.switchCurrentArtifact(artifact, now.plusSeconds(10));
        ChapterNarrationPlayback switched = playbackRepository.save(playback);

        assertThat(switched.getCurrentArtifactId()).isEqualTo(artifactId);
        assertThat(switched.getVersion()).isEqualTo(1L);
        assertThat(artifactRepository.findById(artifactId)).contains(artifact);
        assertThat(artifactRepository.findByPlaybackId(playbackId)).extracting(ChapterNarrationPlaybackArtifact::getId)
                .containsExactly(artifactId);
        assertThat(cueRepository.findByArtifactId(artifactId))
                .extracting(ChapterNarrationPlaybackCue::getCueOrdinal)
                .containsExactly(0, 1);
    }

    @Test
    @DisplayName("Should insert artifact once and reject duplicate ID without overwriting metadata")
    void shouldInsertArtifactOnceAndRejectDuplicateWithoutOverwriting() {
        Instant now = Instant.now();
        UUID playbackId = UUID.randomUUID();
        UUID artifactId = UUID.randomUUID();

        ChapterNarrationPlayback playback = playbackRepository.save(
                ChapterNarrationPlayback.create(playbackId, CHAPTER_ID, VOICE_ID, now)
        );
        ChapterNarrationPlaybackArtifact original = artifactRepository.insert(
                artifact(artifactId, playback, now)
        );
        ChapterNarrationPlaybackArtifact duplicateWithDifferentMetadata = ChapterNarrationPlaybackArtifact.create(
                artifactId,
                playback,
                2L,
                3L,
                "e".repeat(64),
                UUID.fromString("70000000-0000-0000-0000-000000000499"),
                9_999L,
                7,
                "audio/ogg",
                now.plusSeconds(30)
        );

        assertThatThrownBy(() -> artifactRepository.insert(duplicateWithDifferentMetadata))
                .isInstanceOf(RuntimeException.class);

        ChapterNarrationPlaybackArtifact reloaded = artifactRepository.findById(artifactId).orElseThrow();
        assertThat(reloaded.getSourceContentVersion()).isEqualTo(original.getSourceContentVersion());
        assertThat(reloaded.getSynthesisRevision()).isEqualTo(original.getSynthesisRevision());
        assertThat(reloaded.getManifestHash()).isEqualTo(original.getManifestHash());
        assertThat(reloaded.getMediaAssetId()).isEqualTo(original.getMediaAssetId());
        assertThat(reloaded.getDurationMillis()).isEqualTo(original.getDurationMillis());
        assertThat(reloaded.getCueCount()).isEqualTo(original.getCueCount());
        assertThat(reloaded.getCodecMimeType()).isEqualTo(original.getCodecMimeType());
    }

    @Test
    @DisplayName("Should enforce unique playback identity per chapter and managed voice")
    void shouldEnforceUniqueChapterVoicePlaybackIdentity() {
        Instant now = Instant.now();
        playbackRepository.save(ChapterNarrationPlayback.create(UUID.randomUUID(), CHAPTER_ID, VOICE_ID, now));

        assertThatThrownBy(() -> playbackRepository.save(
                ChapterNarrationPlayback.create(UUID.randomUUID(), CHAPTER_ID, VOICE_ID, now)
        )).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("Should fail optimistic version check when switching stale playback")
    void shouldFailOptimisticVersionCheckWhenSwitchingStalePlayback() {
        Instant now = Instant.now();
        UUID playbackId = UUID.randomUUID();
        UUID artifactId = UUID.randomUUID();

        ChapterNarrationPlayback saved = playbackRepository.save(
                ChapterNarrationPlayback.create(playbackId, CHAPTER_ID, VOICE_ID, now)
        );
        ChapterNarrationPlaybackArtifact artifact = artifactRepository.insert(artifact(artifactId, saved, now));
        entityManager.clear();

        jdbcTemplate.update(
                "UPDATE novel_chapter_narration_playbacks SET version = 1 WHERE id = ?",
                playbackId.toString()
        );

        saved.switchCurrentArtifact(artifact, now.plusSeconds(10));
        assertThatThrownBy(() -> {
            playbackRepository.save(saved);
        }).isInstanceOf(org.springframework.orm.ObjectOptimisticLockingFailureException.class);
    }

    @Test
    @DisplayName("Should insert cue once and reject duplicate ordinal without overwriting timing")
    void shouldInsertCueOnceAndRejectDuplicateWithoutOverwritingTiming() {
        Instant now = Instant.now();
        UUID playbackId = UUID.randomUUID();
        UUID artifactId = UUID.randomUUID();

        ChapterNarrationPlayback playback = playbackRepository.save(
                ChapterNarrationPlayback.create(playbackId, CHAPTER_ID, VOICE_ID, now)
        );
        artifactRepository.insert(artifact(artifactId, playback, now));
        cueRepository.insertAll(List.of(
                ChapterNarrationPlaybackCue.create(artifactId, 0, SEGMENT_1_ID, 0, 0L, 1_000L)
        ));

        assertThatThrownBy(() -> cueRepository.insertAll(List.of(
                ChapterNarrationPlaybackCue.create(artifactId, 0, SEGMENT_2_ID, 1, 1_100L, 2_000L)
        ))).isInstanceOf(RuntimeException.class);

        List<ChapterNarrationPlaybackCue> reloaded = cueRepository.findByArtifactId(artifactId);
        assertThat(reloaded).hasSize(1);
        assertThat(reloaded.get(0).getSegmentId()).isEqualTo(SEGMENT_1_ID);
        assertThat(reloaded.get(0).getSegmentIndex()).isZero();
        assertThat(reloaded.get(0).getStartMillis()).isZero();
        assertThat(reloaded.get(0).getEndMillis()).isEqualTo(1_000L);
    }

    @Test
    @DisplayName("Should enforce artifact playback foreign key")
    void shouldEnforceArtifactPlaybackForeignKey() {
        Instant now = Instant.now();
        UUID missingPlaybackId = UUID.randomUUID();
        ChapterNarrationPlayback missingPlayback = ChapterNarrationPlayback.create(
                missingPlaybackId,
                CHAPTER_ID,
                VOICE_ID,
                now
        );

        assertThatThrownBy(() -> artifactRepository.insert(artifact(UUID.randomUUID(), missingPlayback, now)))
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    @DisplayName("Should enforce cue artifact foreign key")
    void shouldEnforceCueArtifactForeignKey() {
        UUID missingArtifactId = UUID.randomUUID();

        assertThatThrownBy(() -> cueRepository.insertAll(List.of(
                ChapterNarrationPlaybackCue.create(missingArtifactId, 0, SEGMENT_1_ID, 0, 0L, 1_000L)
        ))).isInstanceOf(RuntimeException.class);
    }

    private void seedSegment(UUID segmentId, int segmentIndex, String text, String hash, Timestamp timestamp) {
        jdbcTemplate.update(
                "INSERT INTO novel_chapter_narration_segments (id, chapter_id, segment_index, text, character_count, content_hash, status, created_at, updated_at) " +
                        "VALUES (?, ?, ?, ?, ?, ?, 'CURRENT', ?, ?)",
                segmentId.toString(),
                CHAPTER_ID.toString(),
                segmentIndex,
                text,
                text.length(),
                hash,
                timestamp,
                timestamp
        );
    }

    private static ChapterNarrationPlaybackArtifact artifact(
            UUID artifactId,
            ChapterNarrationPlayback playback,
            Instant now
    ) {
        return ChapterNarrationPlaybackArtifact.create(
                artifactId,
                playback,
                1L,
                1L,
                MANIFEST_HASH,
                MEDIA_ASSET_ID,
                2_000L,
                2,
                "audio/mpeg",
                now
        );
    }
}
