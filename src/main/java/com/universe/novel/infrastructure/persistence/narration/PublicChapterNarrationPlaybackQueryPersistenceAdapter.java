package com.universe.novel.infrastructure.persistence.narration;

import com.universe.novel.application.ports.PublicChapterNarrationPlaybackQueryPort;
import org.springframework.stereotype.Component;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

@Component
public class PublicChapterNarrationPlaybackQueryPersistenceAdapter
        implements PublicChapterNarrationPlaybackQueryPort {

    private final SpringDataPublicChapterNarrationPlaybackQueryRepository repository;

    public PublicChapterNarrationPlaybackQueryPersistenceAdapter(
            SpringDataPublicChapterNarrationPlaybackQueryRepository repository
    ) {
        this.repository = Objects.requireNonNull(repository, "repository must not be null");
    }

    @Override
    public Optional<PublicChapterNarrationPlaybackSnapshot> findPublishedPlayback(
            UUID chapterId,
            UUID managedVoiceId
    ) {
        if (chapterId == null) {
            return Optional.empty();
        }
        return repository.findPublishedPlayback(
                        chapterId.toString(),
                        managedVoiceId == null ? null : managedVoiceId.toString()
                )
                .map(this::toSnapshot);
    }

    private PublicChapterNarrationPlaybackSnapshot toSnapshot(
            PublicChapterNarrationPlaybackSnapshotProjection projection
    ) {
        return new PublicChapterNarrationPlaybackSnapshot(
                toUuid(projection.getChapterId()),
                projection.getChapterContentVersion(),
                toUuid(projection.getPlaybackId()),
                toUuid(projection.getCurrentArtifactId()),
                toUuid(projection.getArtifactId()),
                toUuid(projection.getArtifactPlaybackId()),
                toUuid(projection.getArtifactChapterId()),
                toUuid(projection.getArtifactManagedVoiceId()),
                projection.getArtifactSourceContentVersion(),
                projection.getArtifactSynthesisRevision(),
                toUuid(projection.getMediaAssetId()),
                projection.getDurationMillis(),
                projection.getCueCount(),
                projection.getCodecMimeType()
        );
    }

    private UUID toUuid(String value) {
        return value == null ? null : UUID.fromString(value);
    }
}
