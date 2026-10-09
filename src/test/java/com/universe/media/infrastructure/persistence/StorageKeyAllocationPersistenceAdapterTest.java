package com.universe.media.infrastructure.persistence;

import com.universe.media.application.exceptions.DuplicateStorageLocationException;
import com.universe.media.domain.StorageKey;
import com.universe.media.domain.StorageKeyAllocation;
import com.universe.media.domain.StorageKeyAllocationStatus;
import com.universe.media.domain.StorageKeyAllocationType;
import com.universe.media.domain.StorageProviderId;
import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import java.sql.SQLException;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("StorageKeyAllocationPersistenceAdapter Unit Tests")
class StorageKeyAllocationPersistenceAdapterTest {

    @Mock
    private SpringDataStorageKeyAllocationJpaRepository repository;

    private StorageKeyAllocationPersistenceAdapter adapter;

    private final UUID id = UUID.randomUUID();
    private final StorageProviderId providerId = StorageProviderId.of("b2");
    private final StorageKey key = StorageKey.of("objects/video_001.mp4");
    private final UUID sessionId = UUID.randomUUID();
    private final Instant now = Instant.parse("2026-10-08T12:00:00Z");

    @BeforeEach
    void setUp() {
        adapter = new StorageKeyAllocationPersistenceAdapter(repository);
    }

    @Test
    @DisplayName("Should successfully insert new StorageKeyAllocation when not previously existing")
    void shouldInsertNewAllocationWhenNotExisting() {
        StorageKeyAllocation allocation = StorageKeyAllocation.reserve(
                id, providerId, "bucket-1", key, StorageKeyAllocationType.UPLOAD_SESSION, sessionId, now
        );

        when(repository.findById(id.toString())).thenReturn(Optional.empty());

        StorageKeyAllocationJpaEntity entity = new StorageKeyAllocationJpaEntity();
        entity.setId(id.toString());
        entity.setStorageProviderId(providerId.value());
        entity.setStorageBucketName("bucket-1");
        entity.setStorageKey(key.value());
        entity.setAllocationStatus(StorageKeyAllocationStatus.RESERVED);
        entity.setAllocatedByType(StorageKeyAllocationType.UPLOAD_SESSION);
        entity.setAllocatedById(sessionId.toString());
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);
        entity.setPersistenceVersion(0L);

        when(repository.saveAndFlush(any(StorageKeyAllocationJpaEntity.class))).thenReturn(entity);

        StorageKeyAllocation saved = adapter.save(allocation);

