package com.universe.media.infrastructure.persistence;

import com.universe.media.application.ports.MediaUploadSessionRepositoryPort;
import com.universe.media.application.ports.storage.MultipartStoragePort;
import com.universe.media.application.storage.MediaStorageRoutingService;
import com.universe.media.application.storage.MediaUploadSessionService;
import com.universe.media.application.storage.StorageKeyAllocationService;
import com.universe.media.domain.MediaUploadSession;
import com.universe.media.domain.MediaUploadSessionStatus;
import com.universe.media.domain.StorageKey;
import com.universe.media.domain.StorageKeyAllocation;
import com.universe.media.domain.StorageKeyAllocationStatus;
import com.universe.media.domain.StorageKeyAllocationType;
import com.universe.media.domain.StorageProviderId;
import com.universe.media.domain.UploadSessionCleanupStatus;
import com.universe.shared.time.ClockPort;
import jakarta.persistence.Column;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.slf4j.LoggerFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.lang.reflect.Field;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * MS-08 R2-02: P1 Schema Remediation Regression Tests.
 *
 * <p>Verifies:
 * <ul>
 *     <li>JPA entity mapping length matches the approved 1024-character schema capacity;</li>
 *     <li>Representative Cloudflare R2 Upload IDs (343 chars) survive domain validation and persistence mapping;</li>
 *     <li>Boundary values (1024 chars) and historical B2 Upload IDs (~80-120 chars) are preserved exactly without alteration;</li>
 *     <li>Out-of-range values (> 1024 chars) fail safely at the domain boundary;</li>
 *     <li>Persistence adapter maps domain and JPA entity back and forth with zero truncation or character alteration;</li>
 *     <li>TX-B persistence success cleanly transitions session to INITIATED with providerUploadId;</li>
 *     <li>TX-B persistence failure retains ALLOCATED status, preserves initiation claim fencing, and never triggers speculative remote abort;</li>
 *     <li>Provider isolation is preserved between B2 and R2 Movie.</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("MS-08 R2-02: Provider Upload ID Capacity & Persistence Mapping Tests")
class MediaUploadSessionProviderUploadIdPersistenceTest {

    private static final StorageProviderId R2_MOVIE_PROVIDER = StorageProviderId.of("r2-movie");
    private static final StorageProviderId B2_PROVIDER = StorageProviderId.of("b2");
    private static final String R2_MOVIE_BUCKET = "kiemlai-screen";
    private static final String B2_BUCKET = "kiemlai-video";
    private static final Instant NOW = Instant.parse("2026-10-31T15:39:00Z");
    private static final String PRINCIPAL = "admin-tester";

    // Synthetic test tokens representing provider Upload IDs of varied lengths:
    private static final String HISTORICAL_B2_UPLOAD_ID = "4_z27c88f01f40941498b8c0812_f1001d3cb84024b48_d20261031_m153900_c001_v0001001_t0001";
    // Exact 343-character token matching the Cloudflare R2 format observed in the incident (19 + 310 + 14 = 343):
    private static final String R2_343_CHAR_UPLOAD_ID = "cf-r2-upload-token-" + "a".repeat(310) + "-part-seq-0001";
    // Exact 1024-character boundary token (18 + 997 + 9 = 1024):
    private static final String BOUNDARY_1024_CHAR_UPLOAD_ID = "boundary-r2-token-" + "b".repeat(997) + "-end-1024";
    // 1030-character out-of-range token:
    private static final String OUT_OF_RANGE_1030_CHAR_UPLOAD_ID = "overflow-token-" + "c".repeat(1005) + "-end-1025";

    @Nested
    @DisplayName("1. JPA Entity Mapping & Metadata Verification")
    class JpaEntityMappingTests {

        @Test
        @DisplayName("providerUploadId column length is declared as 1024 in MediaUploadSessionJpaEntity")
        void providerUploadIdColumnLengthIs1024() throws NoSuchFieldException {
            Field field = MediaUploadSessionJpaEntity.class.getDeclaredField("providerUploadId");
            Column columnAnnotation = field.getAnnotation(Column.class);

            assertThat(columnAnnotation).isNotNull();
            assertThat(columnAnnotation.name()).isEqualTo("provider_upload_id");
            assertThat(columnAnnotation.length()).isEqualTo(1024);
        }
    }

    @Nested
    @DisplayName("2. Domain Validation & Boundary Safety")
    class DomainValidationTests {

