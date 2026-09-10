package com.universe.novel.infrastructure.persistence.voice;

import com.universe.novel.application.ports.PlaybackManagedVoiceQueryPort;
import com.universe.novel.domain.narration.ManagedVoiceStatus;
import org.springframework.stereotype.Component;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

@Component
public class PlaybackManagedVoiceQueryPersistenceAdapter implements PlaybackManagedVoiceQueryPort {

    private final SpringDataManagedVoiceJpaRepository repository;

    public PlaybackManagedVoiceQueryPersistenceAdapter(SpringDataManagedVoiceJpaRepository repository) {
        this.repository = Objects.requireNonNull(repository, "repository must not be null");
    }

    @Override
    public Optional<PlaybackManagedVoice> findByVoiceKey(String voiceKey) {
        if (voiceKey == null || voiceKey.isBlank()) {
            return Optional.empty();
        }
        return repository.findPlaybackVoiceByVoiceKey(voiceKey.trim()).map(this::toResult);
    }

    @Override
    public Optional<PlaybackManagedVoice> findPreferredActiveVoice() {
        return repository.findPreferredPlaybackVoiceByStatus(ManagedVoiceStatus.ACTIVE.name())
                .map(this::toResult);
    }

    private PlaybackManagedVoice toResult(PlaybackManagedVoiceProjection projection) {
        return new PlaybackManagedVoice(
                UUID.fromString(projection.getId()),
                projection.getVoiceKey(),
                ManagedVoiceStatus.valueOf(projection.getStatus()),
                projection.getSynthesisRevision()
        );
    }
}
