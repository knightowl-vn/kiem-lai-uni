package com.universe.novel.application.voice.commands;

import java.util.UUID;

public record ChangeManagedVoiceProviderMappingCommand(
        UUID id,
        String providerVoiceId
) {
}
