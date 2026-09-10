package com.universe.novel.application.ports;

import java.util.List;

public interface PublicManagedVoiceCatalogQueryPort {

    List<PublicManagedVoiceCatalogItem> findSelectableVoices();

    record PublicManagedVoiceCatalogItem(
            String voiceKey,
            String displayName,
            boolean defaultVoice
    ) {
    }
}