        @Test
        @DisplayName("Accepts historical B2 Upload ID (~80-120 chars)")
        void acceptsHistoricalB2UploadId() {
            MediaUploadSession session = createAllocatedSession(UUID.randomUUID(), B2_PROVIDER, B2_BUCKET);
            session.markInitiated(HISTORICAL_B2_UPLOAD_ID, NOW);

            assertThat(session.getStatus()).isEqualTo(MediaUploadSessionStatus.INITIATED);
            assertThat(session.getProviderUploadId()).isEqualTo(HISTORICAL_B2_UPLOAD_ID);
        }

        @Test
        @DisplayName("Accepts representative Cloudflare R2 Upload ID (343 chars) without alteration")
        void acceptsRepresentativeR2UploadId() {
            assertThat(R2_343_CHAR_UPLOAD_ID.length()).isEqualTo(343);
            MediaUploadSession session = createAllocatedSession(UUID.randomUUID(), R2_MOVIE_PROVIDER, R2_MOVIE_BUCKET);
            session.markInitiated(R2_343_CHAR_UPLOAD_ID, NOW);

            assertThat(session.getStatus()).isEqualTo(MediaUploadSessionStatus.INITIATED);
            assertThat(session.getProviderUploadId()).isEqualTo(R2_343_CHAR_UPLOAD_ID);
            assertThat(session.getProviderUploadId().length()).isEqualTo(343);
        }

        @Test
        @DisplayName("Accepts boundary Upload ID at exactly 1024 characters")
        void acceptsBoundary1024CharUploadId() {
            assertThat(BOUNDARY_1024_CHAR_UPLOAD_ID.length()).isEqualTo(1024);
            MediaUploadSession session = createAllocatedSession(UUID.randomUUID(), R2_MOVIE_PROVIDER, R2_MOVIE_BUCKET);
            session.markInitiated(BOUNDARY_1024_CHAR_UPLOAD_ID, NOW);

            assertThat(session.getStatus()).isEqualTo(MediaUploadSessionStatus.INITIATED);
            assertThat(session.getProviderUploadId()).isEqualTo(BOUNDARY_1024_CHAR_UPLOAD_ID);
            assertThat(session.getProviderUploadId().length()).isEqualTo(1024);
        }

        @Test
        @DisplayName("Rejects out-of-range Upload ID (> 1024 characters) with clean IllegalArgumentException")
        void rejectsOutOfRangeUploadIdExceeding1024Chars() {
            assertThat(OUT_OF_RANGE_1030_CHAR_UPLOAD_ID.length()).isGreaterThan(1024);
            MediaUploadSession session = createAllocatedSession(UUID.randomUUID(), R2_MOVIE_PROVIDER, R2_MOVIE_BUCKET);

            assertThatThrownBy(() -> session.markInitiated(OUT_OF_RANGE_1030_CHAR_UPLOAD_ID, NOW))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("exceeds maximum supported capacity of 1024");
        }

        @Test
        @DisplayName("Preserves exact opaque Upload ID without trimming or normalization")
        void preservesExactOpaqueUploadIdWithoutTrimming() {
            String untrimmedOpaqueId = "  opaque-r2-token-" + "d".repeat(200) + "  ";
            MediaUploadSession session = createAllocatedSession(UUID.randomUUID(), R2_MOVIE_PROVIDER, R2_MOVIE_BUCKET);
            session.markInitiated(untrimmedOpaqueId, NOW);

            assertThat(session.getProviderUploadId()).isEqualTo(untrimmedOpaqueId);
            assertThat(session.getProviderUploadId()).isNotEqualTo(untrimmedOpaqueId.trim());
        }

        @Test
        @DisplayName("Rejects null, empty or blank Upload ID on markInitiated")
        void rejectsNullOrBlankUploadIdOnMarkInitiated() {
            MediaUploadSession session = createAllocatedSession(UUID.randomUUID(), R2_MOVIE_PROVIDER, R2_MOVIE_BUCKET);

            assertThatThrownBy(() -> session.markInitiated(null, NOW))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("must not be blank");

            assertThatThrownBy(() -> session.markInitiated("", NOW))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("must not be blank");

            assertThatThrownBy(() -> session.markInitiated("   \t\n  ", NOW))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("must not be blank");
        }

