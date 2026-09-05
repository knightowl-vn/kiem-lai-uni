package com.universe.novel.application.voice.commands;

public record CreateManagedVoiceCommand(
        String voiceKey,
        String displayName,
        String providerVoiceId,
        int displayOrder,
        boolean defaultVoice
) {
}
