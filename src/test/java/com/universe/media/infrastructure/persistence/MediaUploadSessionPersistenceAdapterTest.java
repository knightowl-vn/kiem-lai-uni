package com.universe.media.infrastructure.persistence;

import com.universe.media.domain.MediaUploadSession;
import com.universe.media.domain.MediaUploadSessionStatus;
import com.universe.media.domain.MediaType;
import com.universe.media.domain.StorageKey;
import com.universe.media.domain.StorageProviderId;
import com.universe.media.domain.UploadSessionCleanupStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("MediaUploadSessionPersistenceAdapter Unit Tests")
class MediaUploadSessionPersistenceAdapterTest {

    @Mock
    private SpringDataMediaUploadSessionJpaRepository repository;

    private MediaUploadSessionPersistenceAdapter adapter;

    private static final StorageProviderId B2_PROVIDER = StorageProviderId.of("b2");
    private static final String B2_BUCKET = "kiemlai-video";
    private static final StorageKey STORAGE_KEY = StorageKey.of("media/video/uuid.mp4");
    private static final String HASH = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

    @BeforeEach
    void setUp() {
        adapter = new MediaUploadSessionPersistenceAdapter(repository);
    }

    private MediaUploadSession createSession(UUID id) {
        Instant now = Instant.now();
        return MediaUploadSession.create(
                id,
                "user-100",
                "screen",
                "ScreenItem",
                UUID.randomUUID(),
                "idem-key-100",
                MediaUploadSession.FIXED_PART_SIZE_BYTES * 4,
                B2_PROVIDER,
                B2_BUCKET,
                STORAGE_KEY,
                UUID.randomUUID(),
                "video.mp4",
                "screen",
                now.plus(24, ChronoUnit.HOURS),
                now
        );
    }

    @Test
    @DisplayName("Should save new MediaUploadSession when not existing")
    void shouldSaveNewSession() {
        UUID id = UUID.randomUUID();
        MediaUploadSession session = createSession(id);

        when(repository.findById(id.toString())).thenReturn(Optional.empty());
        when(repository.saveAndFlush(any(MediaUploadSessionJpaEntity.class))).thenAnswer(inv -> {
            MediaUploadSessionJpaEntity entity = inv.getArgument(0);
            entity.setPersistenceVersion(0L);
            return entity;
        });

        MediaUploadSession saved = adapter.save(session);

        assertThat(saved).isNotNull();
        assertThat(saved.getId()).isEqualTo(id);
        assertThat(saved.getInitiatorPrincipalId()).isEqualTo("user-100");
        assertThat(saved.getIdempotencyKey()).isEqualTo("idem-key-100");
        assertThat(saved.getExpectedPartCount()).isEqualTo(4);

        ArgumentCaptor<MediaUploadSessionJpaEntity> captor = ArgumentCaptor.forClass(MediaUploadSessionJpaEntity.class);
        verify(repository).saveAndFlush(captor.capture());
        MediaUploadSessionJpaEntity savedEntity = captor.getValue();
        assertThat(savedEntity.getId()).isEqualTo(id.toString());
        assertThat(savedEntity.getStorageProviderId()).isEqualTo("b2");
        assertThat(savedEntity.getStorageBucketName()).isEqualTo("kiemlai-video");
        assertThat(savedEntity.getStorageKey()).isEqualTo(STORAGE_KEY.value());
        assertThat(savedEntity.getStatus()).isEqualTo(MediaUploadSessionStatus.ALLOCATED);
    }

