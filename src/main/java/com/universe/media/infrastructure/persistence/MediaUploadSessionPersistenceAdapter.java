package com.universe.media.infrastructure.persistence;

import com.universe.media.application.ports.MediaUploadSessionRepositoryPort;
import com.universe.media.domain.MediaUploadSession;
import com.universe.media.domain.MediaUploadSessionStatus;
import com.universe.media.domain.MediaType;
import com.universe.media.domain.StorageKey;
import com.universe.media.domain.StorageProviderId;
import com.universe.media.domain.UploadSessionCleanupStatus;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Component
public class MediaUploadSessionPersistenceAdapter implements MediaUploadSessionRepositoryPort {

    private final SpringDataMediaUploadSessionJpaRepository repository;

    public MediaUploadSessionPersistenceAdapter(
            SpringDataMediaUploadSessionJpaRepository repository
    ) {
        this.repository = Objects.requireNonNull(repository, "SpringDataMediaUploadSessionJpaRepository cannot be null.");
    }

    @Override
    public Optional<MediaUploadSession> findById(UUID id) {
        Objects.requireNonNull(id, "Session ID cannot be null.");
        return repository.findById(id.toString()).map(this::toDomain);
    }

    @Override
    public Optional<MediaUploadSession> findByIdempotencyKey(String initiatorPrincipalId, String idempotencyKey) {
        Objects.requireNonNull(initiatorPrincipalId, "initiatorPrincipalId cannot be null.");
        Objects.requireNonNull(idempotencyKey, "idempotencyKey cannot be null.");
        return repository.findByInitiatorPrincipalIdAndIdempotencyKey(initiatorPrincipalId.trim(), idempotencyKey.trim())
                .map(this::toDomain);
    }

    @Override
    public Optional<MediaUploadSession> findByStorageAllocationId(UUID storageAllocationId) {
        Objects.requireNonNull(storageAllocationId, "storageAllocationId cannot be null.");
        return repository.findByStorageAllocationId(storageAllocationId.toString()).map(this::toDomain);
    }

    @Override
    public Optional<MediaUploadSession> findByStorageLocation(StorageProviderId providerId, StorageKey storageKey) {
        Objects.requireNonNull(providerId, "StorageProviderId cannot be null.");
        Objects.requireNonNull(storageKey, "StorageKey cannot be null.");
        return repository.findByStorageProviderIdAndStorageKey(providerId.value(), storageKey.value())
                .map(this::toDomain);
    }

