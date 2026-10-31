package com.universe.media.application.storage;

import com.universe.media.application.ports.MediaUploadSessionRepositoryPort;
import com.universe.media.domain.MediaUploadSession;
import com.universe.media.domain.MediaUploadSessionStatus;
import com.universe.media.domain.MediaType;
import com.universe.media.domain.StorageKey;
import com.universe.media.domain.StorageKeyAllocation;
import com.universe.media.domain.StorageKeyAllocationType;
import com.universe.media.domain.StorageProviderId;
import com.universe.media.domain.UploadSessionCleanupStatus;
import com.universe.shared.time.ClockPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("MediaUploadSessionService Unit Tests")
class MediaUploadSessionServiceTest {

    @Mock
    private MediaUploadSessionRepositoryPort sessionRepositoryPort;

    @Mock
    private StorageKeyAllocationService storageKeyAllocationService;

    @Mock
    private MediaStorageRoutingService routingService;

    @Mock
    private com.universe.media.application.ports.storage.MultipartStoragePort multipartStoragePort;

    private ClockPort clockPort;
    private MediaUploadSessionService service;

    private static final StorageProviderId B2_PROVIDER = StorageProviderId.of("b2");
    private static final String B2_BUCKET = "kiemlai-video";
    private static final StorageKey STORAGE_KEY = StorageKey.of("media/video/session-test.mp4");
    private static final Instant NOW = Instant.parse("2026-10-09T10:00:00Z");

