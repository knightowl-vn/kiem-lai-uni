package com.universe.media.application.storage;

import com.universe.media.application.exceptions.DuplicateStorageLocationException;
import com.universe.media.application.ports.StorageKeyAllocationRepositoryPort;
import com.universe.shared.time.ClockPort;
import com.universe.media.domain.StorageKey;
import com.universe.media.domain.StorageKeyAllocation;
import com.universe.media.domain.StorageKeyAllocationStatus;
import com.universe.media.domain.StorageKeyAllocationType;
import com.universe.media.domain.StorageLocation;
import com.universe.media.domain.StorageProviderId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("StorageKeyAllocationService Unit Tests")
class StorageKeyAllocationServiceTest {

    @Mock
    private StorageKeyAllocationRepositoryPort repositoryPort;

    @Mock
    private ClockPort clockPort;

    private StorageKeyAllocationService service;

    private final Instant now = Instant.parse("2026-10-08T12:00:00Z");
    private final StorageProviderId providerId = StorageProviderId.of("b2");
    private final String bucket = "kiemlai-video";
    private final StorageKey key = StorageKey.of("objects/video_test.mp4");
    private final UUID ownerId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new StorageKeyAllocationService(repositoryPort, clockPort);
    }

    @Test
    @DisplayName("Successfully reserves a new unique storage key")
    void shouldReserveStorageKeySuccessfully() {
        when(clockPort.now()).thenReturn(now);
        when(repositoryPort.existsByStorageLocation(providerId, key)).thenReturn(false);
        when(repositoryPort.save(any(StorageKeyAllocation.class))).thenAnswer(invocation -> invocation.getArgument(0));

        StorageKeyAllocation allocation = service.reserveKey(
                providerId,
                bucket,
                key,
                StorageKeyAllocationType.UPLOAD_SESSION,
                ownerId
        );

        assertThat(allocation).isNotNull();
        assertThat(allocation.getProviderId()).isEqualTo(providerId);
        assertThat(allocation.getStorageKey()).isEqualTo(key);
        assertThat(allocation.getStatus()).isEqualTo(StorageKeyAllocationStatus.RESERVED);
        assertThat(allocation.getAllocatedById()).isEqualTo(ownerId);
        verify(repositoryPort).save(any(StorageKeyAllocation.class));
    }

    @Test
    @DisplayName("Rejects reservation when storage location is already allocated")
    void shouldRejectReservationWhenKeyExists() {
        when(repositoryPort.existsByStorageLocation(providerId, key)).thenReturn(true);

        assertThatThrownBy(() -> service.reserveKey(
                providerId,
                bucket,
                key,
                StorageKeyAllocationType.UPLOAD_SESSION,
                ownerId
        )).isInstanceOf(DuplicateStorageLocationException.class);
    }

    @Test
    @DisplayName("Successfully commits an existing reservation to an asset version")
    void shouldCommitAllocationSuccessfully() {
        UUID allocationId = UUID.randomUUID();
        UUID versionId = UUID.randomUUID();
        StorageKeyAllocation allocation = StorageKeyAllocation.reserve(
                allocationId,
                providerId,
                bucket,
                key,
                StorageKeyAllocationType.UPLOAD_SESSION,
                ownerId,
                now
        );

        when(repositoryPort.findById(allocationId)).thenReturn(Optional.of(allocation));
        when(clockPort.now()).thenReturn(now.plusSeconds(120));
        when(repositoryPort.save(any(StorageKeyAllocation.class))).thenAnswer(invocation -> invocation.getArgument(0));

        StorageKeyAllocation committed = service.commitAllocation(
                allocationId,
                StorageKeyAllocationType.ASSET_VERSION,
                versionId
        );

        assertThat(committed.getStatus()).isEqualTo(StorageKeyAllocationStatus.COMMITTED);
        assertThat(committed.getAllocatedByType()).isEqualTo(StorageKeyAllocationType.ASSET_VERSION);
        assertThat(committed.getAllocatedById()).isEqualTo(versionId);
    }

    @Test
    @DisplayName("Successfully marks an existing reservation as tombstoned")
    void shouldTombstoneAllocationSuccessfully() {
        UUID allocationId = UUID.randomUUID();
        StorageKeyAllocation allocation = StorageKeyAllocation.reserve(
                allocationId,
                providerId,
                bucket,
                key,
                StorageKeyAllocationType.UPLOAD_SESSION,
                ownerId,
                now
        );

        when(repositoryPort.findById(allocationId)).thenReturn(Optional.of(allocation));
        when(clockPort.now()).thenReturn(now.plusSeconds(300));
        when(repositoryPort.save(any(StorageKeyAllocation.class))).thenAnswer(invocation -> invocation.getArgument(0));

        service.tombstoneAllocation(allocationId);

        assertThat(allocation.getStatus()).isEqualTo(StorageKeyAllocationStatus.TOMBSTONE);
    }
}
