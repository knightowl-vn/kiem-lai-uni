package com.universe.novel.infrastructure.persistence.narration;

import com.universe.media.contracts.dto.MediaAssetVersionSnapshotDTO;
import com.universe.novel.application.narration.ChapterAudioAssemblyCue;
import com.universe.novel.application.narration.ChapterNarrationPlaybackBuildSnapshot;
import com.universe.novel.application.narration.ChapterNarrationPlaybackSegmentSnapshot;
import com.universe.novel.application.narration.FinalizeChapterNarrationPlaybackCommand;
import com.universe.novel.application.narration.FinalizeChapterNarrationPlaybackUseCase;
import com.universe.novel.application.ports.ChapterNarrationAudioRepositoryPort;
import com.universe.novel.application.ports.ChapterNarrationManifestRepositoryPort;
import com.universe.novel.application.ports.ChapterNarrationSegmentRepositoryPort;
import com.universe.novel.application.ports.ChapterRepositoryPort;
import com.universe.novel.application.ports.ManagedVoiceRepositoryPort;
import com.universe.novel.domain.Chapter;
import com.universe.novel.domain.ChapterStatus;
import com.universe.novel.domain.narration.ChapterNarrationAudio;
import com.universe.novel.domain.narration.ChapterNarrationManifest;
import com.universe.novel.domain.narration.ChapterNarrationSegment;
import com.universe.novel.domain.narration.ChapterNarrationSegmentStatus;
import com.universe.novel.domain.narration.ManagedVoice;
import com.universe.shared.id.IdGeneratorPort;
import com.universe.shared.time.ClockPort;
import com.universe.test.TestDatabaseSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DataJpaTest(properties = {
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.flyway.enabled=true"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({
        FinalizeChapterNarrationPlaybackUseCase.class,
        ChapterNarrationPlaybackPersistenceAdapter.class,
        ChapterNarrationPlaybackArtifactPersistenceAdapter.class,
        ChapterNarrationPlaybackCuePersistenceAdapter.class
})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class FinalizeChapterNarrationPlaybackTransactionalIntegrationTest {

    private static final UUID USER_ID = UUID.fromString("71000000-0000-0000-0000-000000000001");
    private static final UUID VOLUME_ID = UUID.fromString("71000000-0000-0000-0000-000000000002");
    private static final UUID CHAPTER_ID = UUID.fromString("71000000-0000-0000-0000-000000000003");
    private static final UUID VOICE_ID = UUID.fromString("71000000-0000-0000-0000-000000000004");
    private static final UUID SEGMENT_ID = UUID.fromString("71000000-0000-0000-0000-000000000005");
    private static final UUID AUDIO_ID = UUID.fromString("71000000-0000-0000-0000-000000000006");
    private static final UUID SOURCE_MEDIA_ID = UUID.fromString("71000000-0000-0000-0000-000000000007");
    private static final UUID CANDIDATE_MEDIA_ID = UUID.fromString("71000000-0000-0000-0000-000000000008");
    private static final UUID PLAYBACK_ID = UUID.fromString("71000000-0000-0000-0000-000000000009");
    private static final UUID ARTIFACT_ID = UUID.fromString("71000000-0000-0000-0000-000000000010");
    private static final UUID OLD_ARTIFACT_ID = UUID.fromString("71000000-0000-0000-0000-000000000011");
    private static final UUID OLD_MEDIA_ID = UUID.fromString("71000000-0000-0000-0000-000000000012");
    private static final Instant NOW = Instant.parse("2026-09-08T12:00:00Z");
    private static final String MANIFEST_HASH = "a".repeat(64);

    @DynamicPropertySource
    static void configureDataSource(DynamicPropertyRegistry registry) {
        TestDatabaseSupport.configureDynamicProperties(registry);
    }

    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private FinalizeChapterNarrationPlaybackUseCase useCase;
    @Autowired
    private ChapterNarrationPlaybackArtifactPersistenceAdapter artifactAdapter;

    @MockBean
    private ChapterRepositoryPort chapterRepositoryPort;
    @MockBean
    private ManagedVoiceRepositoryPort managedVoiceRepositoryPort;
    @MockBean
    private ChapterNarrationManifestRepositoryPort manifestRepositoryPort;
    @MockBean
    private ChapterNarrationSegmentRepositoryPort segmentRepositoryPort;
    @MockBean
    private ChapterNarrationAudioRepositoryPort audioRepositoryPort;
    @MockBean
    private IdGeneratorPort idGeneratorPort;
    @MockBean
    private ClockPort clockPort;
    private Chapter chapter;
    private ManagedVoice voice;

    private ChapterNarrationSegment segment;
    private ChapterNarrationAudio audio;
    private ChapterNarrationPlaybackBuildSnapshot snapshot;

    @BeforeEach
    void cleanSeedAndArrangeSnapshotTruth() {
        cleanDatabaseRows();
        seedDatabaseRows();

        chapter = mock(Chapter.class);
        voice = mock(ManagedVoice.class);
        segment = ChapterNarrationSegment.create(SEGMENT_ID, CHAPTER_ID, 0, "Playback segment.", NOW);
        audio = ChapterNarrationAudio.rehydrate(
                AUDIO_ID, SEGMENT_ID, VOICE_ID, SOURCE_MEDIA_ID, 2L, 0L, NOW, NOW
        );
        snapshot = new ChapterNarrationPlaybackBuildSnapshot(
                CHAPTER_ID,
                VOICE_ID,
                1L,
                2L,
                MANIFEST_HASH,
                List.of(new ChapterNarrationPlaybackSegmentSnapshot(
                        SEGMENT_ID,
                        0,
                        segment.getContentHash(),
                        AUDIO_ID,
                        0L,
                        SOURCE_MEDIA_ID,
                        2L,
                        new MediaAssetVersionSnapshotDTO(
                                SOURCE_MEDIA_ID, 1, "b".repeat(64), "audio/wav", 100L, "segment.wav"
                        )
                ))
        );

        when(chapterRepositoryPort.findById(CHAPTER_ID)).thenReturn(Optional.of(chapter));
        when(chapter.getStatus()).thenReturn(ChapterStatus.PUBLISHED);
        when(chapter.getContentVersion()).thenReturn(1L);
        when(managedVoiceRepositoryPort.findById(VOICE_ID)).thenReturn(Optional.of(voice));
        when(voice.isActive()).thenReturn(true);
        when(voice.getSynthesisRevision()).thenReturn(2L);
        when(manifestRepositoryPort.findByChapterId(CHAPTER_ID)).thenReturn(Optional.of(
                ChapterNarrationManifest.create(CHAPTER_ID, 1L, MANIFEST_HASH, NOW)
        ));
        when(segmentRepositoryPort.findByChapterIdAndStatus(CHAPTER_ID, ChapterNarrationSegmentStatus.CURRENT))
                .thenReturn(List.of(segment));
        when(audioRepositoryPort.findBySegmentIdInAndManagedVoiceId(List.of(SEGMENT_ID), VOICE_ID))
                .thenReturn(List.of(audio));
        when(clockPort.now()).thenReturn(NOW);
    }

    @Test
    void firstPublicationCommitsPlaybackWithNonNullCurrentArtifactAndExactCue() {
        when(idGeneratorPort.generate()).thenReturn(PLAYBACK_ID, ARTIFACT_ID);

        useCase.execute(command(CANDIDATE_MEDIA_ID));

        assertThat(jdbcTemplate.queryForObject(
                "SELECT current_artifact_id FROM novel_chapter_narration_playbacks WHERE id = ?",
                String.class,
                PLAYBACK_ID.toString()
        )).isEqualTo(ARTIFACT_ID.toString());
        assertThat(jdbcTemplate.queryForObject(
                "SELECT codec_mime_type FROM novel_chapter_narration_playback_artifacts WHERE id = ?",
                String.class,
                ARTIFACT_ID.toString()
        )).isEqualTo("audio/mpeg");
        assertThat(artifactAdapter.findById(ARTIFACT_ID).orElseThrow().getSourceFingerprint())
                .isEqualTo(com.universe.novel.application.narration.ChapterNarrationPlaybackSourceFingerprint.compute(snapshot));
        assertThat(jdbcTemplate.queryForObject(
                "SELECT end_millis FROM novel_chapter_narration_playback_cues WHERE artifact_id = ? AND cue_ordinal = 0",
                Long.class,
                ARTIFACT_ID.toString()
        )).isEqualTo(1_234L);
    }

    @Test
    void firstPublicationCueConstraintFailureRollsBackPlaybackArtifactAndCues() {
        when(idGeneratorPort.generate()).thenReturn(PLAYBACK_ID, ARTIFACT_ID);
        jdbcTemplate.update(
                "DELETE FROM novel_chapter_narration_segments WHERE id = ?",
                SEGMENT_ID.toString()
        );

        assertThatThrownBy(() -> useCase.execute(command(CANDIDATE_MEDIA_ID)))
                .isInstanceOf(RuntimeException.class);

        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM novel_chapter_narration_playbacks WHERE chapter_id = ? AND managed_voice_id = ?",
                Integer.class,
                CHAPTER_ID.toString(),
                VOICE_ID.toString()
        )).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM novel_chapter_narration_playback_artifacts WHERE id = ? AND media_asset_id = ?",
                Integer.class,
                ARTIFACT_ID.toString(),
                CANDIDATE_MEDIA_ID.toString()
        )).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM novel_chapter_narration_playback_cues WHERE artifact_id = ?",
                Integer.class,
                ARTIFACT_ID.toString()
        )).isZero();
    }

    @Test
    void artifactInsertFailureRollsBackAndPreservesPreviousPointer() {
        seedExistingPlaybackAndArtifact();
        when(idGeneratorPort.generate()).thenReturn(OLD_ARTIFACT_ID);

        assertThatThrownBy(() -> useCase.execute(command(CANDIDATE_MEDIA_ID)))
                .isInstanceOf(RuntimeException.class);

        assertThat(jdbcTemplate.queryForObject(
                "SELECT current_artifact_id FROM novel_chapter_narration_playbacks WHERE id = ?",
                String.class,
                PLAYBACK_ID.toString()
        )).isEqualTo(OLD_ARTIFACT_ID.toString());
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM novel_chapter_narration_playback_artifacts WHERE playback_id = ?",
                Integer.class,
                PLAYBACK_ID.toString()
        )).isEqualTo(1);
    }

    private FinalizeChapterNarrationPlaybackCommand command(UUID candidateMediaAssetId) {
        return new FinalizeChapterNarrationPlaybackCommand(
                snapshot,
                candidateMediaAssetId,
                1_234L,
                List.of(new ChapterAudioAssemblyCue(0, SEGMENT_ID, 0, 0L, 1_234L))
        );
    }

    private void cleanDatabaseRows() {
        jdbcTemplate.update(
                "UPDATE novel_chapter_narration_playbacks SET current_artifact_id = NULL WHERE chapter_id = ?",
                CHAPTER_ID.toString()
        );
        jdbcTemplate.update(
                "DELETE FROM novel_chapter_narration_playback_cues WHERE artifact_id IN "
                        + "(SELECT id FROM novel_chapter_narration_playback_artifacts WHERE chapter_id = ?)",
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
    }

    private void seedDatabaseRows() {
        Timestamp timestamp = Timestamp.from(NOW);
        jdbcTemplate.update(
                "INSERT INTO identity_users (id, email, password_hash, display_name, status, role, aggregate_version, persistence_version, created_at, updated_at) "
                        + "VALUES (?, 'h9e-admin@universe.local', '$2a$10$hash', 'H9E Admin', 'ACTIVE', 'ADMIN', 1, 0, ?, ?)",
                USER_ID.toString(), timestamp, timestamp
        );
        jdbcTemplate.update(
                "INSERT INTO novel_volumes (id, title, slug, description, sort_order, status, created_by, updated_by, published_by, archived_by, created_at, updated_at, published_at, archived_at, aggregate_version, persistence_version) "
                        + "VALUES (?, 'H9E Volume', 'h9e-volume', 'Description', 9980, 'PUBLISHED', ?, ?, ?, NULL, ?, ?, ?, NULL, 1, 0)",
                VOLUME_ID.toString(), USER_ID.toString(), USER_ID.toString(), USER_ID.toString(),
                timestamp, timestamp, timestamp
        );
        jdbcTemplate.update(
                "INSERT INTO novel_chapters (id, volume_id, chapter_number, title, slug, summary, content, status, "
                        + "created_by, updated_by, published_by, archived_by, created_at, updated_at, published_at, archived_at, aggregate_version, persistence_version, content_version) "
                        + "VALUES (?, ?, 9981, 'H9E Chapter', 'h9e-chapter', 'Summary', 'Content', 'PUBLISHED', "
                        + "?, ?, ?, NULL, ?, ?, ?, NULL, 1, 0, 1)",
                CHAPTER_ID.toString(), VOLUME_ID.toString(), USER_ID.toString(), USER_ID.toString(), USER_ID.toString(),
                timestamp, timestamp, timestamp
        );
        jdbcTemplate.update(
                "INSERT INTO novel_managed_voices (id, voice_key, display_name, provider_voice_id, status, display_order, is_default, synthesis_revision, persistence_version, created_at, updated_at) "
                        + "VALUES (?, 'h9e-voice', 'H9E Voice', 'h9e-provider', 'ACTIVE', 1, FALSE, 2, 0, ?, ?)",
                VOICE_ID.toString(), timestamp, timestamp
        );
        jdbcTemplate.update(
                "INSERT INTO novel_chapter_narration_segments (id, chapter_id, segment_index, text, character_count, content_hash, status, created_at, updated_at) "
                        + "VALUES (?, ?, 0, 'Playback segment.', 17, ?, 'CURRENT', ?, ?)",
                SEGMENT_ID.toString(), CHAPTER_ID.toString(),
                com.universe.novel.domain.narration.NarrationTextSegment.computeSha256("Playback segment."),
                timestamp, timestamp
        );
    }

    private void seedExistingPlaybackAndArtifact() {
        Timestamp timestamp = Timestamp.from(NOW.minusSeconds(60));
        jdbcTemplate.update(
                "INSERT INTO novel_chapter_narration_playbacks (id, chapter_id, managed_voice_id, current_artifact_id, version, created_at, updated_at) "
                        + "VALUES (?, ?, ?, NULL, 0, ?, ?)",
                PLAYBACK_ID.toString(), CHAPTER_ID.toString(), VOICE_ID.toString(), timestamp, timestamp
        );
        jdbcTemplate.update(
                "INSERT INTO novel_chapter_narration_playback_artifacts "
                        + "(id, playback_id, chapter_id, managed_voice_id, source_content_version, synthesis_revision, manifest_hash, media_asset_id, duration_millis, cue_count, codec_mime_type, created_at) "
                        + "VALUES (?, ?, ?, ?, 1, 2, ?, ?, 900, 1, 'audio/mpeg', ?)",
                OLD_ARTIFACT_ID.toString(), PLAYBACK_ID.toString(), CHAPTER_ID.toString(), VOICE_ID.toString(),
                MANIFEST_HASH, OLD_MEDIA_ID.toString(), timestamp
        );
        jdbcTemplate.update(
                "UPDATE novel_chapter_narration_playbacks SET current_artifact_id = ? WHERE id = ?",
                OLD_ARTIFACT_ID.toString(), PLAYBACK_ID.toString()
        );
    }
}