    @Override
    public MediaUploadSession save(MediaUploadSession session) {
        Objects.requireNonNull(session, "MediaUploadSession cannot be null.");

        String sessionId = session.getId().toString();
        Optional<MediaUploadSessionJpaEntity> existingOpt = repository.findById(sessionId);

        MediaUploadSessionJpaEntity entity;
        if (existingOpt.isPresent()) {
            entity = existingOpt.get();

            // 1. Existing sessions require valid version evidence
            if (session.getPersistenceVersion() == null) {
                throw new OptimisticLockingFailureException(
                        String.format("Cannot update existing upload session %s without version evidence (persistenceVersion is null).", sessionId)
                );
            }

            // 2. Reject stale versions before mutation
            if (!Objects.equals(session.getPersistenceVersion(), entity.getPersistenceVersion())) {
                throw new OptimisticLockingFailureException(
                        String.format(
                                "Upload session %s was concurrently modified. Expected version %d but found %d.",
                                sessionId, session.getPersistenceVersion(), entity.getPersistenceVersion()
                        )
                );
            }

            // 3. Validate persisted state transition
            validatePersistedStatusTransition(entity.getStatus(), session.getStatus());

            // 4. Protect immutable once-set fields
            if (entity.getProviderUploadId() != null && session.getProviderUploadId() != null
                    && !entity.getProviderUploadId().equals(session.getProviderUploadId())) {
                throw new IllegalStateException("Cannot overwrite existing providerUploadId on session " + sessionId);
            }
            if (entity.getRegisteredAssetId() != null && session.getRegisteredAssetId() != null
                    && !entity.getRegisteredAssetId().equals(session.getRegisteredAssetId().toString())) {
                throw new IllegalStateException("Cannot reassign registeredAssetId on session " + sessionId);
            }
            if (entity.getManifestHash() != null && session.getManifestHash() != null
                    && !entity.getManifestHash().equalsIgnoreCase(session.getManifestHash())) {
                throw new IllegalStateException("Cannot overwrite existing manifestHash on session " + sessionId);
            }

            // 5. Protect immutable storage location and session identity
            validateImmutableFields(entity, session);

            // 6. Mutate only allowed transitionable fields
            entity.setStatus(session.getStatus());
            if (session.getProviderUploadId() != null) {
                entity.setProviderUploadId(session.getProviderUploadId());
            }
            if (session.getDeclaredContentHash() != null) {
                entity.setDeclaredContentHash(session.getDeclaredContentHash());
            }
            if (session.getManifestHash() != null) {
                entity.setManifestHash(session.getManifestHash());
            }
            entity.setLeaseOwner(session.getLeaseOwner());
            entity.setLeaseExpiresAt(session.getLeaseExpiresAt());
            if (session.getRegisteredAssetId() != null) {
                entity.setRegisteredAssetId(session.getRegisteredAssetId().toString());
            }
            entity.setCleanupStatus(session.getCleanupStatus());
            entity.setCleanupRetryCount(session.getCleanupRetryCount());
            entity.setCleanupLastAttemptAt(session.getCleanupLastAttemptAt());
            entity.setCleanupErrorDetail(session.getCleanupErrorDetail());
            entity.setUpdatedAt(session.getUpdatedAt());
            entity.setCompletedAt(session.getCompletedAt());
        } else {
            entity = new MediaUploadSessionJpaEntity();
            entity.setId(sessionId);
            entity.setInitiatorPrincipalId(session.getInitiatorPrincipalId());
            entity.setConsumerContext(session.getConsumerContext());
            entity.setTargetEntityType(session.getTargetEntityType());
            entity.setTargetEntityId(session.getTargetEntityId().toString());
            entity.setIdempotencyKey(session.getIdempotencyKey());
            entity.setMediaType(session.getMediaType().name());
            entity.setDeclaredMimeType(session.getDeclaredMimeType());
            entity.setDeclaredSizeBytes(session.getDeclaredSizeBytes());
            entity.setStorageProviderId(session.getStorageProviderId().value());
            entity.setStorageBucketName(session.getStorageBucketName());
            entity.setStorageKey(session.getStorageKey().value());
            entity.setStorageAllocationId(session.getStorageAllocationId().toString());
            entity.setProviderUploadId(session.getProviderUploadId());
            entity.setPartSizeBytes(session.getPartSizeBytes());
            entity.setExpectedPartCount(session.getExpectedPartCount());
            entity.setDeclaredContentHash(session.getDeclaredContentHash());
            entity.setManifestHash(session.getManifestHash());
            entity.setOriginalFilename(session.getOriginalFilename());
            entity.setClientTag(session.getClientTag());
            entity.setStatus(session.getStatus());
            entity.setExpiresAt(session.getExpiresAt());
            entity.setLeaseOwner(session.getLeaseOwner());
            entity.setLeaseExpiresAt(session.getLeaseExpiresAt());
            entity.setRegisteredAssetId(session.getRegisteredAssetId() != null ? session.getRegisteredAssetId().toString() : null);
            entity.setCleanupStatus(session.getCleanupStatus());
            entity.setCleanupRetryCount(session.getCleanupRetryCount());
            entity.setCleanupLastAttemptAt(session.getCleanupLastAttemptAt());
            entity.setCleanupErrorDetail(session.getCleanupErrorDetail());
            entity.setCreatedAt(session.getCreatedAt());
            entity.setUpdatedAt(session.getUpdatedAt());
            entity.setCompletedAt(session.getCompletedAt());
        }

        try {
            MediaUploadSessionJpaEntity savedEntity = repository.saveAndFlush(entity);
            return toDomain(savedEntity);
        } catch (DataIntegrityViolationException e) {
            throw e;
        }
    }

    public static final int MAX_BATCH_SIZE = MediaUploadSessionRepositoryPort.MAX_BATCH_SIZE;

    public static final Set<MediaUploadSessionStatus> APPROVED_CLEANUP_CANDIDATE_STATUSES =
            MediaUploadSessionRepositoryPort.APPROVED_CLEANUP_CANDIDATE_STATUSES;

    public static final Set<UploadSessionCleanupStatus> APPROVED_CLEANUP_DISCOVERY_STATUSES =
            MediaUploadSessionRepositoryPort.APPROVED_CLEANUP_DISCOVERY_STATUSES;

    public static final Set<UploadSessionCleanupStatus> APPROVED_CLEANUP_STATUSES =
            APPROVED_CLEANUP_DISCOVERY_STATUSES;

