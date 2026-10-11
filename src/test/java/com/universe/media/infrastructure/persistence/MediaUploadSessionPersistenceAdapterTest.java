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

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
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

    @Test
    @DisplayName("Should save new MediaUploadSession with null manifestHash")
    void shouldSaveNewSessionWithNullManifestHash() {
        UUID id = UUID.randomUUID();
        MediaUploadSession session = createSession(id);

        when(repository.findById(id.toString())).thenReturn(Optional.empty());
        when(repository.saveAndFlush(any(MediaUploadSessionJpaEntity.class))).thenAnswer(inv -> {
            MediaUploadSessionJpaEntity entity = inv.getArgument(0);
            entity.setPersistenceVersion(0L);
            return entity;
        });

        MediaUploadSession saved = adapter.save(session);

        assertThat(saved.getManifestHash()).isNull();

        ArgumentCaptor<MediaUploadSessionJpaEntity> captor = ArgumentCaptor.forClass(MediaUploadSessionJpaEntity.class);
        verify(repository).saveAndFlush(captor.capture());
        assertThat(captor.getValue().getManifestHash()).isNull();
    }

    @Test
    @DisplayName("Should save new MediaUploadSession with valid manifestHash")
    void shouldSaveNewSessionWithValidManifestHash() {
        UUID id = UUID.randomUUID();
        MediaUploadSession session = createSession(id);
        String manifestHash = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
        session.recordManifestHash(manifestHash);

        when(repository.findById(id.toString())).thenReturn(Optional.empty());
        when(repository.saveAndFlush(any(MediaUploadSessionJpaEntity.class))).thenAnswer(inv -> {
            MediaUploadSessionJpaEntity entity = inv.getArgument(0);
            entity.setPersistenceVersion(0L);
            return entity;
        });

        MediaUploadSession saved = adapter.save(session);

        assertThat(saved.getManifestHash()).isEqualTo(manifestHash);

        ArgumentCaptor<MediaUploadSessionJpaEntity> captor = ArgumentCaptor.forClass(MediaUploadSessionJpaEntity.class);
        verify(repository).saveAndFlush(captor.capture());
        assertThat(captor.getValue().getManifestHash()).isEqualTo(manifestHash);
    }

    @Test
    @DisplayName("Should preserve existing manifestHash when updating unrelated session fields")
    void shouldPreserveExistingManifestHashWhenUpdatingUnrelatedFields() {
        UUID id = UUID.randomUUID();
        String manifestHash = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

        MediaUploadSessionJpaEntity existingEntity = new MediaUploadSessionJpaEntity();
        existingEntity.setId(id.toString());
        existingEntity.setInitiatorPrincipalId("user-100");
        existingEntity.setConsumerContext("screen");
        existingEntity.setTargetEntityType("ScreenItem");
        existingEntity.setTargetEntityId(UUID.randomUUID().toString());
        existingEntity.setIdempotencyKey("idem-100");
        existingEntity.setMediaType("VIDEO");
        existingEntity.setDeclaredMimeType("video/mp4");
        existingEntity.setDeclaredSizeBytes(MediaUploadSession.FIXED_PART_SIZE_BYTES * 2);
        existingEntity.setStorageProviderId("b2");
        existingEntity.setStorageBucketName("kiemlai-video");
        existingEntity.setStorageKey(STORAGE_KEY.value());
        UUID allocId = UUID.randomUUID();
        existingEntity.setStorageAllocationId(allocId.toString());
        existingEntity.setPartSizeBytes(MediaUploadSession.FIXED_PART_SIZE_BYTES);
        existingEntity.setExpectedPartCount(2);
        existingEntity.setStatus(MediaUploadSessionStatus.INITIATED);
        existingEntity.setProviderUploadId("upload-100");
        existingEntity.setOriginalFilename("video.mp4");
        existingEntity.setClientTag("screen");
        existingEntity.setExpiresAt(Instant.now().plus(24, ChronoUnit.HOURS));
        existingEntity.setCleanupStatus(UploadSessionCleanupStatus.NONE);
        existingEntity.setCreatedAt(Instant.now());
        existingEntity.setUpdatedAt(Instant.now());
        existingEntity.setManifestHash(manifestHash);
        existingEntity.setPersistenceVersion(0L);

        when(repository.findById(id.toString())).thenReturn(Optional.of(existingEntity));
        when(repository.saveAndFlush(any(MediaUploadSessionJpaEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        // Session to save has null manifestHash (e.g. status transition without repeating manifestHash)
        MediaUploadSession sessionToSave = MediaUploadSession.rehydrate(
                id,
                "user-100",
                "screen",
                "ScreenItem",
                UUID.fromString(existingEntity.getTargetEntityId()),
                "idem-100",
                MediaType.VIDEO,
                "video/mp4",
                MediaUploadSession.FIXED_PART_SIZE_BYTES * 2,
                B2_PROVIDER,
                B2_BUCKET,
                STORAGE_KEY,
                allocId,
                "upload-100",
                MediaUploadSession.FIXED_PART_SIZE_BYTES,
                2,
                null,
                "video.mp4",
                "screen",
                MediaUploadSessionStatus.COMPLETING,
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
                0L,
                null // null manifestHash in update command
        );

        MediaUploadSession saved = adapter.save(sessionToSave);

        assertThat(existingEntity.getManifestHash()).isEqualTo(manifestHash);
        assertThat(saved.getManifestHash()).isEqualTo(manifestHash);
    }

    @Test
    @DisplayName("Should reject overwriting existing manifestHash with a different hash")
    void shouldRejectOverwritingExistingManifestHashWithDifferentHash() {
        UUID id = UUID.randomUUID();
        String originalHash = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
        String differentHash = "fedcba9876543210fedcba9876543210fedcba9876543210fedcba9876543210";

        MediaUploadSessionJpaEntity existingEntity = new MediaUploadSessionJpaEntity();
        existingEntity.setId(id.toString());
        existingEntity.setInitiatorPrincipalId("user-100");
        existingEntity.setConsumerContext("screen");
        existingEntity.setTargetEntityType("ScreenItem");
        existingEntity.setTargetEntityId(UUID.randomUUID().toString());
        existingEntity.setIdempotencyKey("idem-100");
        existingEntity.setMediaType("VIDEO");
        existingEntity.setDeclaredMimeType("video/mp4");
        existingEntity.setDeclaredSizeBytes(MediaUploadSession.FIXED_PART_SIZE_BYTES * 2);
        existingEntity.setStorageProviderId("b2");
        existingEntity.setStorageBucketName("kiemlai-video");
        existingEntity.setStorageKey(STORAGE_KEY.value());
        UUID allocId = UUID.randomUUID();
        existingEntity.setStorageAllocationId(allocId.toString());
        existingEntity.setPartSizeBytes(MediaUploadSession.FIXED_PART_SIZE_BYTES);
        existingEntity.setExpectedPartCount(2);
        existingEntity.setStatus(MediaUploadSessionStatus.INITIATED);
        existingEntity.setProviderUploadId("upload-100");
        existingEntity.setOriginalFilename("video.mp4");
        existingEntity.setClientTag("screen");
        existingEntity.setExpiresAt(Instant.now().plus(24, ChronoUnit.HOURS));
        existingEntity.setCleanupStatus(UploadSessionCleanupStatus.NONE);
        existingEntity.setCreatedAt(Instant.now());
        existingEntity.setUpdatedAt(Instant.now());
        existingEntity.setManifestHash(originalHash);
        existingEntity.setPersistenceVersion(0L);

        when(repository.findById(id.toString())).thenReturn(Optional.of(existingEntity));

        MediaUploadSession sessionToSave = MediaUploadSession.rehydrate(
                id,
                "user-100",
                "screen",
                "ScreenItem",
                UUID.fromString(existingEntity.getTargetEntityId()),
                "idem-100",
                MediaType.VIDEO,
                "video/mp4",
                MediaUploadSession.FIXED_PART_SIZE_BYTES * 2,
                B2_PROVIDER,
                B2_BUCKET,
                STORAGE_KEY,
                allocId,
                "upload-100",
                MediaUploadSession.FIXED_PART_SIZE_BYTES,
                2,
                null,
                "video.mp4",
                "screen",
                MediaUploadSessionStatus.COMPLETING,
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
                0L,
                differentHash
        );

        assertThatThrownBy(() -> adapter.save(sessionToSave))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Cannot overwrite existing manifestHash on session");
    }

    @Test
    @DisplayName("Should rehydrate entity with manifestHash in findById")
    void shouldRehydrateEntityWithManifestHashInFindById() {
        UUID id = UUID.randomUUID();
        String manifestHash = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

        MediaUploadSessionJpaEntity entity = new MediaUploadSessionJpaEntity();
        entity.setId(id.toString());
        entity.setInitiatorPrincipalId("user-100");
        entity.setConsumerContext("screen");
        entity.setTargetEntityType("ScreenItem");
        entity.setTargetEntityId(UUID.randomUUID().toString());
        entity.setIdempotencyKey("idem-100");
        entity.setMediaType("VIDEO");
        entity.setDeclaredMimeType("video/mp4");
        entity.setDeclaredSizeBytes(MediaUploadSession.FIXED_PART_SIZE_BYTES * 2);
        entity.setStorageProviderId("b2");
        entity.setStorageBucketName("kiemlai-video");
        entity.setStorageKey(STORAGE_KEY.value());
        entity.setStorageAllocationId(UUID.randomUUID().toString());
        entity.setPartSizeBytes(MediaUploadSession.FIXED_PART_SIZE_BYTES);
        entity.setExpectedPartCount(2);
        entity.setStatus(MediaUploadSessionStatus.ALLOCATED);
        entity.setExpiresAt(Instant.now().plus(24, ChronoUnit.HOURS));
        entity.setCleanupStatus(UploadSessionCleanupStatus.NONE);
        entity.setCreatedAt(Instant.now());
        entity.setUpdatedAt(Instant.now());
        entity.setOriginalFilename("video.mp4");
        entity.setClientTag("screen");
        entity.setManifestHash(manifestHash);

        when(repository.findById(id.toString())).thenReturn(Optional.of(entity));

        Optional<MediaUploadSession> found = adapter.findById(id);

        assertThat(found).isPresent();
        assertThat(found.get().getManifestHash()).isEqualTo(manifestHash);
    }

    @Test
    @DisplayName("findSessionsEligibleForCleanup delegates to repository with bounded PageRequest and maps to domain")
    void shouldFindSessionsEligibleForCleanup() {
        UUID id = UUID.randomUUID();
        MediaUploadSessionJpaEntity entity = new MediaUploadSessionJpaEntity();
        entity.setId(id.toString());
        entity.setInitiatorPrincipalId("user-100");
        entity.setConsumerContext("screen");
        entity.setTargetEntityType("ScreenItem");
        entity.setTargetEntityId(UUID.randomUUID().toString());
        entity.setIdempotencyKey("idem-100");
        entity.setMediaType("VIDEO");
        entity.setDeclaredMimeType("video/mp4");
        entity.setDeclaredSizeBytes(MediaUploadSession.FIXED_PART_SIZE_BYTES);
        entity.setStorageProviderId("b2");
        entity.setStorageBucketName("kiemlai-video");
        entity.setStorageKey(STORAGE_KEY.value());
        entity.setStorageAllocationId(UUID.randomUUID().toString());
        entity.setPartSizeBytes(MediaUploadSession.FIXED_PART_SIZE_BYTES);
        entity.setExpectedPartCount(1);
        entity.setStatus(MediaUploadSessionStatus.ABORTED);
        entity.setExpiresAt(Instant.now().plus(24, ChronoUnit.HOURS));
        entity.setCleanupStatus(UploadSessionCleanupStatus.PENDING);
        entity.setCleanupRetryCount(0);
        entity.setCreatedAt(Instant.now());
        entity.setUpdatedAt(Instant.now());
        entity.setOriginalFilename("video.mp4");

        Instant cutoff = Instant.now();
        Set<MediaUploadSessionStatus> sessionStatuses = Set.of(MediaUploadSessionStatus.ABORTED, MediaUploadSessionStatus.EXPIRED);
        Set<UploadSessionCleanupStatus> cleanupStatuses = Set.of(UploadSessionCleanupStatus.PENDING, UploadSessionCleanupStatus.FAILED);
        when(repository.findEligibleForCleanup(sessionStatuses, cleanupStatuses, 5, cutoff, PageRequest.of(0, 10)))
                .thenReturn(List.of(entity));

        List<MediaUploadSession> results = adapter.findSessionsEligibleForCleanup(sessionStatuses, cleanupStatuses, 5, cutoff, 10);

        assertThat(results).hasSize(1);
        assertThat(results.get(0).getId()).isEqualTo(id);
        assertThat(results.get(0).getCleanupStatus()).isEqualTo(UploadSessionCleanupStatus.PENDING);
        verify(repository).findEligibleForCleanup(sessionStatuses, cleanupStatuses, 5, cutoff, PageRequest.of(0, 10));
    }

    @Test
    @DisplayName("findSessionsEligibleForCleanup validates limit, retry, null parameters and rejects unsafe lifecycle statuses")
    void shouldValidateParametersForCleanupQuery() {
        Instant cutoff = Instant.now();
        Set<MediaUploadSessionStatus> safeStatuses = Set.of(MediaUploadSessionStatus.ABORTED);
        Set<UploadSessionCleanupStatus> cleanupStatuses = Set.of(UploadSessionCleanupStatus.PENDING);

        assertThatThrownBy(() -> adapter.findSessionsEligibleForCleanup(null, cleanupStatuses, 5, cutoff, 10))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("sessionStatuses");

        assertThatThrownBy(() -> adapter.findSessionsEligibleForCleanup(safeStatuses, null, 5, cutoff, 10))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("cleanupStatuses");

        assertThatThrownBy(() -> adapter.findSessionsEligibleForCleanup(safeStatuses, cleanupStatuses, 5, cutoff, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("limit must be between 1 and 100");

        assertThatThrownBy(() -> adapter.findSessionsEligibleForCleanup(safeStatuses, cleanupStatuses, 5, cutoff, -1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("limit must be between 1 and 100");

        assertThatThrownBy(() -> adapter.findSessionsEligibleForCleanup(safeStatuses, cleanupStatuses, 5, cutoff, 101))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("limit must be between 1 and 100");

        assertThatThrownBy(() -> adapter.findSessionsEligibleForCleanup(safeStatuses, cleanupStatuses, 5, cutoff, Integer.MAX_VALUE))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("limit must be between 1 and 100");

        assertThatThrownBy(() -> adapter.findSessionsEligibleForCleanup(safeStatuses, cleanupStatuses, -1, cutoff, 10))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maxRetryCount cannot be negative");

        // Fail-closed checks on unapproved lifecycle statuses
        assertThatThrownBy(() -> adapter.findSessionsEligibleForCleanup(
                Set.of(MediaUploadSessionStatus.COMPLETING), cleanupStatuses, 5, cutoff, 10))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unapproved lifecycle statuses");

        assertThatThrownBy(() -> adapter.findSessionsEligibleForCleanup(
                Set.of(MediaUploadSessionStatus.COMPLETED), cleanupStatuses, 5, cutoff, 10))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unapproved lifecycle statuses");

        assertThatThrownBy(() -> adapter.findSessionsEligibleForCleanup(
                Set.of(MediaUploadSessionStatus.CLEANED_UP), cleanupStatuses, 5, cutoff, 10))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unapproved lifecycle statuses");

        assertThatThrownBy(() -> adapter.findSessionsEligibleForCleanup(
                Set.of(MediaUploadSessionStatus.FAILED), cleanupStatuses, 5, cutoff, 10))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unapproved lifecycle statuses");

        assertThatThrownBy(() -> adapter.findSessionsEligibleForCleanup(
                Set.of(MediaUploadSessionStatus.INITIATED), cleanupStatuses, 5, cutoff, 10))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unapproved lifecycle statuses");
    }

    @Test
    @DisplayName("findSessionsEligibleForCleanup enforces approved cleanup-status sets and rejects unapproved cleanup statuses fail-closed")
    void shouldValidateApprovedAndRejectedCleanupStatuses() {
        Instant cutoff = Instant.now();
        Set<MediaUploadSessionStatus> safeLifecycle = Set.of(MediaUploadSessionStatus.ABORTED);

        // Rejected unapproved cleanup statuses: NONE, COMPLETED, IN_PROGRESS, QUARANTINED
        assertThatThrownBy(() -> adapter.findSessionsEligibleForCleanup(
                safeLifecycle, Set.of(UploadSessionCleanupStatus.NONE), 5, cutoff, 10))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unapproved cleanup statuses");

        assertThatThrownBy(() -> adapter.findSessionsEligibleForCleanup(
                safeLifecycle, Set.of(UploadSessionCleanupStatus.COMPLETED), 5, cutoff, 10))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unapproved cleanup statuses");

        assertThatThrownBy(() -> adapter.findSessionsEligibleForCleanup(
                safeLifecycle, Set.of(UploadSessionCleanupStatus.IN_PROGRESS), 5, cutoff, 10))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unapproved cleanup statuses");

        assertThatThrownBy(() -> adapter.findSessionsEligibleForCleanup(
                safeLifecycle, Set.of(UploadSessionCleanupStatus.QUARANTINED), 5, cutoff, 10))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unapproved cleanup statuses");

        // Mixed set containing an unapproved status also fails fail-closed
        assertThatThrownBy(() -> adapter.findSessionsEligibleForCleanup(
                safeLifecycle, Set.of(UploadSessionCleanupStatus.PENDING, UploadSessionCleanupStatus.COMPLETED), 5, cutoff, 10))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unapproved cleanup statuses");

        assertThatThrownBy(() -> adapter.findSessionsEligibleForCleanup(
                safeLifecycle, Set.of(UploadSessionCleanupStatus.PENDING, UploadSessionCleanupStatus.QUARANTINED), 5, cutoff, 10))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unapproved cleanup statuses");


        // Approved cleanup statuses: PENDING alone, FAILED alone, or both PENDING and FAILED
        when(repository.findEligibleForCleanup(any(), any(), anyInt(), any(), any()))
                .thenReturn(List.of());

        assertThat(adapter.findSessionsEligibleForCleanup(
                safeLifecycle, Set.of(UploadSessionCleanupStatus.PENDING), 5, cutoff, 10))
                .isNotNull();

        assertThat(adapter.findSessionsEligibleForCleanup(
                safeLifecycle, Set.of(UploadSessionCleanupStatus.FAILED), 5, cutoff, 10))
                .isNotNull();

        assertThat(adapter.findSessionsEligibleForCleanup(
                safeLifecycle, Set.of(UploadSessionCleanupStatus.PENDING, UploadSessionCleanupStatus.FAILED), 5, cutoff, 10))
                .isNotNull();

        // Default overload uses approved statuses
        assertThat(adapter.findSessionsEligibleForCleanup(5, cutoff, 10))
                .isNotNull();
    }

    @Test
    @DisplayName("findExpiredSessions delegates to repository with bounded PageRequest and maps to domain")
    void shouldFindExpiredSessions() {
        UUID id = UUID.randomUUID();
        MediaUploadSessionJpaEntity entity = new MediaUploadSessionJpaEntity();
        entity.setId(id.toString());
        entity.setInitiatorPrincipalId("user-100");
        entity.setConsumerContext("screen");
        entity.setTargetEntityType("ScreenItem");
        entity.setTargetEntityId(UUID.randomUUID().toString());
        entity.setIdempotencyKey("idem-100");
        entity.setMediaType("VIDEO");
        entity.setDeclaredMimeType("video/mp4");
        entity.setDeclaredSizeBytes(MediaUploadSession.FIXED_PART_SIZE_BYTES);
        entity.setStorageProviderId("b2");
        entity.setStorageBucketName("kiemlai-video");
        entity.setStorageKey(STORAGE_KEY.value());
        entity.setStorageAllocationId(UUID.randomUUID().toString());
        entity.setPartSizeBytes(MediaUploadSession.FIXED_PART_SIZE_BYTES);
        entity.setExpectedPartCount(1);
        entity.setStatus(MediaUploadSessionStatus.INITIATED);
        entity.setProviderUploadId("upload-expired-123");
        entity.setExpiresAt(Instant.now().minus(1, ChronoUnit.HOURS));
        entity.setCleanupStatus(UploadSessionCleanupStatus.NONE);
        entity.setCreatedAt(Instant.now().minus(25, ChronoUnit.HOURS));
        entity.setUpdatedAt(Instant.now().minus(25, ChronoUnit.HOURS));
        entity.setOriginalFilename("video.mp4");

        Instant now = Instant.now();
        Set<MediaUploadSessionStatus> statuses = Set.of(MediaUploadSessionStatus.ALLOCATED, MediaUploadSessionStatus.INITIATED);
        when(repository.findExpired(statuses, now, PageRequest.of(0, 20)))
                .thenReturn(List.of(entity));

        List<MediaUploadSession> results = adapter.findExpiredSessions(statuses, now, 20);

        assertThat(results).hasSize(1);
        assertThat(results.get(0).getId()).isEqualTo(id);
        assertThat(results.get(0).getStatus()).isEqualTo(MediaUploadSessionStatus.INITIATED);
        verify(repository).findExpired(statuses, now, PageRequest.of(0, 20));
    }

    @Test
    @DisplayName("findExpiredSessions validates limit bounds and null parameters")
    void shouldValidateParametersForExpiredQuery() {
        Instant now = Instant.now();
        Set<MediaUploadSessionStatus> statuses = Set.of(MediaUploadSessionStatus.ALLOCATED);

        assertThatThrownBy(() -> adapter.findExpiredSessions(null, now, 10))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("statuses");

        assertThatThrownBy(() -> adapter.findExpiredSessions(statuses, null, 10))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("now");

        assertThatThrownBy(() -> adapter.findExpiredSessions(statuses, now, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("limit must be between 1 and 100");

        assertThatThrownBy(() -> adapter.findExpiredSessions(statuses, now, -1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("limit must be between 1 and 100");

        assertThatThrownBy(() -> adapter.findExpiredSessions(statuses, now, 101))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("limit must be between 1 and 100");

        assertThatThrownBy(() -> adapter.findExpiredSessions(statuses, now, Integer.MAX_VALUE))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("limit must be between 1 and 100");
    }

    @Test
    @DisplayName("findSessionsWithExpiredLeases delegates to repository with bounded PageRequest and maps to domain")
    void shouldFindSessionsWithExpiredLeases() {
        UUID id = UUID.randomUUID();
        MediaUploadSessionJpaEntity entity = new MediaUploadSessionJpaEntity();
        entity.setId(id.toString());
        entity.setInitiatorPrincipalId("user-100");
        entity.setConsumerContext("screen");
        entity.setTargetEntityType("ScreenItem");
        entity.setTargetEntityId(UUID.randomUUID().toString());
        entity.setIdempotencyKey("idem-100");
        entity.setMediaType("VIDEO");
        entity.setDeclaredMimeType("video/mp4");
        entity.setDeclaredSizeBytes(MediaUploadSession.FIXED_PART_SIZE_BYTES);
        entity.setStorageProviderId("b2");
        entity.setStorageBucketName("kiemlai-video");
        entity.setStorageKey(STORAGE_KEY.value());
        entity.setStorageAllocationId(UUID.randomUUID().toString());
        entity.setPartSizeBytes(MediaUploadSession.FIXED_PART_SIZE_BYTES);
        entity.setExpectedPartCount(1);
        entity.setStatus(MediaUploadSessionStatus.COMPLETING);
        entity.setProviderUploadId("upload-lease-123");
        entity.setExpiresAt(Instant.now().plus(24, ChronoUnit.HOURS));
        entity.setLeaseOwner("COMPLETION_CLAIM:stale-token");
        entity.setLeaseExpiresAt(Instant.now().minus(10, ChronoUnit.MINUTES));
        entity.setDeclaredContentHash("0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef");
        entity.setCleanupStatus(UploadSessionCleanupStatus.NONE);
        entity.setCreatedAt(Instant.now().minus(1, ChronoUnit.HOURS));
        entity.setUpdatedAt(Instant.now().minus(1, ChronoUnit.HOURS));
        entity.setOriginalFilename("video.mp4");

        Instant now = Instant.now();
        Set<MediaUploadSessionStatus> statuses = Set.of(MediaUploadSessionStatus.ALLOCATED, MediaUploadSessionStatus.COMPLETING);
        when(repository.findWithExpiredLeases(statuses, now, PageRequest.of(0, 15)))
                .thenReturn(List.of(entity));

        List<MediaUploadSession> results = adapter.findSessionsWithExpiredLeases(statuses, now, 15);

        assertThat(results).hasSize(1);
        assertThat(results.get(0).getId()).isEqualTo(id);
        assertThat(results.get(0).getLeaseOwner()).isEqualTo("COMPLETION_CLAIM:stale-token");
        verify(repository).findWithExpiredLeases(statuses, now, PageRequest.of(0, 15));
    }

    @Test
    @DisplayName("findSessionsWithExpiredLeases validates limit bounds and null parameters")
    void shouldValidateParametersForExpiredLeasesQuery() {
        Instant now = Instant.now();
        Set<MediaUploadSessionStatus> statuses = Set.of(MediaUploadSessionStatus.COMPLETING);

        assertThatThrownBy(() -> adapter.findSessionsWithExpiredLeases(null, now, 10))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("statuses");

        assertThatThrownBy(() -> adapter.findSessionsWithExpiredLeases(statuses, null, 10))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("now");

        assertThatThrownBy(() -> adapter.findSessionsWithExpiredLeases(statuses, now, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("limit must be between 1 and 100");

        assertThatThrownBy(() -> adapter.findSessionsWithExpiredLeases(statuses, now, -1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("limit must be between 1 and 100");

        assertThatThrownBy(() -> adapter.findSessionsWithExpiredLeases(statuses, now, 101))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("limit must be between 1 and 100");

        assertThatThrownBy(() -> adapter.findSessionsWithExpiredLeases(statuses, now, Integer.MAX_VALUE))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("limit must be between 1 and 100");
    }

    @Test
    @DisplayName("Should persist and reload QUARANTINED session with quarantine reason")
    void shouldPersistAndReloadQuarantinedSession() {
        UUID id = UUID.randomUUID();
        Instant now = Instant.now();
        String claimToken = MediaUploadSession.CLEANUP_CLAIM_PREFIX + UUID.randomUUID();
        Instant leaseExpiresAt = now.plus(Duration.ofMinutes(5));

        // Create rehydrated session in IN_PROGRESS state and transition to QUARANTINED
        MediaUploadSession session = MediaUploadSession.rehydrate(
                id,
                "user-100",
                "screen",
                "ScreenItem",
                UUID.randomUUID(),
                "idem-key-quarantine",
                MediaType.VIDEO,
                "video/mp4",
                MediaUploadSession.FIXED_PART_SIZE_BYTES * 2,
                B2_PROVIDER,
                B2_BUCKET,
                STORAGE_KEY,
                UUID.randomUUID(),
                "provider-up-123",
                MediaUploadSession.FIXED_PART_SIZE_BYTES,
                2,
                null,
                "video.mp4",
                "screen",
                MediaUploadSessionStatus.ABORTED,
                now.plus(24, ChronoUnit.HOURS),
                claimToken,
                leaseExpiresAt,
                null,
                UploadSessionCleanupStatus.IN_PROGRESS,
                1,
                now.minus(1, ChronoUnit.HOURS),
                null,
                now.minus(2, ChronoUnit.HOURS),
                now.minus(1, ChronoUnit.HOURS),
                null,
                0L,
                null
        );

        session.markCleanupQuarantined(claimToken, "Safety halt: Object detected at storage key", now);
        assertThat(session.isQuarantined()).isTrue();
        assertThat(session.getCleanupStatus()).isEqualTo(UploadSessionCleanupStatus.QUARANTINED);
        assertThat(session.getCleanupErrorDetail()).isEqualTo("Safety halt: Object detected at storage key");
        assertThat(session.getLeaseOwner()).isNull();

        when(repository.findById(id.toString())).thenReturn(Optional.empty());
        when(repository.saveAndFlush(any(MediaUploadSessionJpaEntity.class))).thenAnswer(inv -> {
            MediaUploadSessionJpaEntity entity = inv.getArgument(0);
            entity.setPersistenceVersion(1L);
            return entity;
        });

        MediaUploadSession saved = adapter.save(session);
        assertThat(saved.getCleanupStatus()).isEqualTo(UploadSessionCleanupStatus.QUARANTINED);
        assertThat(saved.getCleanupErrorDetail()).isEqualTo("Safety halt: Object detected at storage key");

        ArgumentCaptor<MediaUploadSessionJpaEntity> captor = ArgumentCaptor.forClass(MediaUploadSessionJpaEntity.class);
        verify(repository).saveAndFlush(captor.capture());
        MediaUploadSessionJpaEntity savedEntity = captor.getValue();
        assertThat(savedEntity.getCleanupStatus()).isEqualTo(UploadSessionCleanupStatus.QUARANTINED);
        assertThat(savedEntity.getCleanupErrorDetail()).isEqualTo("Safety halt: Object detected at storage key");
    }
}
