package com.universe.media.application.storage;

import com.universe.media.application.exceptions.StorageException;
import com.universe.media.application.ports.MediaAssetRepositoryPort;
import com.universe.media.application.ports.MediaAssetVersionRepositoryPort;
import com.universe.media.application.ports.MediaUploadSessionRepositoryPort;
import com.universe.media.application.ports.storage.MultipartStoragePort;
import com.universe.media.domain.MediaUploadSession;
import com.universe.media.domain.MediaUploadSessionStatus;
import com.universe.media.domain.StorageKey;
import com.universe.media.domain.StorageKeyAllocation;
import com.universe.media.domain.StorageKeyAllocationStatus;
import com.universe.media.domain.StorageKeyAllocationType;
import com.universe.media.domain.StorageProviderId;
import com.universe.shared.time.ClockPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("MediaUploadSessionService — S2D-1 Presigned Part URL Tests")
class MediaUploadSessionS2d1Test {

    @Mock
    private MediaUploadSessionRepositoryPort sessionRepositoryPort;

    @Mock
    private StorageKeyAllocationService storageKeyAllocationService;

    @Mock
    private MediaStorageRoutingService mediaStorageRoutingService;

    @Mock
    private ClockPort clockPort;

    @Mock
    private PlatformTransactionManager transactionManager;

    @Mock
    private MultipartStoragePort multipartStoragePort;

    @Mock
    private MediaAssetRepositoryPort mediaAssetRepositoryPort;

    @Mock
    private MediaAssetVersionRepositoryPort mediaAssetVersionRepositoryPort;

    private MediaUploadSessionService service;

    private static final StorageProviderId B2_PROVIDER = StorageProviderId.of("b2");
    private static final String B2_BUCKET = "kiemlai-video";
    private static final StorageKey STORAGE_KEY = StorageKey.of("media/video/test-file.mp4");
    private static final String UPLOAD_ID = "upload-b2-session-12345";
    private static final String PRINCIPAL_ID = "admin-user-001";
    private static final String WRONG_PRINCIPAL_ID = "unauthorized-user-999";
    private static final Instant BASE_NOW = Instant.parse("2026-10-10T12:00:00Z");

    @BeforeEach
    void setUp() {
        org.mockito.Mockito.lenient().when(multipartStoragePort.providerId()).thenReturn(StorageProviderId.of("b2"));
        service = new MediaUploadSessionService(
                sessionRepositoryPort,
                storageKeyAllocationService,
                mediaStorageRoutingService,
                clockPort,
                transactionManager,
                multipartStoragePort,
                mediaAssetRepositoryPort,
                mediaAssetVersionRepositoryPort
        );
        org.mockito.Mockito.lenient().when(clockPort.now()).thenReturn(BASE_NOW);
    }

    private MediaUploadSession createInitiatedSession(UUID sessionId, UUID allocationId, long sizeBytes, Instant expiresAt) {
        MediaUploadSession session = MediaUploadSession.create(
                sessionId,
                PRINCIPAL_ID,
                "screen",
                "ScreenEntry",
                UUID.randomUUID(),
                "idem-" + sessionId,
                sizeBytes,
                B2_PROVIDER,
                B2_BUCKET,
                STORAGE_KEY,
                allocationId,
                "sample-video.mp4",
                null,
                expiresAt,
                BASE_NOW.minus(Duration.ofHours(1))
        );
        session.claimInitiation("INITIATION_CLAIM:" + UUID.randomUUID(), BASE_NOW.plus(Duration.ofMinutes(5)), BASE_NOW.minus(Duration.ofMinutes(30)));
        session.markInitiated(UPLOAD_ID, BASE_NOW.minus(Duration.ofMinutes(25)));
        return session;
    }

    private StorageKeyAllocation createReservedAllocation(UUID allocationId, UUID sessionId) {
        return StorageKeyAllocation.reserve(
                allocationId,
                B2_PROVIDER,
                B2_BUCKET,
                STORAGE_KEY,
                StorageKeyAllocationType.UPLOAD_SESSION,
                sessionId,
                BASE_NOW.minus(Duration.ofHours(1))
        );
    }

