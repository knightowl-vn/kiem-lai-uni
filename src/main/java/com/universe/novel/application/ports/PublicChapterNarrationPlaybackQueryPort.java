package com.universe.novel.application.ports;

import java.util.Optional;
import java.util.UUID;

/**
 * Chapter-driven public read model for the stable playback pointer and current artifact.
 */
public interface PublicChapterNarrationPlaybackQueryPort {

    Optional<PublicChapterNarrationPlaybackSnapshot> findPublishedPlayback(
            UUID chapterId,
            UUID managedVoiceId
    );

    record PublicChapterNarrationPlaybackSnapshot(
            UUID chapterId,
            long chapterContentVersion,
            UUID playbackId,
            UUID currentArtifactId,
            UUID artifactId,
            UUID artifactPlaybackId,
            UUID artifactChapterId,
            UUID artifactManagedVoiceId,
            Long artifactSourceContentVersion,
            Long artifactSynthesisRevision,
            UUID mediaAssetId,
            Long durationMillis,
            Integer cueCount,
            String codecMimeType
    ) {
    }
}