        assertThat(saved.getId()).isEqualTo(id);
        assertThat(saved.getProviderId()).isEqualTo(providerId);
        assertThat(saved.getBucketName()).isEqualTo("bucket-1");
        assertThat(saved.getStorageKey()).isEqualTo(key);
        assertThat(saved.getStatus()).isEqualTo(StorageKeyAllocationStatus.RESERVED);
        assertThat(saved.getPersistenceVersion()).isEqualTo(0L);
        verify(repository).findById(id.toString());
        verify(repository).saveAndFlush(any(StorageKeyAllocationJpaEntity.class));
    }

    @Test
    @DisplayName("Should update existing managed JPA entity and preserve immutable fields")
    void shouldUpdateExistingManagedEntityAndPreserveImmutableFields() {
        StorageKeyAllocationJpaEntity existing = new StorageKeyAllocationJpaEntity();
        existing.setId(id.toString());
        existing.setStorageProviderId(providerId.value());
        existing.setStorageBucketName("bucket-1");
        existing.setStorageKey(key.value());
        existing.setAllocationStatus(StorageKeyAllocationStatus.RESERVED);
        existing.setAllocatedByType(StorageKeyAllocationType.UPLOAD_SESSION);
        existing.setAllocatedById(sessionId.toString());
        existing.setCreatedAt(now);
        existing.setUpdatedAt(now);
        existing.setPersistenceVersion(0L);

        when(repository.findById(id.toString())).thenReturn(Optional.of(existing));

        // Create domain object representing committed state
        UUID targetVersionId = UUID.randomUUID();
        Instant commitTime = now.plusSeconds(60);
        StorageKeyAllocation committed = StorageKeyAllocation.rehydrate(
                id, providerId, "bucket-1", key,
                StorageKeyAllocationStatus.COMMITTED,
                StorageKeyAllocationType.ASSET_VERSION,
                targetVersionId,
                now, commitTime, 0L
        );

        when(repository.saveAndFlush(existing)).thenAnswer(inv -> {
            existing.setPersistenceVersion(1L);
            return existing;
        });

        StorageKeyAllocation result = adapter.save(committed);

        assertThat(existing.getAllocationStatus()).isEqualTo(StorageKeyAllocationStatus.COMMITTED);
        assertThat(existing.getAllocatedByType()).isEqualTo(StorageKeyAllocationType.ASSET_VERSION);
        assertThat(existing.getAllocatedById()).isEqualTo(targetVersionId.toString());
        assertThat(existing.getUpdatedAt()).isEqualTo(commitTime);
        // Immutable properties preserved
        assertThat(existing.getStorageProviderId()).isEqualTo(providerId.value());
        assertThat(existing.getStorageBucketName()).isEqualTo("bucket-1");
        assertThat(existing.getStorageKey()).isEqualTo(key.value());
        assertThat(existing.getCreatedAt()).isEqualTo(now);

        assertThat(result.getStatus()).isEqualTo(StorageKeyAllocationStatus.COMMITTED);
        assertThat(result.getPersistenceVersion()).isEqualTo(1L);
    }

    @Test
    @DisplayName("Should reject stale update with OptimisticLockingFailureException when versions mismatch")
    void shouldRejectStaleUpdateWhenVersionMismatch() {
        StorageKeyAllocationJpaEntity existing = new StorageKeyAllocationJpaEntity();
        existing.setId(id.toString());
        existing.setPersistenceVersion(2L); // Already at version 2 in DB

        when(repository.findById(id.toString())).thenReturn(Optional.of(existing));

        // Caller has stale version 1
        StorageKeyAllocation stale = StorageKeyAllocation.rehydrate(
                id, providerId, "bucket-1", key,
                StorageKeyAllocationStatus.COMMITTED,
                StorageKeyAllocationType.ASSET_VERSION,
                UUID.randomUUID(),
                now, now.plusSeconds(60), 1L
        );

        assertThatThrownBy(() -> adapter.save(stale))
                .isInstanceOf(org.springframework.dao.OptimisticLockingFailureException.class)
                .hasMessageContaining("concurrently modified");
    }

    @Test
    @DisplayName("Should translate DataIntegrityViolationException to DuplicateStorageLocationException on provider_key constraint")
    void shouldTranslateDuplicateConstraint() {
        StorageKeyAllocation allocation = StorageKeyAllocation.reserve(
                id, providerId, "bucket-1", key, StorageKeyAllocationType.UPLOAD_SESSION, sessionId, now
        );

        when(repository.findById(id.toString())).thenReturn(Optional.empty());

        ConstraintViolationException cve = new ConstraintViolationException(
                "Duplicate key", new SQLException("Duplicate entry"), "uq_media_storage_key_allocations_provider_key"
        );
        DataIntegrityViolationException dive = new DataIntegrityViolationException("Constraint violation", cve);

        when(repository.saveAndFlush(any(StorageKeyAllocationJpaEntity.class))).thenThrow(dive);

        assertThatThrownBy(() -> adapter.save(allocation))
                .isInstanceOf(DuplicateStorageLocationException.class)
                .hasMessageContaining("Storage location is already in use");
    }

    @Test
    @DisplayName("Should NOT translate unrelated PRIMARY KEY or CHECK constraint violations to DuplicateStorageLocationException")
    void shouldNotTranslateUnrelatedConstraintViolations() {
        StorageKeyAllocation allocation = StorageKeyAllocation.reserve(
                id, providerId, "bucket-1", key, StorageKeyAllocationType.UPLOAD_SESSION, sessionId, now
        );

        when(repository.findById(id.toString())).thenReturn(Optional.empty());

        ConstraintViolationException cve = new ConstraintViolationException(
                "Duplicate key", new SQLException("Duplicate entry 'id-123' for key 'PRIMARY'"), "PRIMARY"
        );
        DataIntegrityViolationException dive = new DataIntegrityViolationException("Primary key violation", cve);

        when(repository.saveAndFlush(any(StorageKeyAllocationJpaEntity.class))).thenThrow(dive);

        assertThatThrownBy(() -> adapter.save(allocation))
                .isInstanceOf(DataIntegrityViolationException.class)
                .isNotInstanceOf(DuplicateStorageLocationException.class);
    }

    @Test
    @DisplayName("Should find allocation by location")
    void shouldFindByStorageLocation() {
        StorageKeyAllocationJpaEntity entity = new StorageKeyAllocationJpaEntity();
        entity.setId(id.toString());
        entity.setStorageProviderId(providerId.value());
        entity.setStorageKey(key.value());
        entity.setAllocationStatus(StorageKeyAllocationStatus.COMMITTED);
        entity.setAllocatedByType(StorageKeyAllocationType.ASSET_VERSION);
        entity.setAllocatedById(sessionId.toString());
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);
        entity.setPersistenceVersion(1L);

        when(repository.findByStorageProviderIdAndStorageKey(providerId.value(), key.value()))
                .thenReturn(Optional.of(entity));

        Optional<StorageKeyAllocation> found = adapter.findByStorageLocation(providerId, key);

        assertThat(found).isPresent();
        assertThat(found.get().getId()).isEqualTo(id);
        assertThat(found.get().getStatus()).isEqualTo(StorageKeyAllocationStatus.COMMITTED);
        assertThat(found.get().getPersistenceVersion()).isEqualTo(1L);
    }

    @Test
    @DisplayName("Should reject updating existing allocation when incoming version is null")
    void shouldRejectUpdatingExistingAllocationWhenIncomingVersionIsNull() {
        StorageKeyAllocationJpaEntity existing = new StorageKeyAllocationJpaEntity();
        existing.setId(id.toString());
        existing.setPersistenceVersion(0L);

        when(repository.findById(id.toString())).thenReturn(Optional.of(existing));

        // Rehydrated without version (null)
        StorageKeyAllocation withoutVersion = StorageKeyAllocation.rehydrate(
                id, providerId, "bucket-1", key,
                StorageKeyAllocationStatus.COMMITTED,
                StorageKeyAllocationType.ASSET_VERSION,
                UUID.randomUUID(),
                now, now.plusSeconds(60), null
        );

        assertThatThrownBy(() -> adapter.save(withoutVersion))
                .isInstanceOf(org.springframework.dao.OptimisticLockingFailureException.class)
                .hasMessageContaining("without version evidence");
    }

    @Test
    @DisplayName("Should reject creating new allocation when incoming version is non-null")
    void shouldRejectCreatingNewAllocationWhenIncomingVersionIsNotNull() {
        when(repository.findById(id.toString())).thenReturn(Optional.empty());

        // Has historical version 1L, but row does not exist in DB
        StorageKeyAllocation withVersion = StorageKeyAllocation.rehydrate(
                id, providerId, "bucket-1", key,
                StorageKeyAllocationStatus.RESERVED,
                StorageKeyAllocationType.UPLOAD_SESSION,
                sessionId,
                now, now, 1L
        );

        assertThatThrownBy(() -> adapter.save(withVersion))
                .isInstanceOf(org.springframework.dao.OptimisticLockingFailureException.class)
                .hasMessageContaining("Row is missing");
    }

    @Test
    @DisplayName("Should reject transitioning persisted COMMITTED allocation back to RESERVED")
    void shouldRejectTransitioningPersistedCommittedBackToReserved() {
        StorageKeyAllocationJpaEntity existing = new StorageKeyAllocationJpaEntity();
        existing.setId(id.toString());
        existing.setStorageProviderId(providerId.value());
        existing.setStorageBucketName("bucket-1");
        existing.setStorageKey(key.value());
        existing.setAllocationStatus(StorageKeyAllocationStatus.COMMITTED);
        existing.setAllocatedByType(StorageKeyAllocationType.ASSET_VERSION);
        existing.setAllocatedById(UUID.randomUUID().toString());
        existing.setCreatedAt(now);
        existing.setUpdatedAt(now);
        existing.setPersistenceVersion(0L);

        when(repository.findById(id.toString())).thenReturn(Optional.of(existing));

        // Incoming attempt to revert to RESERVED
        StorageKeyAllocation revertAttempt = StorageKeyAllocation.rehydrate(
                id, providerId, "bucket-1", key,
                StorageKeyAllocationStatus.RESERVED,
                StorageKeyAllocationType.UPLOAD_SESSION,
                sessionId,
                now, now.plusSeconds(60), 0L
        );

        assertThatThrownBy(() -> adapter.save(revertAttempt))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Cannot regress persisted COMMITTED storage key allocation")
                .hasMessageContaining("back to RESERVED");
    }

    @Test
    @DisplayName("Should reject mutating persisted TOMBSTONE allocation to non-TOMBSTONE state")
    void shouldRejectMutatingPersistedTombstoneToNonTombstone() {
        StorageKeyAllocationJpaEntity existing = new StorageKeyAllocationJpaEntity();
        existing.setId(id.toString());
        existing.setStorageProviderId(providerId.value());
        existing.setStorageBucketName("bucket-1");
        existing.setStorageKey(key.value());
        existing.setAllocationStatus(StorageKeyAllocationStatus.TOMBSTONE);
        existing.setAllocatedByType(StorageKeyAllocationType.UPLOAD_SESSION);
        existing.setAllocatedById(sessionId.toString());
        existing.setCreatedAt(now);
        existing.setUpdatedAt(now);
        existing.setPersistenceVersion(0L);

        when(repository.findById(id.toString())).thenReturn(Optional.of(existing));

        // Incoming attempt to resurrect to COMMITTED
        StorageKeyAllocation resurrectAttempt = StorageKeyAllocation.rehydrate(
                id, providerId, "bucket-1", key,
                StorageKeyAllocationStatus.COMMITTED,
                StorageKeyAllocationType.ASSET_VERSION,
                UUID.randomUUID(),
                now, now.plusSeconds(60), 0L
        );

        assertThatThrownBy(() -> adapter.save(resurrectAttempt))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("TOMBSTONE is terminal");
    }

    @Test
    @DisplayName("Should reject reassigning persisted COMMITTED ownership to a different target")
    void shouldRejectReassigningPersistedCommittedOwnership() {
        UUID originalTargetId = UUID.randomUUID();
        StorageKeyAllocationJpaEntity existing = new StorageKeyAllocationJpaEntity();
        existing.setId(id.toString());
        existing.setStorageProviderId(providerId.value());
        existing.setStorageBucketName("bucket-1");
        existing.setStorageKey(key.value());
        existing.setAllocationStatus(StorageKeyAllocationStatus.COMMITTED);
        existing.setAllocatedByType(StorageKeyAllocationType.ASSET_VERSION);
        existing.setAllocatedById(originalTargetId.toString());
        existing.setCreatedAt(now);
        existing.setUpdatedAt(now);
        existing.setPersistenceVersion(0L);

        when(repository.findById(id.toString())).thenReturn(Optional.of(existing));

        UUID differentTargetId = UUID.randomUUID();
        StorageKeyAllocation reassignmentAttempt = StorageKeyAllocation.rehydrate(
                id, providerId, "bucket-1", key,
                StorageKeyAllocationStatus.COMMITTED,
                StorageKeyAllocationType.ASSET_VERSION,
                differentTargetId,
                now, now.plusSeconds(60), 0L
        );

        assertThatThrownBy(() -> adapter.save(reassignmentAttempt))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Cannot reassign persisted COMMITTED storage key allocation");
    }

    @Test
    @DisplayName("Should reject same-state ownership rewriting on RESERVED allocation")
    void shouldRejectSameStateOwnershipRewritingOnReservedAllocation() {
        StorageKeyAllocationJpaEntity existing = new StorageKeyAllocationJpaEntity();
        existing.setId(id.toString());
        existing.setStorageProviderId(providerId.value());
        existing.setStorageBucketName("bucket-1");
        existing.setStorageKey(key.value());
        existing.setAllocationStatus(StorageKeyAllocationStatus.RESERVED);
        existing.setAllocatedByType(StorageKeyAllocationType.UPLOAD_SESSION);
        existing.setAllocatedById(sessionId.toString());
        existing.setCreatedAt(now);
        existing.setUpdatedAt(now);
        existing.setPersistenceVersion(0L);

        when(repository.findById(id.toString())).thenReturn(Optional.of(existing));

        UUID differentSessionId = UUID.randomUUID();
        StorageKeyAllocation rewriteAttempt = StorageKeyAllocation.rehydrate(
                id, providerId, "bucket-1", key,
                StorageKeyAllocationStatus.RESERVED,
                StorageKeyAllocationType.UPLOAD_SESSION,
                differentSessionId,
                now, now.plusSeconds(30), 0L
        );

        assertThatThrownBy(() -> adapter.save(rewriteAttempt))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Cannot rewrite ownership of RESERVED storage key allocation");
    }

    @Test
    @DisplayName("Should reject ownership change when transitioning RESERVED allocation to TOMBSTONE")
    void shouldRejectOwnershipChangeWhenTombstoningReservedAllocation() {
        StorageKeyAllocationJpaEntity existing = new StorageKeyAllocationJpaEntity();
        existing.setId(id.toString());
        existing.setStorageProviderId(providerId.value());
        existing.setStorageBucketName("bucket-1");
        existing.setStorageKey(key.value());
        existing.setAllocationStatus(StorageKeyAllocationStatus.RESERVED);
        existing.setAllocatedByType(StorageKeyAllocationType.UPLOAD_SESSION);
        existing.setAllocatedById(sessionId.toString());
        existing.setCreatedAt(now);
        existing.setUpdatedAt(now);
        existing.setPersistenceVersion(0L);

        when(repository.findById(id.toString())).thenReturn(Optional.of(existing));

        UUID differentOwnerId = UUID.randomUUID();
        StorageKeyAllocation tombstoneAttempt = StorageKeyAllocation.rehydrate(
                id, providerId, "bucket-1", key,
                StorageKeyAllocationStatus.TOMBSTONE,
                StorageKeyAllocationType.UPLOAD_SESSION,
                differentOwnerId,
                now, now.plusSeconds(30), 0L
        );

        assertThatThrownBy(() -> adapter.save(tombstoneAttempt))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Cannot rewrite owner when transitioning RESERVED storage key allocation")
                .hasMessageContaining("to TOMBSTONE")
                .hasMessageContaining("Original reservation owner");
    }

    @Test
    @DisplayName("Should reject ownership change when transitioning COMMITTED allocation to TOMBSTONE")
    void shouldRejectOwnershipChangeWhenTombstoningCommittedAllocation() {
        UUID versionId = UUID.randomUUID();
        StorageKeyAllocationJpaEntity existing = new StorageKeyAllocationJpaEntity();
        existing.setId(id.toString());
        existing.setStorageProviderId(providerId.value());
        existing.setStorageBucketName("bucket-1");
        existing.setStorageKey(key.value());
        existing.setAllocationStatus(StorageKeyAllocationStatus.COMMITTED);
        existing.setAllocatedByType(StorageKeyAllocationType.ASSET_VERSION);
        existing.setAllocatedById(versionId.toString());
        existing.setCreatedAt(now);
        existing.setUpdatedAt(now);
        existing.setPersistenceVersion(0L);

        when(repository.findById(id.toString())).thenReturn(Optional.of(existing));

        UUID differentVersionId = UUID.randomUUID();
        StorageKeyAllocation tombstoneAttempt = StorageKeyAllocation.rehydrate(
                id, providerId, "bucket-1", key,
                StorageKeyAllocationStatus.TOMBSTONE,
                StorageKeyAllocationType.ASSET_VERSION,
                differentVersionId,
                now, now.plusSeconds(30), 0L
        );

        assertThatThrownBy(() -> adapter.save(tombstoneAttempt))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Cannot rewrite owner when transitioning COMMITTED storage key allocation")
                .hasMessageContaining("to TOMBSTONE")
                .hasMessageContaining("Committed owner");
    }

    @Test
    @DisplayName("Should reject ownership change on persisted TOMBSTONE allocation")
    void shouldRejectOwnershipChangeOnPersistedTombstoneAllocation() {
        StorageKeyAllocationJpaEntity existing = new StorageKeyAllocationJpaEntity();
        existing.setId(id.toString());
        existing.setStorageProviderId(providerId.value());
        existing.setStorageBucketName("bucket-1");
        existing.setStorageKey(key.value());
        existing.setAllocationStatus(StorageKeyAllocationStatus.TOMBSTONE);
        existing.setAllocatedByType(StorageKeyAllocationType.UPLOAD_SESSION);
        existing.setAllocatedById(sessionId.toString());
        existing.setCreatedAt(now);
        existing.setUpdatedAt(now);
        existing.setPersistenceVersion(0L);

        when(repository.findById(id.toString())).thenReturn(Optional.of(existing));

        UUID differentOwnerId = UUID.randomUUID();
        StorageKeyAllocation tombstoneAttempt = StorageKeyAllocation.rehydrate(
                id, providerId, "bucket-1", key,
                StorageKeyAllocationStatus.TOMBSTONE,
                StorageKeyAllocationType.UPLOAD_SESSION,
                differentOwnerId,
                now, now.plusSeconds(30), 0L
        );

        assertThatThrownBy(() -> adapter.save(tombstoneAttempt))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Cannot rewrite owner of persisted TOMBSTONE storage key allocation")
                .hasMessageContaining("must remain immutable");
    }

    @Test
    @DisplayName("Should allow transitioning RESERVED to TOMBSTONE when reservation owner is preserved")
    void shouldAllowTransitioningReservedToTombstoneWhenOwnerPreserved() {
        StorageKeyAllocationJpaEntity existing = new StorageKeyAllocationJpaEntity();
        existing.setId(id.toString());
        existing.setStorageProviderId(providerId.value());
        existing.setStorageBucketName("bucket-1");
        existing.setStorageKey(key.value());
        existing.setAllocationStatus(StorageKeyAllocationStatus.RESERVED);
        existing.setAllocatedByType(StorageKeyAllocationType.UPLOAD_SESSION);
        existing.setAllocatedById(sessionId.toString());
        existing.setCreatedAt(now);
        existing.setUpdatedAt(now);
        existing.setPersistenceVersion(0L);

        when(repository.findById(id.toString())).thenReturn(Optional.of(existing));
        when(repository.saveAndFlush(existing)).thenReturn(existing);

        StorageKeyAllocation validTombstone = StorageKeyAllocation.rehydrate(
                id, providerId, "bucket-1", key,
                StorageKeyAllocationStatus.TOMBSTONE,
                StorageKeyAllocationType.UPLOAD_SESSION,
                sessionId,
                now, now.plusSeconds(30), 0L
        );

        StorageKeyAllocation result = adapter.save(validTombstone);
        assertThat(result.getStatus()).isEqualTo(StorageKeyAllocationStatus.TOMBSTONE);
        assertThat(existing.getAllocationStatus()).isEqualTo(StorageKeyAllocationStatus.TOMBSTONE);
        assertThat(existing.getAllocatedById()).isEqualTo(sessionId.toString());
    }

    @Test
    @DisplayName("Should allow transitioning COMMITTED to TOMBSTONE when committed owner is preserved")
    void shouldAllowTransitioningCommittedToTombstoneWhenOwnerPreserved() {
        UUID versionId = UUID.randomUUID();
        StorageKeyAllocationJpaEntity existing = new StorageKeyAllocationJpaEntity();
        existing.setId(id.toString());
        existing.setStorageProviderId(providerId.value());
        existing.setStorageBucketName("bucket-1");
        existing.setStorageKey(key.value());
        existing.setAllocationStatus(StorageKeyAllocationStatus.COMMITTED);
        existing.setAllocatedByType(StorageKeyAllocationType.ASSET_VERSION);
        existing.setAllocatedById(versionId.toString());
        existing.setCreatedAt(now);
        existing.setUpdatedAt(now);
        existing.setPersistenceVersion(0L);

        when(repository.findById(id.toString())).thenReturn(Optional.of(existing));
        when(repository.saveAndFlush(existing)).thenReturn(existing);

        StorageKeyAllocation validTombstone = StorageKeyAllocation.rehydrate(
                id, providerId, "bucket-1", key,
                StorageKeyAllocationStatus.TOMBSTONE,
                StorageKeyAllocationType.ASSET_VERSION,
                versionId,
                now, now.plusSeconds(30), 0L
        );

        StorageKeyAllocation result = adapter.save(validTombstone);
        assertThat(result.getStatus()).isEqualTo(StorageKeyAllocationStatus.TOMBSTONE);
        assertThat(existing.getAllocationStatus()).isEqualTo(StorageKeyAllocationStatus.TOMBSTONE);
        assertThat(existing.getAllocatedById()).isEqualTo(versionId.toString());
    }

    @Test
    @DisplayName("Should allow idempotent save of TOMBSTONE with identical owner")
    void shouldAllowIdempotentSaveOfTombstoneWithIdenticalOwner() {
        StorageKeyAllocationJpaEntity existing = new StorageKeyAllocationJpaEntity();
        existing.setId(id.toString());
        existing.setStorageProviderId(providerId.value());
        existing.setStorageBucketName("bucket-1");
        existing.setStorageKey(key.value());
        existing.setAllocationStatus(StorageKeyAllocationStatus.TOMBSTONE);
        existing.setAllocatedByType(StorageKeyAllocationType.UPLOAD_SESSION);
        existing.setAllocatedById(sessionId.toString());
        existing.setCreatedAt(now);
        existing.setUpdatedAt(now);
        existing.setPersistenceVersion(0L);

        when(repository.findById(id.toString())).thenReturn(Optional.of(existing));
        when(repository.saveAndFlush(existing)).thenReturn(existing);

        StorageKeyAllocation idempotentTombstone = StorageKeyAllocation.rehydrate(
                id, providerId, "bucket-1", key,
                StorageKeyAllocationStatus.TOMBSTONE,
                StorageKeyAllocationType.UPLOAD_SESSION,
                sessionId,
                now, now.plusSeconds(30), 0L
        );

        StorageKeyAllocation result = adapter.save(idempotentTombstone);
        assertThat(result.getStatus()).isEqualTo(StorageKeyAllocationStatus.TOMBSTONE);
        assertThat(existing.getAllocationStatus()).isEqualTo(StorageKeyAllocationStatus.TOMBSTONE);
        assertThat(existing.getAllocatedById()).isEqualTo(sessionId.toString());
    }
}
