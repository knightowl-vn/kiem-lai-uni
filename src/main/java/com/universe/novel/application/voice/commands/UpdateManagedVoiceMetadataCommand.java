package com.universe.novel.application.voice.commands;

import java.util.UUID;

public record UpdateManagedVoiceMetadataCommand(
        UUID id,
        String displayName,
        int displayOrder
) {
}
