package com.universe.novel.infrastructure.persistence.narration;

public interface PublicChapterNarrationPlaybackSnapshotProjection {

    String getChapterId();

    long getChapterContentVersion();

    String getPlaybackId();

    String getCurrentArtifactId();

    String getArtifactId();

    String getArtifactPlaybackId();

    String getArtifactChapterId();

    String getArtifactManagedVoiceId();

    Long getArtifactSourceContentVersion();

    Long getArtifactSynthesisRevision();

    String getMediaAssetId();

    Long getDurationMillis();

    Integer getCueCount();

    String getCodecMimeType();
}