    @Nested
    @DisplayName("Happy Path & Valid Issuance")
    class HappyPathTests {

        @Test
        @DisplayName("Successfully generates presigned part upload URL with default expiry")
        void shouldGeneratePresignedPartUploadUrlWithDefaultExpiry() {
            UUID sessionId = UUID.randomUUID();
            UUID allocationId = UUID.randomUUID();
            Instant expiresAt = BASE_NOW.plus(Duration.ofHours(24));
            long sizeBytes = MediaUploadSession.FIXED_PART_SIZE_BYTES * 3; // 3 parts expected

            MediaUploadSession session = createInitiatedSession(sessionId, allocationId, sizeBytes, expiresAt);
            StorageKeyAllocation allocation = createReservedAllocation(allocationId, sessionId);

            when(clockPort.now()).thenReturn(BASE_NOW);
            when(sessionRepositoryPort.findById(sessionId)).thenReturn(Optional.of(session));
            when(storageKeyAllocationService.findById(allocationId)).thenReturn(Optional.of(allocation));
            when(multipartStoragePort.generatePresignedPartUploadUrl(STORAGE_KEY, UPLOAD_ID, 2, MediaUploadSessionService.DEFAULT_PART_URL_EXPIRY))
                    .thenReturn("https://b2.example.com/part-2-presigned-url");

            String presignedUrl = service.generatePresignedPartUploadUrl(sessionId, 2, PRINCIPAL_ID);

            assertThat(presignedUrl).isEqualTo("https://b2.example.com/part-2-presigned-url");
            verify(multipartStoragePort).generatePresignedPartUploadUrl(STORAGE_KEY, UPLOAD_ID, 2, MediaUploadSessionService.DEFAULT_PART_URL_EXPIRY);
        }

        @Test
        @DisplayName("Successfully generates presigned part upload URL with custom valid expiry")
        void shouldGeneratePresignedPartUploadUrlWithCustomExpiry() {
            UUID sessionId = UUID.randomUUID();
            UUID allocationId = UUID.randomUUID();
            Instant expiresAt = BASE_NOW.plus(Duration.ofHours(24));
            long sizeBytes = MediaUploadSession.FIXED_PART_SIZE_BYTES; // 1 part expected
            Duration customExpiry = Duration.ofMinutes(30);

            MediaUploadSession session = createInitiatedSession(sessionId, allocationId, sizeBytes, expiresAt);
            StorageKeyAllocation allocation = createReservedAllocation(allocationId, sessionId);

            when(clockPort.now()).thenReturn(BASE_NOW);
            when(sessionRepositoryPort.findById(sessionId)).thenReturn(Optional.of(session));
            when(storageKeyAllocationService.findById(allocationId)).thenReturn(Optional.of(allocation));
            when(multipartStoragePort.generatePresignedPartUploadUrl(STORAGE_KEY, UPLOAD_ID, 1, customExpiry))
                    .thenReturn("https://b2.example.com/part-1-presigned-url");

            String presignedUrl = service.generatePresignedPartUploadUrl(sessionId, 1, PRINCIPAL_ID, customExpiry);

            assertThat(presignedUrl).isEqualTo("https://b2.example.com/part-1-presigned-url");
            verify(multipartStoragePort).generatePresignedPartUploadUrl(STORAGE_KEY, UPLOAD_ID, 1, customExpiry);
        }

