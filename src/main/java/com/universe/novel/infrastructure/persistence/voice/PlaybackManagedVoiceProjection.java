package com.universe.novel.infrastructure.persistence.voice;

public interface PlaybackManagedVoiceProjection {

    String getId();

    String getVoiceKey();

    String getStatus();

    long getSynthesisRevision();
}
