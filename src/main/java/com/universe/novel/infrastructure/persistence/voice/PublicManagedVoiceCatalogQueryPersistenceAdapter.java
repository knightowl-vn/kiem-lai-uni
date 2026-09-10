package com.universe.novel.infrastructure.persistence.voice;

import com.universe.novel.application.ports.PublicManagedVoiceCatalogQueryPort;
import com.universe.novel.domain.narration.ManagedVoiceStatus;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Objects;

@Component
public class PublicManagedVoiceCatalogQueryPersistenceAdapter implements PublicManagedVoiceCatalogQueryPort {

    private final SpringDataManagedVoiceJpaRepository repository;

    public PublicManagedVoiceCatalogQueryPersistenceAdapter(SpringDataManagedVoiceJpaRepository repository) {
        this.repository = Objects.requireNonNull(repository, "repository must not be null");
    }

    @Override
    public List<PublicManagedVoiceCatalogItem> findSelectableVoices() {
        return repository.findPublicCatalogByStatus(ManagedVoiceStatus.ACTIVE.name()).stream()
                .map(voice -> new PublicManagedVoiceCatalogItem(
                        voice.getVoiceKey(),
                        voice.getDisplayName(),
                        voice.isDefaultVoice()
                ))
                .toList();
    }
}