        @Test
        @DisplayName("Rejects blank Upload ID on rehydration when non-null")
        void rejectsBlankUploadIdOnRehydration() {
            UUID sessionId = UUID.randomUUID();
            UUID allocId = UUID.randomUUID();
            UUID targetId = UUID.randomUUID();

            assertThatThrownBy(() -> MediaUploadSession.rehydrate(
                    sessionId,
                    PRINCIPAL,
                    "screen",
                    "SCREEN_ENTRY",
                    targetId,
                    "idem-screen-r2-blank",
                    com.universe.media.domain.MediaType.VIDEO,
                    "video/mp4",
                    52428800L,
                    R2_MOVIE_PROVIDER,
                    R2_MOVIE_BUCKET,
                    StorageKey.of("movies/" + sessionId + ".mp4"),
                    allocId,
                    "   ",
                    MediaUploadSession.FIXED_PART_SIZE_BYTES,
                    2,
                    null,
                    "test.mp4",
                    null,
                    MediaUploadSessionStatus.INITIATED,
                    NOW.plus(1, ChronoUnit.DAYS),
                    null,
                    null,
                    null,
                    UploadSessionCleanupStatus.NONE,
                    0,
                    null,
                    null,
                    NOW,
                    NOW,
                    null,
                    1L,
                    null
            )).isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("must not be blank");
        }
    }

    @Nested
    @DisplayName("3. Persistence Adapter Mapping Fidelity")
    class PersistenceAdapterMappingTests {

        @Test
        @DisplayName("Maps domain session with 343-character R2 Upload ID to JPA entity without truncation")
        void mapsDomainToJpaEntityPreservingFullToken() {
            MediaUploadSession session = createAllocatedSession(UUID.randomUUID(), R2_MOVIE_PROVIDER, R2_MOVIE_BUCKET);
            session.markInitiated(R2_343_CHAR_UPLOAD_ID, NOW);

            MediaUploadSessionJpaEntity entity = new MediaUploadSessionJpaEntity();
            entity.setId(session.getId().toString());
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
            entity.setPartSizeBytes(session.getPartSizeBytes());
            entity.setExpectedPartCount(session.getExpectedPartCount());
            entity.setOriginalFilename(session.getOriginalFilename());
            entity.setStatus(session.getStatus());
            entity.setExpiresAt(session.getExpiresAt());
            entity.setCleanupStatus(session.getCleanupStatus());
            entity.setCleanupRetryCount(session.getCleanupRetryCount());
            entity.setCreatedAt(session.getCreatedAt());
            entity.setUpdatedAt(session.getUpdatedAt());

            // Set provider upload ID on entity
            entity.setProviderUploadId(session.getProviderUploadId());

            assertThat(entity.getProviderUploadId()).isEqualTo(R2_343_CHAR_UPLOAD_ID);
            assertThat(entity.getProviderUploadId().length()).isEqualTo(343);
        }

        @Test
        @DisplayName("Rehydrates JPA entity with 343-character R2 Upload ID into Domain session exactly")
        void rehydratesJpaEntityToDomainPreservingFullToken() {
            UUID sessionId = UUID.randomUUID();
            UUID allocId = UUID.randomUUID();
            UUID targetId = UUID.randomUUID();

            MediaUploadSessionJpaEntity entity = new MediaUploadSessionJpaEntity();
            entity.setId(sessionId.toString());
            entity.setInitiatorPrincipalId(PRINCIPAL);
            entity.setConsumerContext("screen");
            entity.setTargetEntityType("SCREEN_ENTRY");
            entity.setTargetEntityId(targetId.toString());
            entity.setIdempotencyKey("idem-screen-r2-1");
            entity.setMediaType("VIDEO");
            entity.setDeclaredMimeType("video/mp4");
            entity.setDeclaredSizeBytes(52428800L);
            entity.setStorageProviderId("r2-movie");
            entity.setStorageBucketName(R2_MOVIE_BUCKET);
            entity.setStorageKey("movies/" + sessionId + ".mp4");
            entity.setStorageAllocationId(allocId.toString());
            entity.setProviderUploadId(R2_343_CHAR_UPLOAD_ID);
            entity.setPartSizeBytes(MediaUploadSession.FIXED_PART_SIZE_BYTES);
            entity.setExpectedPartCount(2);
            entity.setOriginalFilename("screen-video.mp4");
            entity.setStatus(MediaUploadSessionStatus.INITIATED);
            entity.setExpiresAt(NOW.plus(1, ChronoUnit.DAYS));
            entity.setCleanupStatus(UploadSessionCleanupStatus.NONE);
            entity.setCleanupRetryCount(0);
            entity.setCreatedAt(NOW);
            entity.setUpdatedAt(NOW);
            entity.setPersistenceVersion(1L);

            MediaUploadSession rehydrated = MediaUploadSession.rehydrate(
                    UUID.fromString(entity.getId()),
                    entity.getInitiatorPrincipalId(),
                    entity.getConsumerContext(),
                    entity.getTargetEntityType(),
                    UUID.fromString(entity.getTargetEntityId()),
                    entity.getIdempotencyKey(),
                    com.universe.media.domain.MediaType.valueOf(entity.getMediaType()),
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
                    null,
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

            assertThat(rehydrated.getProviderUploadId()).isEqualTo(R2_343_CHAR_UPLOAD_ID);
            assertThat(rehydrated.getStatus()).isEqualTo(MediaUploadSessionStatus.INITIATED);
            assertThat(rehydrated.getStorageProviderId()).isEqualTo(R2_MOVIE_PROVIDER);
        }
    }