    @Test
    @DisplayName("Should update existing MediaUploadSession preserving immutable location")
    void shouldUpdateExistingSession() {
        UUID id = UUID.randomUUID();
        UUID targetEntityId = UUID.randomUUID();
        UUID allocId = UUID.randomUUID();
        MediaUploadSession session = MediaUploadSession.rehydrate(
                id,
                "user-100",
                "screen",
                "ScreenItem",
                targetEntityId,
                "idem-key-100",
                MediaType.VIDEO,
                "video/mp4",
                MediaUploadSession.FIXED_PART_SIZE_BYTES * 2,
                B2_PROVIDER,
                B2_BUCKET,
                STORAGE_KEY,
                allocId,
                "upload-b2-id",
                MediaUploadSession.FIXED_PART_SIZE_BYTES,
                2,
                null,
                "video.mp4",
                "screen",
                MediaUploadSessionStatus.INITIATED,
                Instant.now().plus(24, ChronoUnit.HOURS),
                null,
                null,
                null,
                UploadSessionCleanupStatus.NONE,
                0,
                null,
                null,
                Instant.now().minus(1, ChronoUnit.HOURS),
                Instant.now(),
                null,
                0L
        );

        MediaUploadSessionJpaEntity existingEntity = new MediaUploadSessionJpaEntity();
        existingEntity.setId(id.toString());
        existingEntity.setInitiatorPrincipalId("user-100");
        existingEntity.setIdempotencyKey("idem-key-100");
        existingEntity.setConsumerContext("screen");
        existingEntity.setTargetEntityType("ScreenItem");
        existingEntity.setTargetEntityId(targetEntityId.toString());
        existingEntity.setMediaType(MediaType.VIDEO.name());
        existingEntity.setDeclaredMimeType("video/mp4");
        existingEntity.setDeclaredSizeBytes(MediaUploadSession.FIXED_PART_SIZE_BYTES * 2);
        existingEntity.setStorageAllocationId(allocId.toString());
        existingEntity.setStorageProviderId("b2");
        existingEntity.setStorageBucketName("kiemlai-video");
        existingEntity.setStorageKey(STORAGE_KEY.value());
        existingEntity.setPartSizeBytes(MediaUploadSession.FIXED_PART_SIZE_BYTES);
        existingEntity.setExpectedPartCount(2);
        existingEntity.setStatus(MediaUploadSessionStatus.ALLOCATED);
        existingEntity.setOriginalFilename("video.mp4");
        existingEntity.setCleanupStatus(UploadSessionCleanupStatus.NONE);
        existingEntity.setCreatedAt(Instant.now().minus(1, ChronoUnit.HOURS));
        existingEntity.setUpdatedAt(Instant.now().minus(1, ChronoUnit.HOURS));
        existingEntity.setExpiresAt(Instant.now().plus(24, ChronoUnit.HOURS));
        existingEntity.setPersistenceVersion(0L);

        when(repository.findById(id.toString())).thenReturn(Optional.of(existingEntity));
        when(repository.saveAndFlush(any(MediaUploadSessionJpaEntity.class))).thenAnswer(inv -> {
            MediaUploadSessionJpaEntity entity = inv.getArgument(0);
            entity.setPersistenceVersion(1L);
            return entity;
        });

        MediaUploadSession updated = adapter.save(session);

        assertThat(updated.getStatus()).isEqualTo(MediaUploadSessionStatus.INITIATED);
        assertThat(updated.getProviderUploadId()).isEqualTo("upload-b2-id");
        assertThat(existingEntity.getStatus()).isEqualTo(MediaUploadSessionStatus.INITIATED);
        assertThat(existingEntity.getProviderUploadId()).isEqualTo("upload-b2-id");
    }

    @Test
    @DisplayName("Should detect optimistic locking failure if version mismatch")
    void shouldDetectOptimisticLockingFailure() {
        UUID id = UUID.randomUUID();
        MediaUploadSession session = MediaUploadSession.rehydrate(
                id,
                "user-100",
                "screen",
                "ScreenItem",
                UUID.randomUUID(),
                "idem-key-100",
                MediaType.VIDEO,
                "video/mp4",
                MediaUploadSession.FIXED_PART_SIZE_BYTES * 2,
                B2_PROVIDER,
                B2_BUCKET,
                STORAGE_KEY,
                UUID.randomUUID(),
                null,
                MediaUploadSession.FIXED_PART_SIZE_BYTES,
                2,
                null,
                "video.mp4",
                "screen",
                MediaUploadSessionStatus.ALLOCATED,
                Instant.now().plus(24, ChronoUnit.HOURS),
                null,
                null,
                null,
                UploadSessionCleanupStatus.NONE,
                0,
                null,
                null,
                Instant.now().minus(1, ChronoUnit.HOURS),
                Instant.now(),
                null,
                0L
        );

        MediaUploadSessionJpaEntity concurrentEntity = new MediaUploadSessionJpaEntity();
        concurrentEntity.setId(id.toString());
        concurrentEntity.setPersistenceVersion(1L); // concurrent modification changed version to 1

        when(repository.findById(id.toString())).thenReturn(Optional.of(concurrentEntity));

        assertThatThrownBy(() -> adapter.save(session))
                .isInstanceOf(OptimisticLockingFailureException.class)
                .hasMessageContaining("concurrently modified");
    }

