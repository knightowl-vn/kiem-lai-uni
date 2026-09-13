package com.universe.novel.infrastructure.persistence.narration;

import com.universe.novel.application.ports.PublicChapterNarrationPlaybackQueryPort.PublicChapterNarrationPlaybackSnapshot;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.jpa.repository.Query;

import java.lang.reflect.Method;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PublicChapterNarrationPlaybackQueryPersistenceAdapterTest {

    private static final UUID CHAPTER_ID = UUID.fromString("b1000000-0000-0000-0000-000000000001");
    private static final UUID VOICE_ID = UUID.fromString("b1000000-0000-0000-0000-000000000002");
    private static final UUID PLAYBACK_ID = UUID.fromString("b1000000-0000-0000-0000-000000000003");
    private static final UUID ARTIFACT_ID = UUID.fromString("b1000000-0000-0000-0000-000000000004");
    private static final UUID MEDIA_ID = UUID.fromString("b1000000-0000-0000-0000-000000000005");

    @Mock
    private SpringDataPublicChapterNarrationPlaybackQueryRepository repository;

    @Mock
    private PublicChapterNarrationPlaybackSnapshotProjection projection;

    @Test
    void mapsACompleteCurrentArtifactSnapshotWithoutHydratingAggregates() {
        when(repository.findPublishedPlayback(CHAPTER_ID.toString(), VOICE_ID.toString()))
                .thenReturn(Optional.of(projection));
        when(projection.getChapterId()).thenReturn(CHAPTER_ID.toString());
        when(projection.getChapterContentVersion()).thenReturn(11L);
        when(projection.getPlaybackId()).thenReturn(PLAYBACK_ID.toString());
        when(projection.getCurrentArtifactId()).thenReturn(ARTIFACT_ID.toString());
        when(projection.getArtifactId()).thenReturn(ARTIFACT_ID.toString());
        when(projection.getArtifactPlaybackId()).thenReturn(PLAYBACK_ID.toString());
        when(projection.getArtifactChapterId()).thenReturn(CHAPTER_ID.toString());
        when(projection.getArtifactManagedVoiceId()).thenReturn(VOICE_ID.toString());
        when(projection.getArtifactSourceContentVersion()).thenReturn(10L);
        when(projection.getArtifactSynthesisRevision()).thenReturn(4L);
        when(projection.getMediaAssetId()).thenReturn(MEDIA_ID.toString());
        when(projection.getDurationMillis()).thenReturn(8_000L);
        when(projection.getCueCount()).thenReturn(3);
        when(projection.getCodecMimeType()).thenReturn("audio/mpeg");

        PublicChapterNarrationPlaybackSnapshot result = adapter()
                .findPublishedPlayback(CHAPTER_ID, VOICE_ID)
                .orElseThrow();

        assertThat(result).isEqualTo(new PublicChapterNarrationPlaybackSnapshot(
                CHAPTER_ID, 11L, PLAYBACK_ID, ARTIFACT_ID, ARTIFACT_ID, PLAYBACK_ID,
                CHAPTER_ID, VOICE_ID, 10L, 4L, MEDIA_ID, 8_000L, 3, "audio/mpeg"
        ));
    }

    @Test
    void preservesNullableLeftJoinFieldsForOrdinaryMissingState() {
        when(repository.findPublishedPlayback(CHAPTER_ID.toString(), null)).thenReturn(Optional.of(projection));
        when(projection.getChapterId()).thenReturn(CHAPTER_ID.toString());
        when(projection.getChapterContentVersion()).thenReturn(11L);

        PublicChapterNarrationPlaybackSnapshot result = adapter()
                .findPublishedPlayback(CHAPTER_ID, null)
                .orElseThrow();

        assertThat(result.chapterId()).isEqualTo(CHAPTER_ID);
        assertThat(result.playbackId()).isNull();
        assertThat(result.currentArtifactId()).isNull();
        assertThat(result.artifactId()).isNull();
        assertThat(result.mediaAssetId()).isNull();
        verify(repository).findPublishedPlayback(CHAPTER_ID.toString(), null);
    }

    @Test
    void queryIsChapterDrivenAndPreservesPublicationAndOwnershipFields() throws Exception {
        Method method = SpringDataPublicChapterNarrationPlaybackQueryRepository.class.getMethod(
                "findPublishedPlayback", String.class, String.class
        );
        String query = method.getAnnotation(Query.class).value();

        assertThat(query).contains(
                "from novel_chapters c",
                "inner join novel_volumes v",
                "left join novel_chapter_narration_playbacks p",
                "p.managed_voice_id = :managedVoiceId",
                "left join novel_chapter_narration_playback_artifacts a",
                "a.id = p.current_artifact_id",
                "c.status = 'PUBLISHED'",
                "v.status = 'PUBLISHED'",
                "p.id as playbackId",
                "p.current_artifact_id as currentArtifactId",
                "a.playback_id as artifactPlaybackId",
                "a.chapter_id as artifactChapterId",
                "a.managed_voice_id as artifactManagedVoiceId"
        );
        assertThat(query).doesNotContain("media_assets", "media_asset_versions", "c.content as");
    }

    private PublicChapterNarrationPlaybackQueryPersistenceAdapter adapter() {
        return new PublicChapterNarrationPlaybackQueryPersistenceAdapter(repository);
    }
}
