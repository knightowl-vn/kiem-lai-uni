package com.universe.media.application.ports;

import com.universe.media.domain.MediaUploadSession;
import com.universe.media.domain.StorageKey;
import com.universe.media.domain.StorageProviderId;

import java.util.Optional;
import java.util.UUID;

/**
 * Output port for persistent management of multipart media upload sessions.
 */
public interface MediaUploadSessionRepositoryPort {

    Optional<MediaUploadSession> findById(UUID id);

    Optional<MediaUploadSession> findByIdempotencyKey(String initiatorPrincipalId, String idempotencyKey);

    Optional<MediaUploadSession> findByStorageAllocationId(UUID storageAllocationId);

    Optional<MediaUploadSession> findByStorageLocation(StorageProviderId providerId, StorageKey storageKey);

    MediaUploadSession save(MediaUploadSession session);
}
