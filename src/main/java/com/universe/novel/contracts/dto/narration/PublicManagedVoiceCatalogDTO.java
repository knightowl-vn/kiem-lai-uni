package com.universe.novel.contracts.dto.narration;

import java.util.List;

public record PublicManagedVoiceCatalogDTO(
        List<PublicNarrationVoiceDTO> voices
) {
}
