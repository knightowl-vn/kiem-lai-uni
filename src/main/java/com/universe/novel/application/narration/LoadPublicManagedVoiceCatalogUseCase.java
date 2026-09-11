package com.universe.novel.application.narration;

import com.universe.novel.application.ports.PublicManagedVoiceCatalogQueryPort;
import com.universe.novel.contracts.dto.narration.PublicManagedVoiceCatalogDTO;
import com.universe.novel.contracts.dto.narration.PublicNarrationVoiceDTO;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;

/**
 * Transactional database loader used only on a public catalog cache miss.
 */
@Service
public class LoadPublicManagedVoiceCatalogUseCase {

    private final PublicManagedVoiceCatalogQueryPort catalogQueryPort;

    public LoadPublicManagedVoiceCatalogUseCase(
            PublicManagedVoiceCatalogQueryPort catalogQueryPort
    ) {
        this.catalogQueryPort = Objects.requireNonNull(
                catalogQueryPort,
                "catalogQueryPort must not be null"
        );
    }

    @Transactional(readOnly = true)
    public PublicManagedVoiceCatalogDTO execute(
            GetPublicManagedVoiceCatalogQuery query
    ) {
        Objects.requireNonNull(query, "query must not be null");

        return new PublicManagedVoiceCatalogDTO(
                catalogQueryPort.findSelectableVoices().stream()
                        .map(voice -> new PublicNarrationVoiceDTO(
                                voice.voiceKey(),
                                voice.displayName(),
                                voice.defaultVoice()
                        ))
                        .toList()
        );
    }
}
