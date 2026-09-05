package com.universe.novel.infrastructure.persistence.voice;

import com.universe.novel.application.exceptions.ManagedVoiceInvalidStateException;
import com.universe.novel.application.exceptions.ManagedVoiceKeyAlreadyExistsException;
import com.universe.novel.application.ports.ManagedVoiceRepositoryPort;
import com.universe.novel.domain.narration.ManagedVoice;
import com.universe.novel.domain.narration.ManagedVoiceStatus;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

@Component
public class ManagedVoicePersistenceAdapter implements ManagedVoiceRepositoryPort {

    private static final String UQ_VOICE_KEY = "uq_novel_managed_voices_key";
    private static final String UQ_SINGLE_DEFAULT = "uq_novel_managed_voices_single_default";
    private static final String CHK_ACTIVE_DEFAULT = "chk_novel_managed_voices_active_default";

    private final SpringDataManagedVoiceJpaRepository repository;

    public ManagedVoicePersistenceAdapter(
            SpringDataManagedVoiceJpaRepository repository
    ) {
        this.repository = Objects.requireNonNull(repository, "repository must not be null");
    }

    @Override
    public Optional<ManagedVoice> findById(UUID id) {
        if (id == null) {
            return Optional.empty();
        }
        return repository.findById(id.toString()).map(this::toDomain);
    }

    @Override
    public Optional<ManagedVoice> findByVoiceKey(String voiceKey) {
        if (voiceKey == null || voiceKey.isBlank()) {
            return Optional.empty();
        }
        return repository.findByVoiceKey(voiceKey.trim()).map(this::toDomain);
    }

    @Override
    public Optional<ManagedVoice> findDefaultVoice() {
        return repository.findByDefaultVoiceTrue().map(this::toDomain);
    }

    @Override
    public List<ManagedVoice> findAll() {
        return repository.findAllByOrderByDisplayOrderAscCreatedAtAsc().stream()
                .map(this::toDomain)
                .toList();
    }

    @Override
    public List<ManagedVoice> findAllActive() {
        return repository.findAllByStatusOrderByDisplayOrderAscCreatedAtAsc(ManagedVoiceStatus.ACTIVE.name()).stream()
                .map(this::toDomain)
                .toList();
    }

    @Override
    public boolean existsByVoiceKey(String voiceKey) {
        if (voiceKey == null || voiceKey.isBlank()) {
            return false;
        }
        return repository.existsByVoiceKey(voiceKey.trim());
    }

    @Override
    public ManagedVoice save(ManagedVoice managedVoice) {
        if (managedVoice == null) {
            throw new IllegalArgumentException("ManagedVoice must not be null");
        }

        String idStr = managedVoice.getId().toString();
        Optional<ManagedVoiceJpaEntity> existing = repository.findById(idStr);

        ManagedVoiceJpaEntity entity;
        if (existing.isPresent()) {
            entity = existing.get();
            entity.setDisplayName(managedVoice.getDisplayName());
            entity.setProviderVoiceId(managedVoice.getProviderVoiceId());
            entity.setStatus(managedVoice.getStatus().name());
            entity.setDisplayOrder(managedVoice.getDisplayOrder());
            entity.setDefaultVoice(managedVoice.isDefaultVoice());
            entity.setSynthesisRevision(managedVoice.getSynthesisRevision());
            entity.setUpdatedAt(managedVoice.getUpdatedAt());
        } else {
            entity = new ManagedVoiceJpaEntity(
                    idStr,
                    managedVoice.getVoiceKey(),
                    managedVoice.getDisplayName(),
                    managedVoice.getProviderVoiceId(),
                    managedVoice.getStatus().name(),
                    managedVoice.getDisplayOrder(),
                    managedVoice.isDefaultVoice(),
                    managedVoice.getSynthesisRevision(),
                    managedVoice.getCreatedAt(),
                    managedVoice.getUpdatedAt()
            );
        }

        try {
            ManagedVoiceJpaEntity saved = repository.saveAndFlush(entity);
            return toDomain(saved);
        } catch (DataIntegrityViolationException ex) {
            if (isConstraintViolation(ex, UQ_VOICE_KEY)) {
                throw new ManagedVoiceKeyAlreadyExistsException(managedVoice.getVoiceKey());
            }
            if (isConstraintViolation(ex, UQ_SINGLE_DEFAULT) || isConstraintViolation(ex, "is_default_unique_guard")) {
                throw new ManagedVoiceInvalidStateException("Hệ thống chỉ cho phép duy nhất một giọng đọc mặc định.");
            }
            if (isConstraintViolation(ex, CHK_ACTIVE_DEFAULT)) {
                throw new ManagedVoiceInvalidStateException("Giọng đọc mặc định bắt buộc phải ở trạng thái ACTIVE.");
            }
            throw ex;
        }
    }

    private boolean isConstraintViolation(DataIntegrityViolationException ex, String targetConstraint) {
        String target = targetConstraint.toLowerCase();
        Throwable current = ex;
        while (current != null) {
            if (current instanceof org.hibernate.exception.ConstraintViolationException cve) {
                if (cve.getConstraintName() != null
                        && cve.getConstraintName().toLowerCase().contains(target)) {
                    return true;
                }
            }
            if (current.getMessage() != null
                    && current.getMessage().toLowerCase().contains(target)) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private ManagedVoice toDomain(ManagedVoiceJpaEntity entity) {
        return ManagedVoice.rehydrate(
                UUID.fromString(entity.getId()),
                entity.getVoiceKey(),
                entity.getDisplayName(),
                entity.getProviderVoiceId(),
                ManagedVoiceStatus.valueOf(entity.getStatus()),
                entity.getDisplayOrder(),
                entity.isDefaultVoice(),
                entity.getSynthesisRevision(),
                entity.getCreatedAt(),
                entity.getUpdatedAt()
        );
    }
}