    @Test
    @DisplayName("Should propagate DataIntegrityViolationException on constraint violation")
    void shouldPropagateDataIntegrityViolationException() {
        UUID id = UUID.randomUUID();
        MediaUploadSession session = createSession(id);

        when(repository.findById(id.toString())).thenReturn(Optional.empty());
        when(repository.saveAndFlush(any(MediaUploadSessionJpaEntity.class)))
                .thenThrow(new DataIntegrityViolationException("Duplicate key"));

        assertThatThrownBy(() -> adapter.save(session))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("Should find session by ID and reconstitute all fields")
    void shouldFindById() {
        UUID id = UUID.randomUUID();
        UUID assetId = UUID.randomUUID();
        UUID targetId = UUID.randomUUID();
        UUID allocId = UUID.randomUUID();

        MediaUploadSessionJpaEntity entity = new MediaUploadSessionJpaEntity();
        entity.setId(id.toString());
        entity.setInitiatorPrincipalId("user-100");
        entity.setIdempotencyKey("idem-key-100");
        entity.setConsumerContext("screen");
        entity.setTargetEntityType("ScreenItem");
        entity.setTargetEntityId(targetId.toString());
        entity.setMediaType(MediaType.VIDEO.name());
        entity.setDeclaredMimeType("video/mp4");
        entity.setPartSizeBytes(MediaUploadSession.FIXED_PART_SIZE_BYTES);
        entity.setDeclaredSizeBytes(MediaUploadSession.FIXED_PART_SIZE_BYTES * 2);
        entity.setExpectedPartCount(2);
        entity.setStorageAllocationId(allocId.toString());
        entity.setStorageProviderId("b2");
        entity.setStorageBucketName("kiemlai-video");
        entity.setStorageKey(STORAGE_KEY.value());
        entity.setStatus(MediaUploadSessionStatus.COMPLETED);
        entity.setProviderUploadId("upload-b2");
        entity.setDeclaredContentHash(HASH);
        entity.setRegisteredAssetId(assetId.toString());
        entity.setCleanupStatus(UploadSessionCleanupStatus.NONE);
        entity.setOriginalFilename("video.mp4");
        entity.setCreatedAt(Instant.now().minus(1, ChronoUnit.HOURS));
        entity.setUpdatedAt(Instant.now());
        entity.setExpiresAt(Instant.now().plus(23, ChronoUnit.HOURS));
        entity.setCompletedAt(Instant.now());
        entity.setPersistenceVersion(2L);

        when(repository.findById(id.toString())).thenReturn(Optional.of(entity));

        Optional<MediaUploadSession> found = adapter.findById(id);

        assertThat(found).isPresent();
        MediaUploadSession s = found.get();
        assertThat(s.getId()).isEqualTo(id);
        assertThat(s.getInitiatorPrincipalId()).isEqualTo("user-100");
        assertThat(s.getStatus()).isEqualTo(MediaUploadSessionStatus.COMPLETED);
        assertThat(s.getDeclaredContentHash()).isEqualTo(HASH);
        assertThat(s.getRegisteredAssetId()).isEqualTo(assetId);
        assertThat(s.getCompletedAt()).isNotNull();
    }

    @Test
    @DisplayName("Should reject illegal status transition from terminal COMPLETED to ABORTED")
    void shouldRejectIllegalStatusTransitionFromCompleted() {
        UUID id = UUID.randomUUID();
        MediaUploadSessionJpaEntity existingEntity = new MediaUploadSessionJpaEntity();
        existingEntity.setId(id.toString());
        existingEntity.setPersistenceVersion(0L);
        existingEntity.setStatus(MediaUploadSessionStatus.COMPLETED);
        existingEntity.setStorageAllocationId(UUID.randomUUID().toString());
        existingEntity.setStorageProviderId("b2");
        existingEntity.setStorageBucketName("kiemlai-video");
        existingEntity.setStorageKey(STORAGE_KEY.value());
        existingEntity.setPartSizeBytes(MediaUploadSession.FIXED_PART_SIZE_BYTES);
        existingEntity.setExpectedPartCount(2);

        when(repository.findById(id.toString())).thenReturn(Optional.of(existingEntity));

        MediaUploadSession sessionToSave = MediaUploadSession.rehydrate(
                id,
                "user-100",
                "screen",
                "ScreenItem",
                UUID.randomUUID(),
                "idem-1",
                MediaType.VIDEO,
                "video/mp4",
                MediaUploadSession.FIXED_PART_SIZE_BYTES * 2,
                B2_PROVIDER,
                B2_BUCKET,
                STORAGE_KEY,
                UUID.fromString(existingEntity.getStorageAllocationId()),
                "upload-1",
                MediaUploadSession.FIXED_PART_SIZE_BYTES,
                2,
                null,
                "video.mp4",
                "screen",
                MediaUploadSessionStatus.ABORTED, // invalid transition!
                Instant.now().plus(24, ChronoUnit.HOURS),
                null,
                null,
                null,
                UploadSessionCleanupStatus.NONE,
                0,
                null,
                null,
                Instant.now().minus(1, ChronoUnit.HOURS),
                Instant.now(),
                null,
                0L
        );

        assertThatThrownBy(() -> adapter.save(sessionToSave))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Cannot transition COMPLETED upload session to ABORTED");
    }

    @Test
    @DisplayName("Should reject reassignment of once-set providerUploadId")
    void shouldRejectProviderUploadIdReassignment() {
        UUID id = UUID.randomUUID();
        MediaUploadSessionJpaEntity existingEntity = new MediaUploadSessionJpaEntity();
        existingEntity.setId(id.toString());
        existingEntity.setPersistenceVersion(0L);
        existingEntity.setStatus(MediaUploadSessionStatus.INITIATED);
        existingEntity.setProviderUploadId("original-provider-upload-id");
        existingEntity.setStorageAllocationId(UUID.randomUUID().toString());
        existingEntity.setStorageProviderId("b2");
        existingEntity.setStorageBucketName("kiemlai-video");
        existingEntity.setStorageKey(STORAGE_KEY.value());
        existingEntity.setPartSizeBytes(MediaUploadSession.FIXED_PART_SIZE_BYTES);
        existingEntity.setExpectedPartCount(2);

        when(repository.findById(id.toString())).thenReturn(Optional.of(existingEntity));

        MediaUploadSession sessionToSave = MediaUploadSession.rehydrate(
                id,
                "user-100",
                "screen",
                "ScreenItem",
                UUID.randomUUID(),
                "idem-1",
                MediaType.VIDEO,
                "video/mp4",
                MediaUploadSession.FIXED_PART_SIZE_BYTES * 2,
                B2_PROVIDER,
                B2_BUCKET,
                STORAGE_KEY,
                UUID.fromString(existingEntity.getStorageAllocationId()),
                "new-different-provider-upload-id", // illegal reassignment!
                MediaUploadSession.FIXED_PART_SIZE_BYTES,
                2,
                null,
                "video.mp4",
                "screen",
                MediaUploadSessionStatus.INITIATED,
                Instant.now().plus(24, ChronoUnit.HOURS),
                null,
                null,
                null,
                UploadSessionCleanupStatus.NONE,
                0,
                null,
                null,
                Instant.now().minus(1, ChronoUnit.HOURS),
                Instant.now(),
                null,
                0L
        );

        assertThatThrownBy(() -> adapter.save(sessionToSave))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Cannot overwrite existing providerUploadId");
    }

    @Test
    @DisplayName("Should reject immutable field tampering (e.g. storage allocation ID mismatch)")
    void shouldRejectImmutableFieldTampering() {
        UUID id = UUID.randomUUID();
        UUID alloc1 = UUID.randomUUID();
        UUID alloc2 = UUID.randomUUID();

        MediaUploadSessionJpaEntity existingEntity = new MediaUploadSessionJpaEntity();
        existingEntity.setId(id.toString());
        existingEntity.setPersistenceVersion(0L);
        existingEntity.setStatus(MediaUploadSessionStatus.ALLOCATED);
        existingEntity.setStorageAllocationId(alloc1.toString());
        existingEntity.setStorageProviderId("b2");
        existingEntity.setStorageBucketName("kiemlai-video");
        existingEntity.setStorageKey(STORAGE_KEY.value());
        existingEntity.setPartSizeBytes(MediaUploadSession.FIXED_PART_SIZE_BYTES);
        existingEntity.setExpectedPartCount(2);

        when(repository.findById(id.toString())).thenReturn(Optional.of(existingEntity));

        MediaUploadSession sessionToSave = MediaUploadSession.rehydrate(
                id,
                "user-100",
                "screen",
                "ScreenItem",
                UUID.randomUUID(),
                "idem-1",
                MediaType.VIDEO,
                "video/mp4",
                MediaUploadSession.FIXED_PART_SIZE_BYTES * 2,
                B2_PROVIDER,
                B2_BUCKET,
                STORAGE_KEY,
                alloc2, // mismatch!
                null,
                MediaUploadSession.FIXED_PART_SIZE_BYTES,
                2,
                null,
                "video.mp4",
                "screen",
                MediaUploadSessionStatus.ALLOCATED,
                Instant.now().plus(24, ChronoUnit.HOURS),
                null,
                null,
                null,
                UploadSessionCleanupStatus.NONE,
                0,
                null,
                null,
                Instant.now().minus(1, ChronoUnit.HOURS),
                Instant.now(),
                null,
                0L
        );

        assertThatThrownBy(() -> adapter.save(sessionToSave))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Cannot alter storageAllocationId on session");
    }
}
