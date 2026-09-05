package com.universe.novel.application.ports;

import com.universe.novel.domain.narration.ManagedVoice;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Outbound repository port for persisting and querying {@link ManagedVoice} aggregates.
 */
public interface ManagedVoiceRepositoryPort {

    Optional<ManagedVoice> findById(UUID id);

    Optional<ManagedVoice> findByVoiceKey(String voiceKey);

    Optional<ManagedVoice> findDefaultVoice();

    List<ManagedVoice> findAll();

    List<ManagedVoice> findAllActive();

    boolean existsByVoiceKey(String voiceKey);

    ManagedVoice save(ManagedVoice managedVoice);
}
