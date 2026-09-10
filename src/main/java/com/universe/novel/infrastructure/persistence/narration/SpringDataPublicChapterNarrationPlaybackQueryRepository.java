package com.universe.novel.infrastructure.persistence.narration;

import com.universe.novel.infrastructure.persistence.chapter.ChapterJpaEntity;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface SpringDataPublicChapterNarrationPlaybackQueryRepository
        extends Repository<ChapterJpaEntity, String> {

    @Query(value = """
            select
                c.id as chapterId,
                c.content_version as chapterContentVersion,
                p.id as playbackId,
                p.current_artifact_id as currentArtifactId,
                a.id as artifactId,
                a.playback_id as artifactPlaybackId,
                a.chapter_id as artifactChapterId,
                a.managed_voice_id as artifactManagedVoiceId,
                a.source_content_version as artifactSourceContentVersion,
                a.synthesis_revision as artifactSynthesisRevision,
                a.media_asset_id as mediaAssetId,
                a.duration_millis as durationMillis,
                a.cue_count as cueCount,
                a.codec_mime_type as codecMimeType
            from novel_chapters c
            inner join novel_volumes v
                on v.id = c.volume_id
            left join novel_chapter_narration_playbacks p
                on p.chapter_id = c.id
                and p.managed_voice_id = :managedVoiceId
            left join novel_chapter_narration_playback_artifacts a
                on a.id = p.current_artifact_id
            where c.id = :chapterId
                and c.status = 'PUBLISHED'
                and v.status = 'PUBLISHED'
            """, nativeQuery = true)
    Optional<PublicChapterNarrationPlaybackSnapshotProjection> findPublishedPlayback(
            @Param("chapterId") String chapterId,
            @Param("managedVoiceId") String managedVoiceId
    );
}
