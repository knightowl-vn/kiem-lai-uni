package com.universe.media.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("StorageKeyAllocation Domain Invariants Unit Test")
class StorageKeyAllocationTest {

    private final UUID allocationId = UUID.randomUUID();
    private final StorageProviderId providerId = StorageProviderId.of("b2");
    private final String bucketName = "kiemlai-video";
    private final StorageKey storageKey = StorageKey.of("objects/video_001.mp4");
    private final UUID sessionId = UUID.randomUUID();
    private final UUID versionId = UUID.randomUUID();
    private final Instant now = Instant.parse("2026-10-08T12:00:00Z");

    @Test
    @DisplayName("Should successfully create a RESERVED storage key allocation")
    void shouldCreateReservedAllocation() {
        StorageKeyAllocation allocation = StorageKeyAllocation.reserve(
                allocationId,
                providerId,
                bucketName,
                storageKey,
                StorageKeyAllocationType.UPLOAD_SESSION,
                sessionId,
                now
        );

        assertThat(allocation.getId()).isEqualTo(allocationId);
        assertThat(allocation.getProviderId()).isEqualTo(providerId);
        assertThat(allocation.getBucketName()).isEqualTo(bucketName);
        assertThat(allocation.getStorageKey()).isEqualTo(storageKey);
        assertThat(allocation.getStatus()).isEqualTo(StorageKeyAllocationStatus.RESERVED);
        assertThat(allocation.getAllocatedByType()).isEqualTo(StorageKeyAllocationType.UPLOAD_SESSION);
        assertThat(allocation.getAllocatedById()).isEqualTo(sessionId);
        assertThat(allocation.getCreatedAt()).isEqualTo(now);
        assertThat(allocation.getUpdatedAt()).isEqualTo(now);
    }

    @Test
    @DisplayName("Should transition from RESERVED to COMMITTED with target version ID")
    void shouldTransitionReservedToCommitted() {
        StorageKeyAllocation allocation = StorageKeyAllocation.reserve(
                allocationId,
                providerId,
                bucketName,
                storageKey,
                StorageKeyAllocationType.UPLOAD_SESSION,
                sessionId,
                now
        );

        Instant commitTime = now.plusSeconds(300);
        allocation.commit(StorageKeyAllocationType.ASSET_VERSION, versionId, commitTime);

        assertThat(allocation.getStatus()).isEqualTo(StorageKeyAllocationStatus.COMMITTED);
        assertThat(allocation.getAllocatedByType()).isEqualTo(StorageKeyAllocationType.ASSET_VERSION);
        assertThat(allocation.getAllocatedById()).isEqualTo(versionId);
        assertThat(allocation.getUpdatedAt()).isEqualTo(commitTime);
    }

    @Test
    @DisplayName("Should transition from RESERVED to TOMBSTONE (permanent non-reusable)")
    void shouldTransitionReservedToTombstone() {
        StorageKeyAllocation allocation = StorageKeyAllocation.reserve(
                allocationId,
                providerId,
                bucketName,
                storageKey,
                StorageKeyAllocationType.UPLOAD_SESSION,
                sessionId,
                now
        );

        Instant tombstoneTime = now.plusSeconds(600);
        allocation.tombstone(tombstoneTime);

        assertThat(allocation.getStatus()).isEqualTo(StorageKeyAllocationStatus.TOMBSTONE);
        assertThat(allocation.getUpdatedAt()).isEqualTo(tombstoneTime);
    }

    @Test
    @DisplayName("Should transition from COMMITTED to TOMBSTONE upon asset deletion")
    void shouldTransitionCommittedToTombstone() {
        StorageKeyAllocation allocation = StorageKeyAllocation.reserve(
                allocationId,
                providerId,
                bucketName,
                storageKey,
                StorageKeyAllocationType.UPLOAD_SESSION,
                sessionId,
                now
        );
        allocation.commit(StorageKeyAllocationType.ASSET_VERSION, versionId, now.plusSeconds(100));

        Instant tombstoneTime = now.plusSeconds(1000);
        allocation.tombstone(tombstoneTime);

        assertThat(allocation.getStatus()).isEqualTo(StorageKeyAllocationStatus.TOMBSTONE);
    }

