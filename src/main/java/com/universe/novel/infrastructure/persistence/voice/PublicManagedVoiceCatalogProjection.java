package com.universe.novel.infrastructure.persistence.voice;

public interface PublicManagedVoiceCatalogProjection {

    String getVoiceKey();

    String getDisplayName();

    boolean isDefaultVoice();
}
