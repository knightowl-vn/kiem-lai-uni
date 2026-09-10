package com.universe.novel.application.narration;

import com.universe.novel.application.ports.PublicManagedVoiceCatalogQueryPort;
import com.universe.novel.contracts.dto.narration.PublicManagedVoiceCatalogDTO;
import com.universe.novel.contracts.dto.narration.PublicNarrationVoiceDTO;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;

@Service
@Transactional(readOnly = true)
public class GetPublicManagedVoiceCatalogUseCase {

    private final PublicManagedVoiceCatalogQueryPort catalogQueryPort;

    public GetPublicManagedVoiceCatalogUseCase(PublicManagedVoiceCatalogQueryPort catalogQueryPort) {
        this.catalogQueryPort = Objects.requireNonNull(catalogQueryPort, "catalogQueryPort must not be null");
    }

    public PublicManagedVoiceCatalogDTO execute(GetPublicManagedVoiceCatalogQuery query) {
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