        @Test
        @DisplayName("Preserves idempotent refresh behavior when invoked multiple times for same part")
        void shouldPreserveIdempotentRefreshBehavior() {
            UUID sessionId = UUID.randomUUID();
            UUID allocationId = UUID.randomUUID();
            Instant expiresAt = BASE_NOW.plus(Duration.ofHours(24));
            long sizeBytes = MediaUploadSession.FIXED_PART_SIZE_BYTES * 2;

            MediaUploadSession session = createInitiatedSession(sessionId, allocationId, sizeBytes, expiresAt);
            StorageKeyAllocation allocation = createReservedAllocation(allocationId, sessionId);

            when(clockPort.now()).thenReturn(BASE_NOW);
            when(sessionRepositoryPort.findById(sessionId)).thenReturn(Optional.of(session));
            when(storageKeyAllocationService.findById(allocationId)).thenReturn(Optional.of(allocation));
            when(multipartStoragePort.generatePresignedPartUploadUrl(STORAGE_KEY, UPLOAD_ID, 1, MediaUploadSessionService.DEFAULT_PART_URL_EXPIRY))
                    .thenReturn("https://b2.example.com/part-1-refresh-1")
                    .thenReturn("https://b2.example.com/part-1-refresh-2");

            String url1 = service.generatePresignedPartUploadUrl(sessionId, 1, PRINCIPAL_ID);
            String url2 = service.generatePresignedPartUploadUrl(sessionId, 1, PRINCIPAL_ID);

            assertThat(url1).isEqualTo("https://b2.example.com/part-1-refresh-1");
            assertThat(url2).isEqualTo("https://b2.example.com/part-1-refresh-2");
            assertThat(session.getStatus()).isEqualTo(MediaUploadSessionStatus.INITIATED);
            assertThat(allocation.getStatus()).isEqualTo(StorageKeyAllocationStatus.RESERVED);
        }
    }

    @Nested
    @DisplayName("Authorization & Ownership Validation")
    class AuthorizationTests {

        @Test
        @DisplayName("Rejects request when caller principal does not match session initiator")
        void shouldRejectUnauthorizedPrincipal() {
            UUID sessionId = UUID.randomUUID();
            UUID allocationId = UUID.randomUUID();
            Instant expiresAt = BASE_NOW.plus(Duration.ofHours(24));
            MediaUploadSession session = createInitiatedSession(sessionId, allocationId, MediaUploadSession.FIXED_PART_SIZE_BYTES, expiresAt);

            when(sessionRepositoryPort.findById(sessionId)).thenReturn(Optional.of(session));

            assertThatThrownBy(() -> service.generatePresignedPartUploadUrl(sessionId, 1, WRONG_PRINCIPAL_ID))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Principal '" + WRONG_PRINCIPAL_ID + "' is not authorized");

            verify(multipartStoragePort, never()).generatePresignedPartUploadUrl(any(), any(), any(Integer.class), any());
        }