    @BeforeEach
    void setUp() {
        clockPort = () -> NOW;
        org.mockito.Mockito.lenient().when(multipartStoragePort.providerId()).thenReturn(B2_PROVIDER);
        org.mockito.Mockito.lenient().when(routingService.resolveWriteProvider(org.mockito.ArgumentMatchers.eq(com.universe.media.domain.MediaType.VIDEO), org.mockito.ArgumentMatchers.any()))
                .thenReturn(B2_PROVIDER);
        org.mockito.Mockito.lenient().when(routingService.resolveWriteProvider(org.mockito.ArgumentMatchers.eq(com.universe.media.domain.MediaType.VIDEO), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(B2_PROVIDER);
        service = new MediaUploadSessionService(
                sessionRepositoryPort,
                storageKeyAllocationService,
                routingService,
                clockPort,
                null,
                multipartStoragePort
        );
    }

    @Nested
    @DisplayName("Allocate Session Tests")
    class AllocateTests {

        @Test
        @DisplayName("Successfully allocates new session when no active session exists")
        void allocatesNewSession() {
            UUID allocId = UUID.randomUUID();
            UUID targetId = UUID.randomUUID();

            when(sessionRepositoryPort.findByIdempotencyKey("user-1", "idem-1"))
                    .thenReturn(Optional.empty());
            when(routingService.generateStorageKey(eq(MediaType.VIDEO), eq("screen"), any(), eq(1)))
                    .thenReturn(STORAGE_KEY);
            when(routingService.resolveBucketName(B2_PROVIDER))
                    .thenReturn(B2_BUCKET);

            StorageKeyAllocation mockAlloc = StorageKeyAllocation.reserve(
                    allocId,
                    B2_PROVIDER,
                    B2_BUCKET,
                    STORAGE_KEY,
                    StorageKeyAllocationType.UPLOAD_SESSION,
                    UUID.randomUUID(),
                    NOW
            );
            when(storageKeyAllocationService.reserveKey(eq(B2_PROVIDER), eq(B2_BUCKET), eq(STORAGE_KEY), eq(StorageKeyAllocationType.UPLOAD_SESSION), any()))
                    .thenReturn(mockAlloc);

            when(sessionRepositoryPort.save(any(MediaUploadSession.class)))
                    .thenAnswer(inv -> inv.getArgument(0));

            MediaUploadSession session = service.allocateSession(
                    "user-1",
                    "screen",
                    "ScreenItem",
                    targetId,
                    "idem-1",
                    MediaUploadSession.FIXED_PART_SIZE_BYTES * 2,
                    "movie.mp4",
                    "screen"
            );

            assertThat(session).isNotNull();
            assertThat(session.getStatus()).isEqualTo(MediaUploadSessionStatus.ALLOCATED);
            assertThat(session.getInitiatorPrincipalId()).isEqualTo("user-1");
            assertThat(session.getIdempotencyKey()).isEqualTo("idem-1");
            assertThat(session.getStorageAllocationId()).isEqualTo(allocId);
            assertThat(session.getStorageProviderId()).isEqualTo(B2_PROVIDER);
            assertThat(session.getStorageBucketName()).isEqualTo(B2_BUCKET);
            assertThat(session.getStorageKey()).isEqualTo(STORAGE_KEY);
            assertThat(session.getExpectedPartCount()).isEqualTo(2);

            verify(sessionRepositoryPort).save(any(MediaUploadSession.class));
        }

        @Test
        @DisplayName("Replays existing active session for identical idempotency key without re-allocating")
        void replaysExistingActiveSession() {
            UUID targetId = UUID.randomUUID();
            MediaUploadSession existingSession = MediaUploadSession.create(
                    UUID.randomUUID(),
                    "user-1",
                    "screen",
                    "ScreenItem",
                    targetId,
                    "idem-1",
                    MediaUploadSession.FIXED_PART_SIZE_BYTES * 2,
                    B2_PROVIDER,
                    B2_BUCKET,
                    STORAGE_KEY,
                    UUID.randomUUID(),
                    "movie.mp4",
                    "screen",
                    NOW.plus(24, ChronoUnit.HOURS),
                    NOW
            );

            when(sessionRepositoryPort.findByIdempotencyKey("user-1", "idem-1"))
                    .thenReturn(Optional.of(existingSession));

            MediaUploadSession replayed = service.allocateSession(
                    "user-1",
                    "screen",
                    "ScreenItem",
                    targetId,
                    "idem-1",
                    MediaUploadSession.FIXED_PART_SIZE_BYTES * 2,
                    "movie.mp4",
                    "screen"
            );

            assertThat(replayed).isSameAs(existingSession);
            verify(storageKeyAllocationService, never()).reserveKey(any(), any(), any(), any(), any());
            verify(sessionRepositoryPort, never()).save(any());
        }

        @Test
        @DisplayName("Throws IllegalArgumentException when replaying idempotency key with conflicting parameters")
        void throwsOnIdempotencyParameterConflict() {
            UUID targetId = UUID.randomUUID();
            MediaUploadSession existingSession = MediaUploadSession.create(
                    UUID.randomUUID(),
                    "user-1",
                    "screen",
                    "ScreenItem",
                    targetId,
                    "idem-1",
                    MediaUploadSession.FIXED_PART_SIZE_BYTES * 2,
                    B2_PROVIDER,
                    B2_BUCKET,
                    STORAGE_KEY,
                    UUID.randomUUID(),
                    "movie.mp4",
                    "screen",
                    NOW.plus(24, ChronoUnit.HOURS),
                    NOW
            );

            when(sessionRepositoryPort.findByIdempotencyKey("user-1", "idem-1"))
                    .thenReturn(Optional.of(existingSession));

            org.junit.jupiter.api.Assertions.assertThrows(
                    IllegalArgumentException.class,
                    () -> service.allocateSession(
                            "user-1",
                            "screen",
                            "ScreenItem",
                            targetId,
                            "idem-1",
                            MediaUploadSession.FIXED_PART_SIZE_BYTES * 5, // conflicting size!
                            "movie.mp4",
                            "screen"
                    )
            );
        }

        @Test
        @DisplayName("Normalizes original filename by stripping path traversal characters")
        void normalizesFilename() {
            UUID allocId = UUID.randomUUID();
            UUID targetId = UUID.randomUUID();

            when(sessionRepositoryPort.findByIdempotencyKey("user-1", "idem-norm"))
                    .thenReturn(Optional.empty());
            when(routingService.generateStorageKey(any(), any(), any(), anyInt()))
                    .thenReturn(STORAGE_KEY);
            when(routingService.resolveBucketName(B2_PROVIDER))
                    .thenReturn(B2_BUCKET);

            StorageKeyAllocation mockAlloc = StorageKeyAllocation.reserve(
                    allocId,
                    B2_PROVIDER,
                    B2_BUCKET,
                    STORAGE_KEY,
                    StorageKeyAllocationType.UPLOAD_SESSION,
                    UUID.randomUUID(),
                    NOW
            );
            when(storageKeyAllocationService.reserveKey(any(), any(), any(), any(), any()))
                    .thenReturn(mockAlloc);
            when(sessionRepositoryPort.save(any(MediaUploadSession.class)))
                    .thenAnswer(inv -> inv.getArgument(0));

            MediaUploadSession session = service.allocateSession(
                    "user-1",
                    "screen",
                    "ScreenItem",
                    targetId,
                    "idem-norm",
                    MediaUploadSession.FIXED_PART_SIZE_BYTES * 2,
                    "../../etc/passwd\\video.mp4",
                    "screen"
            );

            assertThat(session.getOriginalFilename()).isEqualTo("video.mp4");
        }

        @Test
        @DisplayName("Handles concurrent first-request race collision with redundant reservation compensation")
        void handlesConcurrentRaceWithCompensation() {
            UUID allocId = UUID.randomUUID();
            UUID targetId = UUID.randomUUID();

            when(sessionRepositoryPort.findByIdempotencyKey("user-1", "idem-race"))
                    .thenReturn(Optional.empty()) // initial check finds nothing
                    .thenReturn(Optional.of(MediaUploadSession.create(
                            UUID.randomUUID(),
                            "user-1",
                            "screen",
                            "ScreenItem",
                            targetId,
                            "idem-race",
                            MediaUploadSession.FIXED_PART_SIZE_BYTES * 2,
                            B2_PROVIDER,
                            B2_BUCKET,
                            STORAGE_KEY,
                            UUID.randomUUID(),
                            "movie.mp4",
                            "screen",
                            NOW.plus(24, ChronoUnit.HOURS),
                            NOW
                    ))); // subsequent query after race finds winner

            when(routingService.generateStorageKey(any(), any(), any(), anyInt())).thenReturn(STORAGE_KEY);
            when(routingService.resolveBucketName(B2_PROVIDER)).thenReturn(B2_BUCKET);

            StorageKeyAllocation mockAlloc = StorageKeyAllocation.reserve(
                    allocId,
                    B2_PROVIDER,
                    B2_BUCKET,
                    STORAGE_KEY,
                    StorageKeyAllocationType.UPLOAD_SESSION,
                    UUID.randomUUID(),
                    NOW
            );
            when(storageKeyAllocationService.reserveKey(any(), any(), any(), any(), any())).thenReturn(mockAlloc);

            // First save throws DataIntegrityViolationException due to concurrent duplicate key
            when(sessionRepositoryPort.save(any()))
                    .thenThrow(new org.springframework.dao.DataIntegrityViolationException("Duplicate entry 'user-1-idem-race' for key 'media_upload_sessions.uq_media_upload_sessions_idempotency'"));

            MediaUploadSession result = service.allocateSession(
                    "user-1",
                    "screen",
                    "ScreenItem",
                    targetId,
                    "idem-race",
                    MediaUploadSession.FIXED_PART_SIZE_BYTES * 2,
                    "movie.mp4",
                    "screen"
            );

            assertThat(result).isNotNull();
            // Redundant reservation must be immediately tombstoned to prevent leaked storage keys
            verify(storageKeyAllocationService).tombstoneAllocationInNewTransaction(allocId);
        }

        @Test
        @DisplayName("Does not treat unrelated constraint violations as idempotency collisions")
        void doesNotTreatUnrelatedConstraintAsIdempotencyCollision() {
            UUID allocId = UUID.randomUUID();
            UUID targetId = UUID.randomUUID();

            when(sessionRepositoryPort.findByIdempotencyKey("user-1", "idem-unrelated"))
                    .thenReturn(Optional.empty());
            when(routingService.generateStorageKey(any(), any(), any(), anyInt())).thenReturn(STORAGE_KEY);
            when(routingService.resolveBucketName(B2_PROVIDER)).thenReturn(B2_BUCKET);

            StorageKeyAllocation mockAlloc = StorageKeyAllocation.reserve(
                    allocId,
                    B2_PROVIDER,
                    B2_BUCKET,
                    STORAGE_KEY,
                    StorageKeyAllocationType.UPLOAD_SESSION,
                    UUID.randomUUID(),
                    NOW
            );
            when(storageKeyAllocationService.reserveKey(any(), any(), any(), any(), any())).thenReturn(mockAlloc);

            // Save throws unrelated FK or CHECK constraint violation
            when(sessionRepositoryPort.save(any()))
                    .thenThrow(new org.springframework.dao.DataIntegrityViolationException("Cannot add or update a child row: a foreign key constraint fails"));

            org.junit.jupiter.api.Assertions.assertThrows(
                    org.springframework.dao.DataIntegrityViolationException.class,
                    () -> service.allocateSession(
                            "user-1",
                            "screen",
                            "ScreenItem",
                            targetId,
                            "idem-unrelated",
                            MediaUploadSession.FIXED_PART_SIZE_BYTES * 2,
                            "movie.mp4",
                            "screen"
                    )
            );

            // Allocation must still be tombstoned on failure to avoid leaking the reserved key
            verify(storageKeyAllocationService).tombstoneAllocationInNewTransaction(allocId);
        }

        @Test
        @DisplayName("Rejects invalid declared size before reserving key")
        void rejectsInvalidDeclaredSizeBeforeReservation() {
            UUID targetId = UUID.randomUUID();

            // Zero size
            org.junit.jupiter.api.Assertions.assertThrows(
                    IllegalArgumentException.class,
                    () -> service.allocateSession("user-1", "screen", "ScreenItem", targetId, "idem-size-0", 0L, "video.mp4", "screen")
            );

            // Negative size
            org.junit.jupiter.api.Assertions.assertThrows(
                    IllegalArgumentException.class,
                    () -> service.allocateSession("user-1", "screen", "ScreenItem", targetId, "idem-size-neg", -100L, "video.mp4", "screen")
            );

            // Exceeds 50 GiB limit
            org.junit.jupiter.api.Assertions.assertThrows(
                    IllegalArgumentException.class,
                    () -> service.allocateSession("user-1", "screen", "ScreenItem", targetId, "idem-size-huge", MediaUploadSession.MAX_VIDEO_SIZE_BYTES + 1L, "video.mp4", "screen")
            );

            // Guarantee zero storage key allocations were attempted
            verify(storageKeyAllocationService, never()).reserveKey(any(), any(), any(), any(), any());
        }

        @Test
        @DisplayName("Rejects blank or null deterministic arguments before reserving key")
        void rejectsBlankOrNullArgumentsBeforeReservation() {
            UUID targetId = UUID.randomUUID();

            // Blank initiator
            org.junit.jupiter.api.Assertions.assertThrows(
                    IllegalArgumentException.class,
                    () -> service.allocateSession("  ", "screen", "ScreenItem", targetId, "idem-arg", 1024L, "video.mp4", "screen")
            );

            // Blank consumer context
            org.junit.jupiter.api.Assertions.assertThrows(
                    IllegalArgumentException.class,
                    () -> service.allocateSession("user-1", "", "ScreenItem", targetId, "idem-arg", 1024L, "video.mp4", "screen")
            );

            // Blank target entity type
            org.junit.jupiter.api.Assertions.assertThrows(
                    IllegalArgumentException.class,
                    () -> service.allocateSession("user-1", "screen", "   ", targetId, "idem-arg", 1024L, "video.mp4", "screen")
            );

            // Null target entity id
            org.junit.jupiter.api.Assertions.assertThrows(
                    NullPointerException.class,
                    () -> service.allocateSession("user-1", "screen", "ScreenItem", null, "idem-arg", 1024L, "video.mp4", "screen")
            );

            // Blank idempotency key
            org.junit.jupiter.api.Assertions.assertThrows(
                    IllegalArgumentException.class,
                    () -> service.allocateSession("user-1", "screen", "ScreenItem", targetId, "  ", 1024L, "video.mp4", "screen")
            );

            // Blank or invalid filename
            org.junit.jupiter.api.Assertions.assertThrows(
                    IllegalArgumentException.class,
                    () -> service.allocateSession("user-1", "screen", "ScreenItem", targetId, "idem-arg", 1024L, "  ", "screen")
            );

            verify(storageKeyAllocationService, never()).reserveKey(any(), any(), any(), any(), any());
        }

        @Test
        @DisplayName("Reconciles uncertain persistence outcome when session actually committed despite save exception")
        void reconcilesUncertainPersistenceWhenSessionDidCommit() {
            UUID allocId = UUID.randomUUID();
            UUID targetId = UUID.randomUUID();

            when(sessionRepositoryPort.findByIdempotencyKey("user-1", "idem-uncertain-1"))
                    .thenReturn(Optional.empty());
            when(routingService.generateStorageKey(any(), any(), any(), anyInt())).thenReturn(STORAGE_KEY);
            when(routingService.resolveBucketName(B2_PROVIDER)).thenReturn(B2_BUCKET);

            StorageKeyAllocation mockAlloc = StorageKeyAllocation.reserve(
                    allocId,
                    B2_PROVIDER,
                    B2_BUCKET,
                    STORAGE_KEY,
                    StorageKeyAllocationType.UPLOAD_SESSION,
                    UUID.randomUUID(),
                    NOW
            );
            when(storageKeyAllocationService.reserveKey(any(), any(), any(), any(), any())).thenReturn(mockAlloc);

            // Save throws TransientDataAccessResourceException (e.g. socket timeout after DB commit)
            when(sessionRepositoryPort.save(any()))
                    .thenThrow(new org.springframework.dao.TransientDataAccessResourceException("Socket timeout during commit ack"));

            // Reconciliation probe findById finds that the session was indeed committed
            when(sessionRepositoryPort.findById(any()))
                    .thenAnswer(inv -> Optional.of(MediaUploadSession.create(
                            inv.getArgument(0),
                            "user-1",
                            "screen",
                            "ScreenItem",
                            targetId,
                            "idem-uncertain-1",
                            MediaUploadSession.FIXED_PART_SIZE_BYTES * 2,
                            B2_PROVIDER,
                            B2_BUCKET,
                            STORAGE_KEY,
                            allocId,
                            "movie.mp4",
                            "screen",
                            NOW.plus(24, ChronoUnit.HOURS),
                            NOW
                    )));

            MediaUploadSession session = service.allocateSession(
                    "user-1",
                    "screen",
                    "ScreenItem",
                    targetId,
                    "idem-uncertain-1",
                    MediaUploadSession.FIXED_PART_SIZE_BYTES * 2,
                    "movie.mp4",
                    "screen"
            );

            assertThat(session).isNotNull();
            // Must NOT tombstone the allocation because it belongs to the committed session!
            verify(storageKeyAllocationService, never()).tombstoneAllocation(any());
            verify(storageKeyAllocationService, never()).tombstoneAllocationInNewTransaction(any());
        }

        @Test
        @DisplayName("Reconciles uncertain persistence outcome when concurrent winner committed with same idempotency key")
        void reconcilesUncertainPersistenceWhenWinningSessionCommitted() {
            UUID allocId = UUID.randomUUID();
            UUID targetId = UUID.randomUUID();
            UUID winningAllocId = UUID.randomUUID();

            when(sessionRepositoryPort.findByIdempotencyKey("user-1", "idem-uncertain-2"))
                    .thenReturn(Optional.empty()); // Initial probe finds nothing
            when(routingService.generateStorageKey(any(), any(), any(), anyInt())).thenReturn(STORAGE_KEY);
            when(routingService.resolveBucketName(B2_PROVIDER)).thenReturn(B2_BUCKET);

            StorageKeyAllocation mockAlloc = StorageKeyAllocation.reserve(
                    allocId,
                    B2_PROVIDER,
                    B2_BUCKET,
                    STORAGE_KEY,
                    StorageKeyAllocationType.UPLOAD_SESSION,
                    UUID.randomUUID(),
                    NOW
            );
            when(storageKeyAllocationService.reserveKey(any(), any(), any(), any(), any())).thenReturn(mockAlloc);

            // Save throws exception
            when(sessionRepositoryPort.save(any()))
                    .thenThrow(new org.springframework.dao.TransientDataAccessResourceException("Driver error"));

            // Reconciliation probe: findById finds nothing for our tentative session, but findByIdempotencyKey finds winning session
            when(sessionRepositoryPort.findById(any())).thenReturn(Optional.empty());
            MediaUploadSession winningSession = MediaUploadSession.create(
                    UUID.randomUUID(),
                    "user-1",
                    "screen",
                    "ScreenItem",
                    targetId,
                    "idem-uncertain-2",
                    MediaUploadSession.FIXED_PART_SIZE_BYTES * 2,
                    B2_PROVIDER,
                    B2_BUCKET,
                    STORAGE_KEY,
                    winningAllocId,
                    "movie.mp4",
                    "screen",
                    NOW.plus(24, ChronoUnit.HOURS),
                    NOW
            );
            when(sessionRepositoryPort.findByIdempotencyKey("user-1", "idem-uncertain-2"))
                    .thenReturn(Optional.empty()) // initial probe
                    .thenReturn(Optional.of(winningSession)); // reconciliation probe

            MediaUploadSession session = service.allocateSession(
                    "user-1",
                    "screen",
                    "ScreenItem",
                    targetId,
                    "idem-uncertain-2",
                    MediaUploadSession.FIXED_PART_SIZE_BYTES * 2,
                    "movie.mp4",
                    "screen"
            );

            assertThat(session).isSameAs(winningSession);
            // Redundant tentative reservation must be tombstoned
            verify(storageKeyAllocationService).tombstoneAllocationInNewTransaction(allocId);
            // Winning session allocation must NEVER be tombstoned
            verify(storageKeyAllocationService, never()).tombstoneAllocation(winningAllocId);
            verify(storageKeyAllocationService, never()).tombstoneAllocationInNewTransaction(winningAllocId);
        }

        @Test
        @DisplayName("Preserves allocation as RESERVED when both reconciliation probes return empty")
        void preservesAllocationAsReservedWhenBothProbesReturnEmpty() {
            UUID allocId = UUID.randomUUID();
            UUID targetId = UUID.randomUUID();

            when(sessionRepositoryPort.findByIdempotencyKey("user-1", "idem-uncertain-3"))
                    .thenReturn(Optional.empty());
            when(routingService.generateStorageKey(any(), any(), any(), anyInt())).thenReturn(STORAGE_KEY);
            when(routingService.resolveBucketName(B2_PROVIDER)).thenReturn(B2_BUCKET);

            StorageKeyAllocation mockAlloc = StorageKeyAllocation.reserve(
                    allocId,
                    B2_PROVIDER,
                    B2_BUCKET,
                    STORAGE_KEY,
                    StorageKeyAllocationType.UPLOAD_SESSION,
                    UUID.randomUUID(),
                    NOW
            );
            when(storageKeyAllocationService.reserveKey(any(), any(), any(), any(), any())).thenReturn(mockAlloc);

            // Save throws transient exception (ambiguous commit outcome)
            when(sessionRepositoryPort.save(any()))
                    .thenThrow(new org.springframework.dao.TransientDataAccessResourceException("Connection reset before commit acknowledgment"));

            // Both reconciliation probes return empty: negative SELECT results do not prove rollback
            when(sessionRepositoryPort.findById(any())).thenReturn(Optional.empty());
            when(sessionRepositoryPort.findByIdempotencyKey("user-1", "idem-uncertain-3")).thenReturn(Optional.empty());

            org.junit.jupiter.api.Assertions.assertThrows(
                    org.springframework.dao.TransientDataAccessResourceException.class,
                    () -> service.allocateSession(
                            "user-1",
                            "screen",
                            "ScreenItem",
                            targetId,
                            "idem-uncertain-3",
                            MediaUploadSession.FIXED_PART_SIZE_BYTES * 2,
                            "movie.mp4",
                            "screen"
                    )
            );

            // Allocation must NOT be tombstoned! Preserved as RESERVED to guarantee key non-reuse
            verify(storageKeyAllocationService, never()).tombstoneAllocation(any());
            verify(storageKeyAllocationService, never()).tombstoneAllocationInNewTransaction(any());
        }

        @Test
        @DisplayName("Tombstones allocation when domain session construction fails after reservation")
        void tombstonesAllocationWhenDomainSessionConstructionFailsAfterReservation() {
            UUID allocId = UUID.randomUUID();
            UUID targetId = UUID.randomUUID();

            when(sessionRepositoryPort.findByIdempotencyKey("user-1", "idem-domain-fail"))
                    .thenReturn(Optional.empty());
            when(routingService.generateStorageKey(any(), any(), any(), anyInt())).thenReturn(STORAGE_KEY);
            // Routing returns sentinel bucket which triggers IllegalArgumentException in MediaUploadSession.create
            when(routingService.resolveBucketName(B2_PROVIDER)).thenReturn("NONE");

            StorageKeyAllocation mockAlloc = mock(StorageKeyAllocation.class);
            when(mockAlloc.getId()).thenReturn(allocId);
            when(storageKeyAllocationService.reserveKey(any(), any(), any(), any(), any())).thenReturn(mockAlloc);

            org.junit.jupiter.api.Assertions.assertThrows(
                    IllegalArgumentException.class,
                    () -> service.allocateSession(
                            "user-1",
                            "screen",
                            "ScreenItem",
                            targetId,
                            "idem-domain-fail",
                            MediaUploadSession.FIXED_PART_SIZE_BYTES * 2,
                            "movie.mp4",
                            "screen"
                    )
            );

            // Construction failed definitely before persistence: safe independent tombstone
            verify(storageKeyAllocationService).tombstoneAllocationInNewTransaction(allocId);
        }

        @Test
        @DisplayName("Preserves allocation against reuse when database is unreachable during reconciliation probe")
        void preservesAllocationWhenReconciliationProbeFails() {
            UUID allocId = UUID.randomUUID();
            UUID targetId = UUID.randomUUID();

            when(sessionRepositoryPort.findByIdempotencyKey("user-1", "idem-uncertain-4"))
                    .thenReturn(Optional.empty());
            when(routingService.generateStorageKey(any(), any(), any(), anyInt())).thenReturn(STORAGE_KEY);
            when(routingService.resolveBucketName(B2_PROVIDER)).thenReturn(B2_BUCKET);

            StorageKeyAllocation mockAlloc = StorageKeyAllocation.reserve(
                    allocId,
                    B2_PROVIDER,
                    B2_BUCKET,
                    STORAGE_KEY,
                    StorageKeyAllocationType.UPLOAD_SESSION,
                    UUID.randomUUID(),
                    NOW
            );
            when(storageKeyAllocationService.reserveKey(any(), any(), any(), any(), any())).thenReturn(mockAlloc);

            // Save throws error
            org.springframework.dao.TransientDataAccessResourceException primaryEx =
                    new org.springframework.dao.TransientDataAccessResourceException("Connection drop");
            when(sessionRepositoryPort.save(any())).thenThrow(primaryEx);

            // Probe query also throws error (e.g. database host completely dead)
            org.springframework.dao.DataAccessResourceFailureException probeEx =
                    new org.springframework.dao.DataAccessResourceFailureException("Database unreachable");
            when(sessionRepositoryPort.findById(any())).thenThrow(probeEx);

            org.springframework.dao.TransientDataAccessResourceException thrown =
                    org.junit.jupiter.api.Assertions.assertThrows(
                            org.springframework.dao.TransientDataAccessResourceException.class,
                            () -> service.allocateSession(
                                    "user-1",
                                    "screen",
                                    "ScreenItem",
                                    targetId,
                                    "idem-uncertain-4",
                                    MediaUploadSession.FIXED_PART_SIZE_BYTES * 2,
                                    "movie.mp4",
                                    "screen"
                            )
                    );

            assertThat(thrown).isSameAs(primaryEx);
            assertThat(thrown.getSuppressed()).contains(probeEx);

            // Allocation must NOT be tombstoned! It is preserved as RESERVED to guarantee key non-reuse
            verify(storageKeyAllocationService, never()).tombstoneAllocation(any());
            verify(storageKeyAllocationService, never()).tombstoneAllocationInNewTransaction(any());
        }

        @Test
        @DisplayName("Existing winning session is never tombstoned because a retry fails validation")
        void existingWinningSessionIsNeverTombstonedOnRetryFailure() {
            UUID targetId = UUID.randomUUID();
            UUID winningAllocId = UUID.randomUUID();

            MediaUploadSession winningSession = MediaUploadSession.create(
                    UUID.randomUUID(),
                    "user-1",
                    "screen",
                    "ScreenItem",
                    targetId,
                    "idem-retry-conflict",
                    MediaUploadSession.FIXED_PART_SIZE_BYTES * 2,
                    B2_PROVIDER,
                    B2_BUCKET,
                    STORAGE_KEY,
                    winningAllocId,
                    "movie.mp4",
                    "screen",
                    NOW.plus(24, ChronoUnit.HOURS),
                    NOW
            );

            when(sessionRepositoryPort.findByIdempotencyKey("user-1", "idem-retry-conflict"))
                    .thenReturn(Optional.of(winningSession));

            // Caller provides conflicting size on retry
            org.junit.jupiter.api.Assertions.assertThrows(
                    IllegalArgumentException.class,
                    () -> service.allocateSession(
                            "user-1",
                            "screen",
                            "ScreenItem",
                            targetId,
                            "idem-retry-conflict",
                            MediaUploadSession.FIXED_PART_SIZE_BYTES * 4,
                            "movie.mp4",
                            "screen"
                    )
            );

            // Winning session allocation is NEVER tombstoned
            verify(storageKeyAllocationService, never()).tombstoneAllocation(any());
            verify(storageKeyAllocationService, never()).tombstoneAllocationInNewTransaction(any());
        }
    }

    @Nested
    @DisplayName("Lifecycle Tests")
    class LifecycleTests {

        @Test
        @DisplayName("Successfully marks session initiated")
        void initiatesSession() {
            UUID sessionId = UUID.randomUUID();
            UUID allocId = UUID.randomUUID();
            MediaUploadSession session = MediaUploadSession.create(
                    sessionId,
                    "user-1",
                    "screen",
                    "ScreenItem",
                    UUID.randomUUID(),
                    "idem-1",
                    MediaUploadSession.FIXED_PART_SIZE_BYTES * 2,
                    B2_PROVIDER,
                    B2_BUCKET,
                    STORAGE_KEY,
                    allocId,
                    "movie.mp4",
                    "screen",
                    NOW.plus(24, ChronoUnit.HOURS),
                    NOW
            );

            String claimToken = "CLAIM-TEST-1";
            session.claimInitiation(claimToken, NOW.plus(5, ChronoUnit.MINUTES), NOW);

            StorageKeyAllocation allocation = StorageKeyAllocation.reserve(
                    allocId,
                    B2_PROVIDER,
                    B2_BUCKET,
                    STORAGE_KEY,
                    StorageKeyAllocationType.UPLOAD_SESSION,
                    sessionId,
                    NOW
            );

            when(sessionRepositoryPort.findById(sessionId)).thenReturn(Optional.of(session));
            when(storageKeyAllocationService.findById(allocId)).thenReturn(Optional.of(allocation));
            when(sessionRepositoryPort.save(any())).thenAnswer(inv -> inv.getArgument(0));

            MediaUploadSession initiated = service.markInitiated(sessionId, "b2-upload-123", "user-1", claimToken);

            assertThat(initiated.getStatus()).isEqualTo(MediaUploadSessionStatus.INITIATED);
            assertThat(initiated.getProviderUploadId()).isEqualTo("b2-upload-123");
            verify(sessionRepositoryPort).save(session);
        }

        @Test
        @DisplayName("Aborts session and tombstones allocation")
        void abortsSessionAndTombstones() {
            UUID sessionId = UUID.randomUUID();
            UUID allocId = UUID.randomUUID();
            MediaUploadSession session = MediaUploadSession.create(
                    sessionId,
                    "user-1",
                    "screen",
                    "ScreenItem",
                    UUID.randomUUID(),
                    "idem-1",
                    MediaUploadSession.FIXED_PART_SIZE_BYTES * 2,
                    B2_PROVIDER,
                    B2_BUCKET,
                    STORAGE_KEY,
                    allocId,
                    "movie.mp4",
                    "screen",
                    NOW.plus(24, ChronoUnit.HOURS),
                    NOW
            );
            session.markInitiated("b2-upload-123", NOW);

            when(sessionRepositoryPort.findById(sessionId)).thenReturn(Optional.of(session));
            when(sessionRepositoryPort.save(any())).thenAnswer(inv -> inv.getArgument(0));

            MediaUploadSession aborted = service.abortSession(sessionId, "user-1");

            assertThat(aborted.getStatus()).isEqualTo(MediaUploadSessionStatus.ABORTED);
            assertThat(aborted.getCleanupStatus()).isEqualTo(UploadSessionCleanupStatus.PENDING);
            verify(storageKeyAllocationService).tombstoneAllocation(allocId);
            verify(sessionRepositoryPort).save(session);
        }

        @Test
        @DisplayName("Aborts session via abortBySystem maintenance method")
        void abortsSessionViaSystem() {
            UUID sessionId = UUID.randomUUID();
            UUID allocId = UUID.randomUUID();
            MediaUploadSession session = MediaUploadSession.create(
                    sessionId,
                    "user-1",
                    "screen",
                    "ScreenItem",
                    UUID.randomUUID(),
                    "idem-1",
                    MediaUploadSession.FIXED_PART_SIZE_BYTES * 2,
                    B2_PROVIDER,
                    B2_BUCKET,
                    STORAGE_KEY,
                    allocId,
                    "movie.mp4",
                    "screen",
                    NOW.plus(24, ChronoUnit.HOURS),
                    NOW
            );

            when(sessionRepositoryPort.findById(sessionId)).thenReturn(Optional.of(session));
            when(sessionRepositoryPort.save(any())).thenAnswer(inv -> inv.getArgument(0));

            MediaUploadSession aborted = service.abortBySystem(sessionId, "expired session reaper");

            assertThat(aborted.getStatus()).isEqualTo(MediaUploadSessionStatus.ABORTED);
            verify(storageKeyAllocationService).tombstoneAllocation(allocId);
        }

        @Test
        @DisplayName("Rejects markInitiated when requesting principal does not match session owner")
        void rejectsUnauthorizedInitiation() {
            UUID sessionId = UUID.randomUUID();
            UUID allocId = UUID.randomUUID();
            MediaUploadSession session = MediaUploadSession.create(
                    sessionId,
                    "owner-user",
                    "screen",
                    "ScreenItem",
                    UUID.randomUUID(),
                    "idem-1",
                    MediaUploadSession.FIXED_PART_SIZE_BYTES * 2,
                    B2_PROVIDER,
                    B2_BUCKET,
                    STORAGE_KEY,
                    allocId,
                    "movie.mp4",
                    "screen",
                    NOW.plus(24, ChronoUnit.HOURS),
                    NOW
            );
            String claimToken = "CLAIM-TEST-1";
            session.claimInitiation(claimToken, NOW.plus(Duration.ofMinutes(5)), NOW);

            when(sessionRepositoryPort.findById(sessionId)).thenReturn(Optional.of(session));

            org.junit.jupiter.api.Assertions.assertThrows(
                    IllegalStateException.class,
                    () -> service.markInitiated(sessionId, "b2-upload-123", "attacker-user", claimToken)
            );
        }

        @Test
        @DisplayName("Rejects legacy 3-arg markInitiated bypass without claim token")
        void rejectsLegacyMarkInitiatedBypass() {
            UUID sessionId = UUID.randomUUID();
            org.junit.jupiter.api.Assertions.assertThrows(
                    UnsupportedOperationException.class,
                    () -> service.markInitiated(sessionId, "b2-upload-123", "user-1")
            );
        }

        @Test
        @DisplayName("Rejects abortSession when requesting principal does not match session owner")
        void rejectsUnauthorizedAbort() {
            UUID sessionId = UUID.randomUUID();
            MediaUploadSession session = MediaUploadSession.create(
                    sessionId,
                    "owner-user",
                    "screen",
                    "ScreenItem",
                    UUID.randomUUID(),
                    "idem-1",
                    MediaUploadSession.FIXED_PART_SIZE_BYTES * 2,
                    B2_PROVIDER,
                    B2_BUCKET,
                    STORAGE_KEY,
                    UUID.randomUUID(),
                    "movie.mp4",
                    "screen",
                    NOW.plus(24, ChronoUnit.HOURS),
                    NOW
            );

            when(sessionRepositoryPort.findById(sessionId)).thenReturn(Optional.of(session));

            org.junit.jupiter.api.Assertions.assertThrows(
                    IllegalStateException.class,
                    () -> service.abortSession(sessionId, "attacker-user")
            );
        }
    }
}