    @Override
    public List<MediaUploadSession> findSessionsEligibleForCleanup(
            Set<MediaUploadSessionStatus> sessionStatuses,
            Set<UploadSessionCleanupStatus> cleanupStatuses,
            int maxRetryCount,
            Instant maxLastAttemptAt,
            int limit
    ) {
        Objects.requireNonNull(sessionStatuses, "sessionStatuses must not be null");
        Objects.requireNonNull(cleanupStatuses, "cleanupStatuses must not be null");
        if (limit <= 0 || limit > MAX_BATCH_SIZE) {
            throw new IllegalArgumentException("limit must be between 1 and " + MAX_BATCH_SIZE + ", got: " + limit);
        }
        if (maxRetryCount < 0) {
            throw new IllegalArgumentException("maxRetryCount cannot be negative");
        }
        Set<MediaUploadSessionStatus> unapprovedLifecycle = sessionStatuses.stream()
                .filter(s -> !APPROVED_CLEANUP_CANDIDATE_STATUSES.contains(s))
                .collect(Collectors.toSet());
        if (!unapprovedLifecycle.isEmpty()) {
            throw new IllegalArgumentException(
                    "sessionStatuses contains unapproved lifecycle statuses for cleanup candidate discovery: " + unapprovedLifecycle
                    + ". Only " + APPROVED_CLEANUP_CANDIDATE_STATUSES + " are permitted."
            );
        }
        Set<UploadSessionCleanupStatus> unapprovedCleanup = cleanupStatuses.stream()
                .filter(s -> !APPROVED_CLEANUP_DISCOVERY_STATUSES.contains(s))
                .collect(Collectors.toSet());
        if (!unapprovedCleanup.isEmpty()) {
            throw new IllegalArgumentException(
                    "cleanupStatuses contains unapproved cleanup statuses for cleanup candidate discovery: " + unapprovedCleanup
                    + ". Only " + APPROVED_CLEANUP_DISCOVERY_STATUSES + " are permitted."
            );
        }
        if (sessionStatuses.isEmpty() || cleanupStatuses.isEmpty()) {
            return Collections.emptyList();
        }

        return repository.findEligibleForCleanup(
                sessionStatuses,
                cleanupStatuses,
                maxRetryCount,
                maxLastAttemptAt,
                PageRequest.of(0, limit)
        ).stream().map(this::toDomain).toList();
    }

    @Override
    public List<MediaUploadSession> findExpiredSessions(
            Set<MediaUploadSessionStatus> statuses,
            Instant now,
            int limit
    ) {
        Objects.requireNonNull(statuses, "statuses must not be null");
        Objects.requireNonNull(now, "now must not be null");
        if (limit <= 0 || limit > MAX_BATCH_SIZE) {
            throw new IllegalArgumentException("limit must be between 1 and " + MAX_BATCH_SIZE + ", got: " + limit);
        }
        if (statuses.isEmpty()) {
            return Collections.emptyList();
        }

        return repository.findExpired(
                statuses,
                now,
                PageRequest.of(0, limit)
        ).stream().map(this::toDomain).toList();
    }

    @Override
    public List<MediaUploadSession> findSessionsWithExpiredLeases(
            Set<MediaUploadSessionStatus> statuses,
            Instant now,
            int limit
    ) {
        Objects.requireNonNull(statuses, "statuses must not be null");
        Objects.requireNonNull(now, "now must not be null");
        if (limit <= 0 || limit > MAX_BATCH_SIZE) {
            throw new IllegalArgumentException("limit must be between 1 and " + MAX_BATCH_SIZE + ", got: " + limit);
        }
        if (statuses.isEmpty()) {
            return Collections.emptyList();
        }

        return repository.findWithExpiredLeases(
                statuses,
                now,
                PageRequest.of(0, limit)
        ).stream().map(this::toDomain).toList();
    }


    private void validatePersistedStatusTransition(MediaUploadSessionStatus current, MediaUploadSessionStatus target) {
        if (current == target) {
            return; // Idempotent same-status save is valid
        }
        if (current == MediaUploadSessionStatus.COMPLETED) {
            throw new IllegalStateException("Cannot transition COMPLETED upload session to " + target);
        }
        if (current == MediaUploadSessionStatus.CLEANED_UP) {
            throw new IllegalStateException("Cannot transition CLEANED_UP upload session to " + target);
        }

        boolean valid = switch (current) {
            case ALLOCATED -> (target == MediaUploadSessionStatus.INITIATED
                    || target == MediaUploadSessionStatus.ABORTED
                    || target == MediaUploadSessionStatus.EXPIRED
                    || target == MediaUploadSessionStatus.FAILED);
            case INITIATED -> (target == MediaUploadSessionStatus.COMPLETING
                    || target == MediaUploadSessionStatus.ABORTED
                    || target == MediaUploadSessionStatus.EXPIRED
                    || target == MediaUploadSessionStatus.FAILED);
            case COMPLETING -> (target == MediaUploadSessionStatus.COMPLETED
                    || target == MediaUploadSessionStatus.FAILED
                    || target == MediaUploadSessionStatus.ABORTED);
            case ABORTED, EXPIRED, FAILED -> (target == MediaUploadSessionStatus.CLEANED_UP);
            case COMPLETED, CLEANED_UP -> false;
        };

        if (!valid) {
            throw new IllegalStateException(
                    String.format("Illegal persisted upload session status transition from %s to %s", current, target)
            );
        }
    }