        @Test
        @DisplayName("Rejects null or blank requesting principal ID")
        void shouldRejectNullOrBlankPrincipal() {
            UUID sessionId = UUID.randomUUID();

            assertThatThrownBy(() -> service.generatePresignedPartUploadUrl(sessionId, 1, null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("requestingPrincipalId");

            assertThatThrownBy(() -> service.generatePresignedPartUploadUrl(sessionId, 1, "   "))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("requestingPrincipalId");
        }

        @Test
        @DisplayName("Rejects non-existent session ID")
        void shouldRejectNonExistentSession() {
            UUID missingSessionId = UUID.randomUUID();
            when(sessionRepositoryPort.findById(missingSessionId)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.generatePresignedPartUploadUrl(missingSessionId, 1, PRINCIPAL_ID))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Upload session not found for ID: " + missingSessionId);
        }
    }

    @Nested
    @DisplayName("Session State & Lifecycle Validation")
    class SessionStateTests {

        @Test
        @DisplayName("Rejects session in ALLOCATED status before provider initiation")
        void shouldRejectSessionInAllocatedStatus() {
            UUID sessionId = UUID.randomUUID();
            UUID allocationId = UUID.randomUUID();
            MediaUploadSession session = MediaUploadSession.create(
                    sessionId,
                    PRINCIPAL_ID,
                    "screen",
                    "ScreenEntry",
                    UUID.randomUUID(),
                    "idem-" + sessionId,
                    MediaUploadSession.FIXED_PART_SIZE_BYTES,
                    B2_PROVIDER,
                    B2_BUCKET,
                    STORAGE_KEY,
                    allocationId,
                    "sample.mp4",
                    null,
                    BASE_NOW.plus(Duration.ofHours(24)),
                    BASE_NOW
            );

            when(sessionRepositoryPort.findById(sessionId)).thenReturn(Optional.of(session));

            assertThatThrownBy(() -> service.generatePresignedPartUploadUrl(sessionId, 1, PRINCIPAL_ID))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Session must be in INITIATED status")
                    .hasMessageContaining("ALLOCATED");
        }

        @Test
        @DisplayName("Rejects session in COMPLETING status")
        void shouldRejectSessionInCompletingStatus() {
            UUID sessionId = UUID.randomUUID();
            UUID allocationId = UUID.randomUUID();
            MediaUploadSession session = createInitiatedSession(sessionId, allocationId, MediaUploadSession.FIXED_PART_SIZE_BYTES, BASE_NOW.plus(Duration.ofHours(24)));
            session.markCompleting("0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef", BASE_NOW);

            when(sessionRepositoryPort.findById(sessionId)).thenReturn(Optional.of(session));

            assertThatThrownBy(() -> service.generatePresignedPartUploadUrl(sessionId, 1, PRINCIPAL_ID))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Session must be in INITIATED status")
                    .hasMessageContaining("COMPLETING");
        }

        @Test
        @DisplayName("Rejects session in terminal ABORTED status")
        void shouldRejectSessionInAbortedStatus() {
            UUID sessionId = UUID.randomUUID();
            UUID allocationId = UUID.randomUUID();
            MediaUploadSession session = createInitiatedSession(sessionId, allocationId, MediaUploadSession.FIXED_PART_SIZE_BYTES, BASE_NOW.plus(Duration.ofHours(24)));
            session.abort(BASE_NOW);

            when(sessionRepositoryPort.findById(sessionId)).thenReturn(Optional.of(session));

            assertThatThrownBy(() -> service.generatePresignedPartUploadUrl(sessionId, 1, PRINCIPAL_ID))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Session must be in INITIATED status")
                    .hasMessageContaining("ABORTED");
        }

        @Test
        @DisplayName("Rejects expired session where expiresAt is before now")
        void shouldRejectExpiredSession() {
            UUID sessionId = UUID.randomUUID();
            UUID allocationId = UUID.randomUUID();
            Instant expiredTime = BASE_NOW.minus(Duration.ofMinutes(1));
            MediaUploadSession session = createInitiatedSession(sessionId, allocationId, MediaUploadSession.FIXED_PART_SIZE_BYTES, expiredTime);

            when(clockPort.now()).thenReturn(BASE_NOW);
            when(sessionRepositoryPort.findById(sessionId)).thenReturn(Optional.of(session));

            assertThatThrownBy(() -> service.generatePresignedPartUploadUrl(sessionId, 1, PRINCIPAL_ID))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Upload session has expired: " + sessionId);
        }

        @Test
        @DisplayName("Rejects session expiring imminently (< MIN_PART_URL_EXPIRY)")
        void shouldRejectSessionExpiringImminently() {
            UUID sessionId = UUID.randomUUID();
            UUID allocationId = UUID.randomUUID();
            // Session expires in only 5 seconds, less than MIN_PART_URL_EXPIRY (10 seconds)
            Instant imminentlyExpiring = BASE_NOW.plus(Duration.ofSeconds(5));
            MediaUploadSession session = createInitiatedSession(sessionId, allocationId, MediaUploadSession.FIXED_PART_SIZE_BYTES, imminentlyExpiring);

            when(clockPort.now()).thenReturn(BASE_NOW);
            when(sessionRepositoryPort.findById(sessionId)).thenReturn(Optional.of(session));

            assertThatThrownBy(() -> service.generatePresignedPartUploadUrl(sessionId, 1, PRINCIPAL_ID))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Upload session is expiring imminently");
        }

        @Test
        @DisplayName("Clamps presigned URL expiry to session remaining TTL when TTL is less than requested expiry")
        void shouldClampExpiryToRemainingSessionTtl() {
            UUID sessionId = UUID.randomUUID();
            UUID allocationId = UUID.randomUUID();
            // Session expires in 5 minutes (300s); default requested expiry is 15 minutes (900s)
            Instant expiresAt = BASE_NOW.plus(Duration.ofMinutes(5));
            Duration remainingTtl = Duration.ofMinutes(5);

            MediaUploadSession session = createInitiatedSession(sessionId, allocationId, MediaUploadSession.FIXED_PART_SIZE_BYTES, expiresAt);
            StorageKeyAllocation allocation = createReservedAllocation(allocationId, sessionId);

            when(clockPort.now()).thenReturn(BASE_NOW);
            when(sessionRepositoryPort.findById(sessionId)).thenReturn(Optional.of(session));
            when(storageKeyAllocationService.findById(allocationId)).thenReturn(Optional.of(allocation));
            when(multipartStoragePort.generatePresignedPartUploadUrl(STORAGE_KEY, UPLOAD_ID, 1, remainingTtl))
                    .thenReturn("https://b2.example.com/part-1-clamped");

            String url = service.generatePresignedPartUploadUrl(sessionId, 1, PRINCIPAL_ID);

            assertThat(url).isEqualTo("https://b2.example.com/part-1-clamped");
            verify(multipartStoragePort).generatePresignedPartUploadUrl(STORAGE_KEY, UPLOAD_ID, 1, remainingTtl);
        }
    }

    @Nested
    @DisplayName("Part Number & Metadata Bounds Validation")
    class PartNumberValidationTests {

        @Test
        @DisplayName("Rejects part number 0 or negative")
        void shouldRejectZeroOrNegativePartNumber() {
            UUID sessionId = UUID.randomUUID();
            UUID allocationId = UUID.randomUUID();
            Instant expiresAt = BASE_NOW.plus(Duration.ofHours(24));
            MediaUploadSession session = createInitiatedSession(sessionId, allocationId, MediaUploadSession.FIXED_PART_SIZE_BYTES * 2, expiresAt);

            when(clockPort.now()).thenReturn(BASE_NOW);
            when(sessionRepositoryPort.findById(sessionId)).thenReturn(Optional.of(session));

            assertThatThrownBy(() -> service.generatePresignedPartUploadUrl(sessionId, 0, PRINCIPAL_ID))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Part number 0 is out of bounds");

            assertThatThrownBy(() -> service.generatePresignedPartUploadUrl(sessionId, -1, PRINCIPAL_ID))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Part number -1 is out of bounds");
        }

        @Test
        @DisplayName("Rejects part number exceeding expectedPartCount for session")
        void shouldRejectPartNumberExceedingExpectedCount() {
            UUID sessionId = UUID.randomUUID();
            UUID allocationId = UUID.randomUUID();
            Instant expiresAt = BASE_NOW.plus(Duration.ofHours(24));
            // 2 parts expected
            long sizeBytes = MediaUploadSession.FIXED_PART_SIZE_BYTES * 2;
            MediaUploadSession session = createInitiatedSession(sessionId, allocationId, sizeBytes, expiresAt);

            when(clockPort.now()).thenReturn(BASE_NOW);
            when(sessionRepositoryPort.findById(sessionId)).thenReturn(Optional.of(session));

            assertThatThrownBy(() -> service.generatePresignedPartUploadUrl(sessionId, 3, PRINCIPAL_ID))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Part number 3 is out of bounds for session " + sessionId + ". Expected between 1 and 2.");
        }

        @Test
        @DisplayName("Rejects requested expiry below minimum bound (< 10 seconds)")
        void shouldRejectRequestedExpiryBelowMinimum() {
            UUID sessionId = UUID.randomUUID();
            UUID allocationId = UUID.randomUUID();
            Instant expiresAt = BASE_NOW.plus(Duration.ofHours(24));
            MediaUploadSession session = createInitiatedSession(sessionId, allocationId, MediaUploadSession.FIXED_PART_SIZE_BYTES, expiresAt);

            when(clockPort.now()).thenReturn(BASE_NOW);
            when(sessionRepositoryPort.findById(sessionId)).thenReturn(Optional.of(session));

            Duration tooShort = Duration.ofSeconds(5);
            assertThatThrownBy(() -> service.generatePresignedPartUploadUrl(sessionId, 1, PRINCIPAL_ID, tooShort))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("expiry must be between");
        }

        @Test
        @DisplayName("Rejects requested expiry above maximum bound (> 1 hour)")
        void shouldRejectRequestedExpiryAboveMaximum() {
            UUID sessionId = UUID.randomUUID();
            UUID allocationId = UUID.randomUUID();
            Instant expiresAt = BASE_NOW.plus(Duration.ofHours(24));
            MediaUploadSession session = createInitiatedSession(sessionId, allocationId, MediaUploadSession.FIXED_PART_SIZE_BYTES, expiresAt);

            when(clockPort.now()).thenReturn(BASE_NOW);
            when(sessionRepositoryPort.findById(sessionId)).thenReturn(Optional.of(session));

            Duration tooLong = Duration.ofHours(2);
            assertThatThrownBy(() -> service.generatePresignedPartUploadUrl(sessionId, 1, PRINCIPAL_ID, tooLong))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("expiry must be between");
        }
    }

    @Nested
    @DisplayName("Binding & Provider Failure Handling")
    class BindingAndProviderTests {

        @Test
        @DisplayName("Rejects when storage key allocation is missing or corrupted")
        void shouldRejectWhenAllocationNotFound() {
            UUID sessionId = UUID.randomUUID();
            UUID allocationId = UUID.randomUUID();
            Instant expiresAt = BASE_NOW.plus(Duration.ofHours(24));
            MediaUploadSession session = createInitiatedSession(sessionId, allocationId, MediaUploadSession.FIXED_PART_SIZE_BYTES, expiresAt);

            when(clockPort.now()).thenReturn(BASE_NOW);
            when(sessionRepositoryPort.findById(sessionId)).thenReturn(Optional.of(session));
            when(storageKeyAllocationService.findById(allocationId)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.generatePresignedPartUploadUrl(sessionId, 1, PRINCIPAL_ID))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Storage key allocation not found for session: " + sessionId);
        }

        @Test
        @DisplayName("Propagates provider StorageException without exposing credentials")
        void shouldPropagateProviderStorageException() {
            UUID sessionId = UUID.randomUUID();
            UUID allocationId = UUID.randomUUID();
            Instant expiresAt = BASE_NOW.plus(Duration.ofHours(24));
            MediaUploadSession session = createInitiatedSession(sessionId, allocationId, MediaUploadSession.FIXED_PART_SIZE_BYTES, expiresAt);
            StorageKeyAllocation allocation = createReservedAllocation(allocationId, sessionId);

            when(clockPort.now()).thenReturn(BASE_NOW);
            when(sessionRepositoryPort.findById(sessionId)).thenReturn(Optional.of(session));
            when(storageKeyAllocationService.findById(allocationId)).thenReturn(Optional.of(allocation));
            when(multipartStoragePort.generatePresignedPartUploadUrl(STORAGE_KEY, UPLOAD_ID, 1, MediaUploadSessionService.DEFAULT_PART_URL_EXPIRY))
                    .thenThrow(new StorageException("Simulated provider presigning network failure"));

            assertThatThrownBy(() -> service.generatePresignedPartUploadUrl(sessionId, 1, PRINCIPAL_ID))
                    .isInstanceOf(StorageException.class)
                    .hasMessageContaining("Simulated provider presigning network failure");
        }

        @Test
        @DisplayName("Rejects null or blank URL returned by provider storage port (fail-closed)")
        void shouldRejectNullOrBlankPresignedUrlFromProvider() {
            UUID sessionId = UUID.randomUUID();
            UUID allocationId = UUID.randomUUID();
            Instant expiresAt = BASE_NOW.plus(Duration.ofHours(24));
            MediaUploadSession session = createInitiatedSession(sessionId, allocationId, MediaUploadSession.FIXED_PART_SIZE_BYTES, expiresAt);
            StorageKeyAllocation allocation = createReservedAllocation(allocationId, sessionId);

            when(clockPort.now()).thenReturn(BASE_NOW);
            when(sessionRepositoryPort.findById(sessionId)).thenReturn(Optional.of(session));
            when(storageKeyAllocationService.findById(allocationId)).thenReturn(Optional.of(allocation));

            // Test null return
            when(multipartStoragePort.generatePresignedPartUploadUrl(STORAGE_KEY, UPLOAD_ID, 1, MediaUploadSessionService.DEFAULT_PART_URL_EXPIRY))
                    .thenReturn(null);

            assertThatThrownBy(() -> service.generatePresignedPartUploadUrl(sessionId, 1, PRINCIPAL_ID))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("returned empty or blank presigned part upload URL");

            // Test blank return
            when(multipartStoragePort.generatePresignedPartUploadUrl(STORAGE_KEY, UPLOAD_ID, 1, MediaUploadSessionService.DEFAULT_PART_URL_EXPIRY))
                    .thenReturn("   ");

            assertThatThrownBy(() -> service.generatePresignedPartUploadUrl(sessionId, 1, PRINCIPAL_ID))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("returned empty or blank presigned part upload URL");
        }

        @Test
        @DisplayName("Verifies lifecycle transitions and durable completion-intent markers")
        void shouldVerifyLifecycleTransitionsAndCompletionIntentMarkers() {
            UUID sessionId = UUID.randomUUID();
            UUID allocationId = UUID.randomUUID();
            Instant expiresAt = BASE_NOW.plus(Duration.ofHours(24));

            // 1. INITIATED -> ABORTED without completion intent
            MediaUploadSession session1 = createInitiatedSession(sessionId, allocationId, MediaUploadSession.FIXED_PART_SIZE_BYTES, expiresAt);
            session1.abort(BASE_NOW);
            assertThat(session1.getStatus()).isEqualTo(MediaUploadSessionStatus.ABORTED);
            assertThat(session1.getDeclaredContentHash()).isNull();
            assertThat(session1.getManifestHash()).isNull();
            assertThat(session1.getRegisteredAssetId()).isNull();

            // 2. INITIATED -> EXPIRED without completion intent
            MediaUploadSession session2 = createInitiatedSession(UUID.randomUUID(), UUID.randomUUID(), MediaUploadSession.FIXED_PART_SIZE_BYTES, expiresAt);
            session2.expire(BASE_NOW);
            assertThat(session2.getStatus()).isEqualTo(MediaUploadSessionStatus.EXPIRED);
            assertThat(session2.getDeclaredContentHash()).isNull();
            assertThat(session2.getManifestHash()).isNull();
            assertThat(session2.getRegisteredAssetId()).isNull();

            // 3. COMPLETING -> EXPIRED with persisted completion intent
            MediaUploadSession session3 = createInitiatedSession(UUID.randomUUID(), UUID.randomUUID(), MediaUploadSession.FIXED_PART_SIZE_BYTES, expiresAt);
            String contentHash = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
            session3.markCompleting(contentHash, BASE_NOW);
            session3.claimCompletion("claim-token", BASE_NOW.plus(Duration.ofMinutes(5)), BASE_NOW);
            assertThat(session3.getStatus()).isEqualTo(MediaUploadSessionStatus.COMPLETING);
            assertThat(session3.getDeclaredContentHash()).isEqualTo(contentHash);
            session3.expire(BASE_NOW.plus(Duration.ofMinutes(10)));
            assertThat(session3.getStatus()).isEqualTo(MediaUploadSessionStatus.EXPIRED);
            // Completion intent remains durably preserved
            assertThat(session3.getDeclaredContentHash()).isEqualTo(contentHash);

            // 4. COMPLETING -> FAILED -> ABORTED with persisted completion intent
            MediaUploadSession session4 = createInitiatedSession(UUID.randomUUID(), UUID.randomUUID(), MediaUploadSession.FIXED_PART_SIZE_BYTES, expiresAt);
            session4.markCompleting(contentHash, BASE_NOW);
            session4.claimCompletion("claim-token-2", BASE_NOW.plus(Duration.ofMinutes(5)), BASE_NOW);
            session4.fail("Network timeout during provider CompleteMultipartUpload", BASE_NOW.plus(Duration.ofMinutes(1)));
            assertThat(session4.getStatus()).isEqualTo(MediaUploadSessionStatus.FAILED);
            assertThat(session4.getDeclaredContentHash()).isEqualTo(contentHash);
            session4.abort(BASE_NOW.plus(Duration.ofMinutes(2)));
            assertThat(session4.getStatus()).isEqualTo(MediaUploadSessionStatus.ABORTED);
            // Completion intent remains durably preserved across transitions
            assertThat(session4.getDeclaredContentHash()).isEqualTo(contentHash);
        }
    }
}
