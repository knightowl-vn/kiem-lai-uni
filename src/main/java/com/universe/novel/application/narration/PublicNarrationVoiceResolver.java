package com.universe.novel.application.narration;

import com.universe.novel.application.exceptions.ManagedVoiceInvalidStateException;
import com.universe.novel.application.exceptions.ManagedVoiceNotFoundException;
import com.universe.novel.application.ports.ManagedVoiceRepositoryPort;
import com.universe.novel.domain.narration.ManagedVoice;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * Shared public Reader policy for selecting an active managed narration voice.
 */
final class PublicNarrationVoiceResolver {

    private PublicNarrationVoiceResolver() {
    }

    static Resolution resolve(
            ManagedVoiceRepositoryPort managedVoiceRepositoryPort,
            String requestedVoiceKey
    ) {
        Objects.requireNonNull(managedVoiceRepositoryPort, "managedVoiceRepositoryPort must not be null");

        List<ManagedVoice> activeVoices = managedVoiceRepositoryPort.findAllActive().stream()
                .sorted(Comparator
                        .comparingInt(ManagedVoice::getDisplayOrder)
                        .thenComparing(ManagedVoice::getCreatedAt)
                        .thenComparing(ManagedVoice::getId))
                .toList();

        if (requestedVoiceKey != null && !requestedVoiceKey.isBlank()) {
            String trimmedKey = requestedVoiceKey.trim();
            ManagedVoice voice = managedVoiceRepositoryPort.findByVoiceKey(trimmedKey)
                    .orElseThrow(() -> new ManagedVoiceNotFoundException(trimmedKey));
            if (!voice.isActive()) {
                throw new ManagedVoiceInvalidStateException("Managed voice is not active: " + trimmedKey);
            }
            return new Resolution(activeVoices, voice);
        }

        ManagedVoice selectedVoice = activeVoices.stream()
                .filter(ManagedVoice::isDefaultVoice)
                .findFirst()
                .orElseGet(() -> activeVoices.isEmpty() ? null : activeVoices.get(0));

        return new Resolution(activeVoices, selectedVoice);
    }

    record Resolution(
            List<ManagedVoice> activeVoices,
            ManagedVoice selectedVoice
    ) {
    }
}