    @Test
    @DisplayName("Tombstoned allocation cannot transition to COMMITTED (permanent tombstone)")
    void shouldRejectTransitionsFromTombstone() {
        StorageKeyAllocation allocation = StorageKeyAllocation.reserve(
                allocationId,
                providerId,
                bucketName,
                storageKey,
                StorageKeyAllocationType.UPLOAD_SESSION,
                sessionId,
                now
        );
        allocation.tombstone(now.plusSeconds(100));

        assertThatThrownBy(() -> allocation.commit(StorageKeyAllocationType.ASSET_VERSION, versionId, now.plusSeconds(200)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Cannot commit a tombstoned storage key allocation");
    }

    @Test
    @DisplayName("Idempotent repeat commit for identical target succeeds as no-op")
    void shouldAllowIdempotentRepeatCommitForIdenticalTarget() {
        StorageKeyAllocation allocation = StorageKeyAllocation.reserve(
                allocationId,
                providerId,
                bucketName,
                storageKey,
                StorageKeyAllocationType.UPLOAD_SESSION,
                sessionId,
                now
        );

        Instant firstCommitTime = now.plusSeconds(300);
        allocation.commit(StorageKeyAllocationType.ASSET_VERSION, versionId, firstCommitTime);

        // Repeat commit for identical target and type
        allocation.commit(StorageKeyAllocationType.ASSET_VERSION, versionId, now.plusSeconds(500));

        assertThat(allocation.getStatus()).isEqualTo(StorageKeyAllocationStatus.COMMITTED);
        assertThat(allocation.getAllocatedByType()).isEqualTo(StorageKeyAllocationType.ASSET_VERSION);
        assertThat(allocation.getAllocatedById()).isEqualTo(versionId);
    }

    @Test
    @DisplayName("Committed allocation cannot be reassigned to a different target or type")
    void shouldRejectReassigningCommittedAllocationToDifferentTarget() {
        StorageKeyAllocation allocation = StorageKeyAllocation.reserve(
                allocationId,
                providerId,
                bucketName,
                storageKey,
                StorageKeyAllocationType.UPLOAD_SESSION,
                sessionId,
                now
        );

        allocation.commit(StorageKeyAllocationType.ASSET_VERSION, versionId, now.plusSeconds(100));

        UUID differentId = UUID.randomUUID();
        assertThatThrownBy(() -> allocation.commit(StorageKeyAllocationType.ASSET_VERSION, differentId, now.plusSeconds(200)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Cannot reassign a COMMITTED storage key allocation to a different target");

        assertThatThrownBy(() -> allocation.commit(StorageKeyAllocationType.IMAGE_VARIANT, versionId, now.plusSeconds(200)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Cannot reassign a COMMITTED storage key allocation to a different target");
    }

    @Test
    @DisplayName("Repeat tombstone is idempotent no-op")
    void shouldAllowRepeatTombstoneIdempotently() {
        StorageKeyAllocation allocation = StorageKeyAllocation.reserve(
                allocationId,
                providerId,
                bucketName,
                storageKey,
                StorageKeyAllocationType.UPLOAD_SESSION,
                sessionId,
                now
        );

        allocation.tombstone(now.plusSeconds(100));
        allocation.tombstone(now.plusSeconds(200));

        assertThat(allocation.getStatus()).isEqualTo(StorageKeyAllocationStatus.TOMBSTONE);
    }

    @Test
    @DisplayName("Rehydrate preserves persistenceVersion")
    void shouldPreservePersistenceVersionOnRehydrate() {
        StorageKeyAllocation allocation = StorageKeyAllocation.rehydrate(
                allocationId,
                providerId,
                bucketName,
                storageKey,
                StorageKeyAllocationStatus.RESERVED,
                StorageKeyAllocationType.UPLOAD_SESSION,
                sessionId,
                now,
                now,
                5L
        );

        assertThat(allocation.getPersistenceVersion()).isEqualTo(5L);
    }

    @Test
    @DisplayName("Should reject null fields upon reservation")
    void shouldRejectNullFields() {
        assertThatThrownBy(() -> StorageKeyAllocation.reserve(null, providerId, bucketName, storageKey, StorageKeyAllocationType.UPLOAD_SESSION, sessionId, now))
                .isInstanceOf(NullPointerException.class);

        assertThatThrownBy(() -> StorageKeyAllocation.reserve(allocationId, null, bucketName, storageKey, StorageKeyAllocationType.UPLOAD_SESSION, sessionId, now))
                .isInstanceOf(NullPointerException.class);

        assertThatThrownBy(() -> StorageKeyAllocation.reserve(allocationId, providerId, bucketName, null, StorageKeyAllocationType.UPLOAD_SESSION, sessionId, now))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("Should reject null, blank or sentinel bucket for bucketed provider (b2)")
    void shouldRejectInvalidBucketForBucketedProvider() {
        assertThatThrownBy(() -> StorageKeyAllocation.reserve(allocationId, StorageProviderId.of("b2"), null, storageKey, StorageKeyAllocationType.UPLOAD_SESSION, sessionId, now))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Storage provider 'b2' requires a valid non-blank bucket name");

        assertThatThrownBy(() -> StorageKeyAllocation.reserve(allocationId, StorageProviderId.of("b2"), "   ", storageKey, StorageKeyAllocationType.UPLOAD_SESSION, sessionId, now))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Storage provider 'b2' requires a valid non-blank bucket name");

        assertThatThrownBy(() -> StorageKeyAllocation.reserve(allocationId, StorageProviderId.of("b2"), "NONE", storageKey, StorageKeyAllocationType.UPLOAD_SESSION, sessionId, now))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cannot use the bucketless sentinel");
    }

    @Test
    @DisplayName("Should canonicalize null or blank bucket to NONE for bucketless provider (local)")
    void shouldCanonicalizeBucketForBucketlessProvider() {
        StorageKeyAllocation allocationNull = StorageKeyAllocation.reserve(
                allocationId,
                StorageProviderId.of("local"),
                null,
                storageKey,
                StorageKeyAllocationType.UPLOAD_SESSION,
                sessionId,
                now
        );
        assertThat(allocationNull.getBucketName()).isEqualTo(StorageKeyAllocation.CANONICAL_BUCKETLESS_SENTINEL);
        assertThat(allocationNull.isBucketless()).isTrue();
        assertThat(allocationNull.getCloudBucketName()).isEmpty();

        StorageKeyAllocation allocationBlank = StorageKeyAllocation.reserve(
                UUID.randomUUID(),
                StorageProviderId.of("local"),
                "  ",
                storageKey,
                StorageKeyAllocationType.UPLOAD_SESSION,
                sessionId,
                now
        );
        assertThat(allocationBlank.getBucketName()).isEqualTo(StorageKeyAllocation.CANONICAL_BUCKETLESS_SENTINEL);
        assertThat(allocationBlank.isBucketless()).isTrue();
        assertThat(allocationBlank.getCloudBucketName()).isEmpty();

        StorageKeyAllocation allocationExplicitNone = StorageKeyAllocation.reserve(
                UUID.randomUUID(),
                StorageProviderId.of("local"),
                "NONE",
                storageKey,
                StorageKeyAllocationType.UPLOAD_SESSION,
                sessionId,
                now
        );
        assertThat(allocationExplicitNone.getBucketName()).isEqualTo(StorageKeyAllocation.CANONICAL_BUCKETLESS_SENTINEL);
        assertThat(allocationExplicitNone.isBucketless()).isTrue();
        assertThat(allocationExplicitNone.getCloudBucketName()).isEmpty();
    }

    @Test
    @DisplayName("Bucketed provider preserves bucket and returns cloud bucket name")
    void shouldPreserveBucketForBucketedProvider() {
        StorageKeyAllocation allocation = StorageKeyAllocation.reserve(
                allocationId,
                StorageProviderId.of("b2"),
                "kiemlai-video",
                storageKey,
                StorageKeyAllocationType.UPLOAD_SESSION,
                sessionId,
                now
        );
        assertThat(allocation.getBucketName()).isEqualTo("kiemlai-video");
        assertThat(allocation.isBucketless()).isFalse();
        assertThat(allocation.getCloudBucketName()).contains("kiemlai-video");
    }
}