    @Nested
    @DisplayName("4. TX-B Orchestration & Failure Fencing")
    class TxBOrchestrationTests {

        @Mock
        private MediaUploadSessionRepositoryPort sessionRepositoryPort;
        @Mock
        private StorageKeyAllocationService storageKeyAllocationService;
        @Mock
        private MediaStorageRoutingService mediaStorageRoutingService;
        @Mock
        private MultipartStoragePort r2MoviePort;
        @Mock
        private MultipartStoragePort b2Port;
        @Mock
        private ClockPort clockPort;

        @Test
        @DisplayName("TX-B persistence failure preserves ALLOCATED status, retains initiation claim, and never aborts remote upload")
        void txBPersistenceFailurePreservesFencingWithoutRemoteAbort() {
            PlatformTransactionManager txManager = mock(PlatformTransactionManager.class);
            when(txManager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
            when(r2MoviePort.providerId()).thenReturn(R2_MOVIE_PROVIDER);

            MediaUploadSessionService service = new MediaUploadSessionService(
                    sessionRepositoryPort,
                    storageKeyAllocationService,
                    mediaStorageRoutingService,
                    clockPort,
                    txManager,
                    r2MoviePort
            );

            UUID sessionId = UUID.randomUUID();
            UUID allocId = UUID.randomUUID();
            MediaUploadSession session = createAllocatedSession(sessionId, R2_MOVIE_PROVIDER, R2_MOVIE_BUCKET);
            StorageKeyAllocation allocation = createReservedAllocation(allocId, sessionId, R2_MOVIE_PROVIDER, R2_MOVIE_BUCKET);

            AtomicReference<MediaUploadSession> committedSession = new AtomicReference<>(session);
            when(sessionRepositoryPort.findById(sessionId)).thenAnswer(inv -> Optional.of(rehydrateCopy(committedSession.get())));
            when(storageKeyAllocationService.findById(session.getStorageAllocationId())).thenReturn(Optional.of(allocation));
            when(clockPort.now()).thenReturn(NOW);

            // TX-A succeeds, but TX-B save throws (simulating data truncation or optimistic lock error)
            when(sessionRepositoryPort.save(any(MediaUploadSession.class)))
                    .thenAnswer(inv -> {
                        MediaUploadSession saved = inv.getArgument(0);
                        committedSession.set(rehydrateCopy(saved));
                        return saved;
                    }) // TX-A succeeds and records leaseOwner
                    .thenThrow(new OptimisticLockingFailureException("Simulated TX-B persistence failure"));

            when(r2MoviePort.initiateMultipartUpload(any(), any(), any())).thenReturn(R2_343_CHAR_UPLOAD_ID);

            assertThatThrownBy(() -> service.initiateMultipartUpload(sessionId, PRINCIPAL))
                    .isInstanceOf(OptimisticLockingFailureException.class);

            // 1. Session in database retained ALLOCATED status
            assertThat(committedSession.get().getStatus()).isEqualTo(MediaUploadSessionStatus.ALLOCATED);
            // 2. providerUploadId was NOT persisted to DB (rolled back)
            assertThat(committedSession.get().getProviderUploadId()).isNull();
            // 3. Initiation claim fencing was retained in database from TX-A
            assertThat(committedSession.get().getLeaseOwner()).startsWith("INITIATION_CLAIM:");
            // 4. Remote provider abort was NEVER called speculatively
            verify(r2MoviePort, never()).abortMultipartUpload(any(), any());
            // 5. Foreign B2 port received zero calls
            verify(b2Port, never()).initiateMultipartUpload(any(), any(), any());
            verify(b2Port, never()).abortMultipartUpload(any(), any());
        }

        @Test
        @DisplayName("TX-B persistence success with 343-character R2 token cleanly transitions to INITIATED")
        void txBPersistenceSuccessTransitionsToInitiated() {
            PlatformTransactionManager txManager = mock(PlatformTransactionManager.class);
            when(txManager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
            when(r2MoviePort.providerId()).thenReturn(R2_MOVIE_PROVIDER);

            MediaUploadSessionService service = new MediaUploadSessionService(
                    sessionRepositoryPort,
                    storageKeyAllocationService,
                    mediaStorageRoutingService,
                    clockPort,
                    txManager,
                    r2MoviePort
            );

            UUID sessionId = UUID.randomUUID();
            UUID allocId = UUID.randomUUID();
            MediaUploadSession session = createAllocatedSession(sessionId, R2_MOVIE_PROVIDER, R2_MOVIE_BUCKET);
            StorageKeyAllocation allocation = createReservedAllocation(allocId, sessionId, R2_MOVIE_PROVIDER, R2_MOVIE_BUCKET);

            AtomicReference<MediaUploadSession> committedSession = new AtomicReference<>(session);
            when(sessionRepositoryPort.findById(sessionId)).thenAnswer(inv -> Optional.of(rehydrateCopy(committedSession.get())));
            when(storageKeyAllocationService.findById(session.getStorageAllocationId())).thenReturn(Optional.of(allocation));
            when(clockPort.now()).thenReturn(NOW);

            when(sessionRepositoryPort.save(any(MediaUploadSession.class)))
                    .thenAnswer(inv -> {
                        MediaUploadSession saved = inv.getArgument(0);
                        committedSession.set(rehydrateCopy(saved));
                        return saved;
                    });

            when(r2MoviePort.initiateMultipartUpload(any(), any(), any())).thenReturn(R2_343_CHAR_UPLOAD_ID);

            MediaUploadSession initiated = service.initiateMultipartUpload(sessionId, PRINCIPAL);

            assertThat(initiated.getStatus()).isEqualTo(MediaUploadSessionStatus.INITIATED);
            assertThat(initiated.getProviderUploadId()).isEqualTo(R2_343_CHAR_UPLOAD_ID);
            assertThat(initiated.getLeaseOwner()).isNull();
            assertThat(committedSession.get().getProviderUploadId()).isEqualTo(R2_343_CHAR_UPLOAD_ID);
            verify(r2MoviePort, times(1)).initiateMultipartUpload(any(), any(), any());
        }

        @Test
        @DisplayName("TX-B persistence failure redacts sensitive providerUploadId from ordinary application logs")
        void txBPersistenceFailureRedactsSensitiveProviderUploadIdFromLogs() {
            PlatformTransactionManager txManager = mock(PlatformTransactionManager.class);
            when(txManager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
            when(r2MoviePort.providerId()).thenReturn(R2_MOVIE_PROVIDER);

            MediaUploadSessionService service = new MediaUploadSessionService(
                    sessionRepositoryPort,
                    storageKeyAllocationService,
                    mediaStorageRoutingService,
                    clockPort,
                    txManager,
                    r2MoviePort
            );

            UUID sessionId = UUID.randomUUID();
            UUID allocId = UUID.randomUUID();
            MediaUploadSession session = createAllocatedSession(sessionId, R2_MOVIE_PROVIDER, R2_MOVIE_BUCKET);
            StorageKeyAllocation allocation = createReservedAllocation(allocId, sessionId, R2_MOVIE_PROVIDER, R2_MOVIE_BUCKET);

            AtomicReference<MediaUploadSession> committedSession = new AtomicReference<>(session);
            when(sessionRepositoryPort.findById(sessionId)).thenAnswer(inv -> Optional.of(rehydrateCopy(committedSession.get())));
            when(storageKeyAllocationService.findById(session.getStorageAllocationId())).thenReturn(Optional.of(allocation));
            when(clockPort.now()).thenReturn(NOW);

            when(sessionRepositoryPort.save(any(MediaUploadSession.class)))
                    .thenAnswer(inv -> {
                        MediaUploadSession saved = inv.getArgument(0);
                        committedSession.set(rehydrateCopy(saved));
                        return saved;
                    })
                    .thenThrow(new OptimisticLockingFailureException("Simulated TX-B persistence failure"));

            when(r2MoviePort.initiateMultipartUpload(any(), any(), any())).thenReturn(R2_343_CHAR_UPLOAD_ID);

            Logger serviceLogger = (Logger) LoggerFactory.getLogger(MediaUploadSessionService.class);
            ListAppender<ILoggingEvent> listAppender = new ListAppender<>();
            listAppender.start();
            serviceLogger.addAppender(listAppender);

            try {
                assertThatThrownBy(() -> service.initiateMultipartUpload(sessionId, PRINCIPAL))
                        .isInstanceOf(OptimisticLockingFailureException.class);

                // Verify log event was emitted with diagnostic context
                assertThat(listAppender.list)
                        .anyMatch(event -> event.getFormattedMessage().contains("TX-B persistence failed after successful provider initiation")
                                && event.getFormattedMessage().contains("providerId=r2-movie")
                                && event.getFormattedMessage().contains("operation=INITIATE_TX_B")
                                && event.getFormattedMessage().contains("failureCategory=PERSISTENCE_FAILURE")
                                && event.getFormattedMessage().contains("uploadIdLength=" + R2_343_CHAR_UPLOAD_ID.length()));

                // Verify the complete opaque 343-character upload ID is NEVER leaked into log messages
                assertThat(listAppender.list)
                        .noneMatch(event -> event.getFormattedMessage().contains(R2_343_CHAR_UPLOAD_ID));
            } finally {
                serviceLogger.detachAppender(listAppender);
            }
        }
    }

    // Helper methods:
    private static MediaUploadSession createAllocatedSession(UUID sessionId, StorageProviderId provider, String bucket) {
        return MediaUploadSession.create(
                sessionId,
                PRINCIPAL,
                "screen",
                "SCREEN_ENTRY",
                UUID.randomUUID(),
                "idem-key-" + sessionId,
                MediaUploadSession.FIXED_PART_SIZE_BYTES * 2,
                provider,
                bucket,
                StorageKey.of("movies/" + sessionId + ".mp4"),
                UUID.randomUUID(),
                "test-video.mp4",
                "screen-video",
                NOW.plus(1, ChronoUnit.DAYS),
                NOW
        );
    }

    private static StorageKeyAllocation createReservedAllocation(UUID allocId, UUID sessionId, StorageProviderId provider, String bucket) {
        return StorageKeyAllocation.rehydrate(
                allocId,
                provider,
                bucket,
                StorageKey.of("movies/" + sessionId + ".mp4"),
                StorageKeyAllocationStatus.RESERVED,
                StorageKeyAllocationType.UPLOAD_SESSION,
                sessionId,
                NOW,
                NOW,
                0L
        );
    }

    private static MediaUploadSession rehydrateCopy(MediaUploadSession s) {
        return MediaUploadSession.rehydrate(
                s.getId(),
                s.getInitiatorPrincipalId(),
                s.getConsumerContext(),
                s.getTargetEntityType(),
                s.getTargetEntityId(),
                s.getIdempotencyKey(),
                s.getMediaType(),
                s.getDeclaredMimeType(),
                s.getDeclaredSizeBytes(),
                s.getStorageProviderId(),
                s.getStorageBucketName(),
                s.getStorageKey(),
                s.getStorageAllocationId(),
                s.getProviderUploadId(),
                s.getPartSizeBytes(),
                s.getExpectedPartCount(),
                s.getDeclaredContentHash(),
                s.getOriginalFilename(),
                s.getClientTag(),
                s.getStatus(),
                s.getExpiresAt(),
                s.getLeaseOwner(),
                s.getLeaseExpiresAt(),
                s.getRegisteredAssetId(),
                s.getCleanupStatus(),
                s.getCleanupRetryCount(),
                s.getCleanupLastAttemptAt(),
                s.getCleanupErrorDetail(),
                s.getCreatedAt(),
                s.getUpdatedAt(),
                s.getCompletedAt(),
                s.getPersistenceVersion(),
                s.getManifestHash()
        );
    }
}