    private void validateImmutableFields(MediaUploadSessionJpaEntity entity, MediaUploadSession session) {
        if (!Objects.equals(entity.getStorageAllocationId(), session.getStorageAllocationId().toString())) {
            throw new IllegalStateException("Cannot alter storageAllocationId on session " + entity.getId());
        }
        if (!Objects.equals(entity.getStorageProviderId(), session.getStorageProviderId().value())) {
            throw new IllegalStateException("Cannot alter storageProviderId on session " + entity.getId());
        }
        if (!Objects.equals(entity.getStorageBucketName(), session.getStorageBucketName())) {
            throw new IllegalStateException("Cannot alter storageBucketName on session " + entity.getId());
        }
        if (!Objects.equals(entity.getStorageKey(), session.getStorageKey().value())) {
            throw new IllegalStateException("Cannot alter storageKey on session " + entity.getId());
        }
        if (!Objects.equals(entity.getInitiatorPrincipalId(), session.getInitiatorPrincipalId())) {
            throw new IllegalStateException("Cannot alter initiatorPrincipalId on session " + entity.getId());
        }
        if (!Objects.equals(entity.getConsumerContext(), session.getConsumerContext())) {
            throw new IllegalStateException("Cannot alter consumerContext on session " + entity.getId());
        }
        if (!Objects.equals(entity.getTargetEntityType(), session.getTargetEntityType())) {
            throw new IllegalStateException("Cannot alter targetEntityType on session " + entity.getId());
        }
        if (!Objects.equals(entity.getTargetEntityId(), session.getTargetEntityId().toString())) {
            throw new IllegalStateException("Cannot alter targetEntityId on session " + entity.getId());
        }
        if (!Objects.equals(entity.getIdempotencyKey(), session.getIdempotencyKey())) {
            throw new IllegalStateException("Cannot alter idempotencyKey on session " + entity.getId());
        }
        if (entity.getPartSizeBytes() != session.getPartSizeBytes()) {
            throw new IllegalStateException("Cannot alter partSizeBytes on session " + entity.getId());
        }
        if (entity.getDeclaredSizeBytes() != session.getDeclaredSizeBytes()) {
            throw new IllegalStateException("Cannot alter declaredSizeBytes on session " + entity.getId());
        }
        if (entity.getExpectedPartCount() != session.getExpectedPartCount()) {
            throw new IllegalStateException("Cannot alter expectedPartCount on session " + entity.getId());
        }
    }

    private MediaUploadSession toDomain(MediaUploadSessionJpaEntity entity) {
        return MediaUploadSession.rehydrate(
                UUID.fromString(entity.getId()),
                entity.getInitiatorPrincipalId(),
                entity.getConsumerContext(),
                entity.getTargetEntityType(),
                UUID.fromString(entity.getTargetEntityId()),
                entity.getIdempotencyKey(),
                MediaType.valueOf(entity.getMediaType()),
                entity.getDeclaredMimeType(),
                entity.getDeclaredSizeBytes(),
                StorageProviderId.of(entity.getStorageProviderId()),
                entity.getStorageBucketName(),
                StorageKey.of(entity.getStorageKey()),
                UUID.fromString(entity.getStorageAllocationId()),
                entity.getProviderUploadId(),
                entity.getPartSizeBytes(),
                entity.getExpectedPartCount(),
                entity.getDeclaredContentHash(),
                entity.getOriginalFilename(),
                entity.getClientTag(),
                entity.getStatus(),
                entity.getExpiresAt(),
                entity.getLeaseOwner(),
                entity.getLeaseExpiresAt(),
                entity.getRegisteredAssetId() != null ? UUID.fromString(entity.getRegisteredAssetId()) : null,
                entity.getCleanupStatus(),
                entity.getCleanupRetryCount(),
                entity.getCleanupLastAttemptAt(),
                entity.getCleanupErrorDetail(),
                entity.getCreatedAt(),
                entity.getUpdatedAt(),
                entity.getCompletedAt(),
                entity.getPersistenceVersion(),
                entity.getManifestHash()
        );
    }
}
