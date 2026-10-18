package com.universe.media.application.storage;

import com.universe.media.application.ports.MediaUploadSessionRepositoryPort;
import com.universe.media.domain.MediaType;
import com.universe.media.domain.MediaUploadSession;
import com.universe.media.domain.MediaUploadSessionStatus;
import com.universe.media.domain.StorageKey;
import com.universe.media.domain.StorageProviderId;
import com.universe.media.domain.UploadSessionCleanupStatus;
import com.universe.shared.time.ClockPort;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;
import org.springframework.dao.OptimisticLockingFailureException;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("SweepAbandonedMultipartUploadsUseCase Tests (MS-08E2-L3-2 Final Metrics)")
class SweepAbandonedMultipartUploadsUseCaseTest {

    @Mock
    private MediaUploadSessionRepositoryPort sessionRepositoryPort;

    @Mock
    private MediaUploadSessionService sessionService;

    private AtomicReference<Instant> currentTime;
    private ClockPort clockPort;
    private SweepAbandonedMultipartUploadsUseCase useCase;

    private ListAppender<ILoggingEvent> listAppender;
    private Logger useCaseLogger;

    private static final StorageProviderId B2_PROVIDER = StorageProviderId.of("b2");
    private static final String B2_BUCKET = "kiemlai-video";
    private static final StorageKey STORAGE_KEY = StorageKey.of("media/video/ms08-sweep.mp4");
    private static final Instant T0 = Instant.parse("2026-10-18T14:00:00Z");
    private static final String VALID_HASH = "a1b2c3d4e5f60718293a4b5c6d7e8f90a1b2c3d4e5f60718293a4b5c6d7e8f90";

    @BeforeEach
    void setUp() {
        currentTime = new AtomicReference<>(T0);
        clockPort = currentTime::get;
        useCase = new SweepAbandonedMultipartUploadsUseCase(
                sessionRepositoryPort,
                sessionService,
                clockPort
        );

        useCaseLogger = (Logger) LoggerFactory.getLogger(SweepAbandonedMultipartUploadsUseCase.class);
        listAppender = new ListAppender<>();
        listAppender.start();
        useCaseLogger.addAppender(listAppender);
    }

    @AfterEach
    void tearDown() {
        if (useCaseLogger != null && listAppender != null) {
            useCaseLogger.detachAppender(listAppender);
        }
    }

    private MediaUploadSession buildSession(
            UUID sessionId,
            MediaUploadSessionStatus status,
            UploadSessionCleanupStatus cleanupStatus,
            String providerUploadId,
            Instant expiresAt,
            String leaseOwner,
            Instant leaseExpiresAt,
            String declaredContentHash,
            String manifestHash,
            UUID registeredAssetId,
            int retryCount
    ) {
        return MediaUploadSession.rehydrate(
                sessionId,
                "user-principal-sweep",
                "screen",
                "ScreenEntry",
                UUID.randomUUID(),
                "idem-" + UUID.randomUUID(),
                MediaType.VIDEO,
                "video/mp4",
                MediaUploadSession.FIXED_PART_SIZE_BYTES * 2,
                B2_PROVIDER,
                B2_BUCKET,
                STORAGE_KEY,
                UUID.randomUUID(),
                providerUploadId,
                MediaUploadSession.FIXED_PART_SIZE_BYTES,
                2,
                declaredContentHash,
                "video.mp4",
                "screen-video",
                status,
                expiresAt,
                leaseOwner,
                leaseExpiresAt,
                registeredAssetId,
                cleanupStatus,
                retryCount,
                null,
                null,
                T0.minus(2, ChronoUnit.HOURS),
                T0.minus(1, ChronoUnit.HOURS),
                (status == MediaUploadSessionStatus.COMPLETED) ? T0.minus(1, ChronoUnit.HOURS) : null,
                1L,
                manifestHash
        );
    }

    private MediaUploadSession createEligibleAbortedSession(UUID sessionId) {
        return buildSession(
                sessionId,
                MediaUploadSessionStatus.ABORTED,
                UploadSessionCleanupStatus.PENDING,
                "b2-upload-12345",
                T0.plus(24, ChronoUnit.HOURS),
                null,
                null,
                null,
                null,
                null,
                0
        );
    }

    private MediaUploadSession createEligibleExpiredSession(UUID sessionId) {
        return buildSession(
                sessionId,
                MediaUploadSessionStatus.EXPIRED,
                UploadSessionCleanupStatus.PENDING,
                "b2-upload-12345",
                T0.minus(1, ChronoUnit.HOURS),
                null,
                null,
                null,
                null,
                null,
                0
        );
    }

    private MediaUploadSession createQuarantinedSession(UUID sessionId, MediaUploadSessionStatus status) {
        return buildSession(
                sessionId,
                status,
                UploadSessionCleanupStatus.QUARANTINED,
                "b2-upload-12345",
                T0.minus(1, ChronoUnit.HOURS),
                null,
                null,
                null,
                null,
                null,
                0
        );
    }

    @Test
    @DisplayName("Eligible ABORTED session receives guarded provider abort: increments Attempted and Quarantined, never VerifiedCleaned")
    void shouldSweepEligibleAbortedSession() {
        UUID sessionId = UUID.randomUUID();
        MediaUploadSession session = createEligibleAbortedSession(sessionId);
        MediaUploadSession quarantinedSession = createQuarantinedSession(sessionId, MediaUploadSessionStatus.ABORTED);

        when(sessionRepositoryPort.findExpiredSessions(any(), any(), anyInt()))
                .thenReturn(Collections.emptyList());
        when(sessionRepositoryPort.findSessionsEligibleForCleanup(any(), any(), anyInt(), any(), anyInt()))
                .thenReturn(List.of(session));
        when(sessionService.executeGuardedMultipartAbort(sessionId))
                .thenReturn(quarantinedSession);

        SweepAbandonedMultipartUploadsResult result = useCase.execute(new SweepAbandonedMultipartUploadsCommand());

        assertThat(result.scannedCount()).isEqualTo(1);
        assertThat(result.attemptedCount()).isEqualTo(1);
        assertThat(result.skippedCount()).isEqualTo(0);
        assertThat(result.quarantinedCount()).isEqualTo(1);
        assertThat(result.verifiedCleanedCount()).isEqualTo(0);
        assertThat(result.failedCount()).isEqualTo(0);
        assertThat(result.expirationFailedCount()).isEqualTo(0);

        verify(sessionService).executeGuardedMultipartAbort(sessionId);
    }

    @Test
    @DisplayName("Eligible EXPIRED session receives guarded provider abort: increments Attempted and Quarantined, never VerifiedCleaned")
    void shouldSweepEligibleExpiredSession() {
        UUID sessionId = UUID.randomUUID();
        MediaUploadSession session = createEligibleExpiredSession(sessionId);
        MediaUploadSession quarantinedSession = createQuarantinedSession(sessionId, MediaUploadSessionStatus.EXPIRED);

        when(sessionRepositoryPort.findExpiredSessions(any(), any(), anyInt()))
                .thenReturn(Collections.emptyList());
        when(sessionRepositoryPort.findSessionsEligibleForCleanup(any(), any(), anyInt(), any(), anyInt()))
                .thenReturn(List.of(session));
        when(sessionService.executeGuardedMultipartAbort(sessionId))
                .thenReturn(quarantinedSession);

        SweepAbandonedMultipartUploadsResult result = useCase.execute(new SweepAbandonedMultipartUploadsCommand());

        assertThat(result.scannedCount()).isEqualTo(1);
        assertThat(result.attemptedCount()).isEqualTo(1);
        assertThat(result.skippedCount()).isEqualTo(0);
        assertThat(result.quarantinedCount()).isEqualTo(1);
        assertThat(result.verifiedCleanedCount()).isEqualTo(0);
        assertThat(result.failedCount()).isEqualTo(0);
        assertThat(result.expirationFailedCount()).isEqualTo(0);

        verify(sessionService).executeGuardedMultipartAbort(sessionId);
    }

    @Test
    @DisplayName("Candidate discovery strictly requests PENDING cleanup status")
    void shouldQueryOnlyPendingCleanupStatusForGuardedAbort() {
        when(sessionRepositoryPort.findExpiredSessions(any(), any(), anyInt()))
                .thenReturn(Collections.emptyList());
        when(sessionRepositoryPort.findSessionsEligibleForCleanup(any(), any(), anyInt(), any(), anyInt()))
                .thenReturn(Collections.emptyList());

        useCase.execute(new SweepAbandonedMultipartUploadsCommand(25, 3, Duration.ofHours(1)));

        verify(sessionRepositoryPort).findSessionsEligibleForCleanup(
                eq(MediaUploadSessionRepositoryPort.APPROVED_CLEANUP_CANDIDATE_STATUSES),
                eq(Set.of(UploadSessionCleanupStatus.PENDING)),
                eq(3),
                eq(T0.minus(1, ChronoUnit.HOURS)),
                eq(25)
        );
    }

    @Test
    @DisplayName("Candidate with FAILED cleanup status is skipped and never receives guarded abort")
    void shouldSkipCandidateInFailedCleanupStatusAndNotAttemptGuardedAbort() {
        UUID sessionId = UUID.randomUUID();
        MediaUploadSession failedCandidate = buildSession(
                sessionId,
                MediaUploadSessionStatus.ABORTED,
                UploadSessionCleanupStatus.FAILED,
                "b2-upload-12345",
                T0.plus(24, ChronoUnit.HOURS),
                null,
                null,
                null,
                null,
                null,
                1
        );

        when(sessionRepositoryPort.findExpiredSessions(any(), any(), anyInt()))
                .thenReturn(Collections.emptyList());
        when(sessionRepositoryPort.findSessionsEligibleForCleanup(any(), any(), anyInt(), any(), anyInt()))
                .thenReturn(List.of(failedCandidate));

        SweepAbandonedMultipartUploadsResult result = useCase.execute(new SweepAbandonedMultipartUploadsCommand());

        assertThat(result.scannedCount()).isEqualTo(1);
        assertThat(result.attemptedCount()).isEqualTo(0);
        assertThat(result.skippedCount()).isEqualTo(1);
        assertThat(result.quarantinedCount()).isEqualTo(0);
        assertThat(result.verifiedCleanedCount()).isEqualTo(0);
        assertThat(result.failedCount()).isEqualTo(0);
        assertThat(result.expirationFailedCount()).isEqualTo(0);

        verify(sessionService, never()).executeGuardedMultipartAbort(any());
    }

    @Test
    @DisplayName("Optimistic-lock claim rejection increments Attempted and Failed, not a successful-claim metric")
    void shouldIncrementAttemptedAndFailedOnOptimisticLockClaimRejection() {
        UUID sessionId = UUID.randomUUID();
        MediaUploadSession candidate = createEligibleAbortedSession(sessionId);

        when(sessionRepositoryPort.findExpiredSessions(any(), any(), anyInt()))
                .thenReturn(Collections.emptyList());
        when(sessionRepositoryPort.findSessionsEligibleForCleanup(any(), any(), anyInt(), any(), anyInt()))
                .thenReturn(List.of(candidate));
        when(sessionService.executeGuardedMultipartAbort(sessionId))
                .thenThrow(new OptimisticLockingFailureException("Worker lost claim race"));

        SweepAbandonedMultipartUploadsResult result = useCase.execute(new SweepAbandonedMultipartUploadsCommand());

        assertThat(result.scannedCount()).isEqualTo(1);
        assertThat(result.attemptedCount()).isEqualTo(1);
        assertThat(result.skippedCount()).isEqualTo(0);
        assertThat(result.quarantinedCount()).isEqualTo(0);
        assertThat(result.verifiedCleanedCount()).isEqualTo(0);
        assertThat(result.failedCount()).isEqualTo(1);
        assertThat(result.expirationFailedCount()).isEqualTo(0);

        verify(sessionService).executeGuardedMultipartAbort(sessionId);
    }

    @Test
    @DisplayName("Expiration failure appears in final counters when no abort candidates exist")
    void shouldRecordExpirationFailuresInFinalResultWhenNoAbortCandidatesExist() {
        UUID activeSessionId = UUID.randomUUID();
        MediaUploadSession activeSession = buildSession(
                activeSessionId,
                MediaUploadSessionStatus.INITIATED,
                UploadSessionCleanupStatus.NONE,
                "b2-upload-active",
                T0.minus(10, ChronoUnit.MINUTES),
                null,
                null,
                null,
                null,
                null,
                0
        );

        when(sessionRepositoryPort.findExpiredSessions(any(), any(), anyInt()))
                .thenReturn(List.of(activeSession));
        when(sessionService.expireSession(activeSessionId))
                .thenThrow(new RuntimeException("Database timeout during expiration"));
        when(sessionRepositoryPort.findSessionsEligibleForCleanup(any(), any(), anyInt(), any(), anyInt()))
                .thenReturn(Collections.emptyList());

        SweepAbandonedMultipartUploadsResult result = useCase.execute(new SweepAbandonedMultipartUploadsCommand());

        assertThat(result.scannedCount()).isEqualTo(0);
        assertThat(result.attemptedCount()).isEqualTo(0);
        assertThat(result.skippedCount()).isEqualTo(0);
        assertThat(result.quarantinedCount()).isEqualTo(0);
        assertThat(result.verifiedCleanedCount()).isEqualTo(0);
        assertThat(result.failedCount()).isEqualTo(0);
        assertThat(result.expirationFailedCount()).isEqualTo(1);

        verify(sessionService).expireSession(activeSessionId);
        verify(sessionService, never()).executeGuardedMultipartAbort(any());
    }

    @Test
    @DisplayName("Expired active sessions (ALLOCATED or INITIATED past expiresAt) are safely transitioned before candidate discovery")
    void shouldTransitionExpiredActiveSessionsBeforeDiscovery() {
        UUID activeSessionId = UUID.randomUUID();
        MediaUploadSession activeSession = buildSession(
                activeSessionId,
                MediaUploadSessionStatus.INITIATED,
                UploadSessionCleanupStatus.NONE,
                "b2-upload-active",
                T0.minus(10, ChronoUnit.MINUTES),
                null,
                null,
                null,
                null,
                null,
                0
        );

        when(sessionRepositoryPort.findExpiredSessions(
                eq(Set.of(MediaUploadSessionStatus.ALLOCATED, MediaUploadSessionStatus.INITIATED)),
                eq(T0),
                anyInt()
        )).thenReturn(List.of(activeSession));

        when(sessionService.expireSession(activeSessionId)).thenReturn(Optional.of(activeSession));
        when(sessionRepositoryPort.findSessionsEligibleForCleanup(any(), any(), anyInt(), any(), anyInt()))
                .thenReturn(Collections.emptyList());

        SweepAbandonedMultipartUploadsResult result = useCase.execute(new SweepAbandonedMultipartUploadsCommand());

        verify(sessionService).expireSession(activeSessionId);
        assertThat(result.quarantinedCount()).isEqualTo(0);
        assertThat(result.verifiedCleanedCount()).isEqualTo(0);
        assertThat(result.expirationFailedCount()).isEqualTo(0);
    }

    @Test
    @DisplayName("Non-expired ACTIVE session (expiresAt in future) is not eligible for abort and is skipped")
    void shouldSkipNonExpiredActiveSession() {
        UUID sessionId = UUID.randomUUID();
        MediaUploadSession activeSession = buildSession(
                sessionId,
                MediaUploadSessionStatus.INITIATED,
                UploadSessionCleanupStatus.NONE,
                "b2-upload-active",
                T0.plus(1, ChronoUnit.HOURS),
                null,
                null,
                null,
                null,
                null,
                0
        );

        when(sessionRepositoryPort.findExpiredSessions(any(), any(), anyInt()))
                .thenReturn(Collections.emptyList());
        when(sessionRepositoryPort.findSessionsEligibleForCleanup(any(), any(), anyInt(), any(), anyInt()))
                .thenReturn(List.of(activeSession));

        SweepAbandonedMultipartUploadsResult result = useCase.execute(new SweepAbandonedMultipartUploadsCommand());

        assertThat(result.scannedCount()).isEqualTo(1);
        assertThat(result.attemptedCount()).isEqualTo(0);
        assertThat(result.skippedCount()).isEqualTo(1);
        assertThat(result.quarantinedCount()).isEqualTo(0);
        assertThat(result.verifiedCleanedCount()).isEqualTo(0);
        assertThat(result.failedCount()).isEqualTo(0);
        assertThat(result.expirationFailedCount()).isEqualTo(0);

        verify(sessionService, never()).executeGuardedMultipartAbort(any());
    }

    @Test
    @DisplayName("COMPLETING session is strictly skipped and never receives guarded abort")
    void shouldSkipCompletingSession() {
        UUID sessionId = UUID.randomUUID();
        MediaUploadSession completingSession = buildSession(
                sessionId,
                MediaUploadSessionStatus.COMPLETING,
                UploadSessionCleanupStatus.NONE,
                "b2-upload-completing",
                T0.plus(1, ChronoUnit.HOURS),
                null,
                null,
                null,
                null,
                null,
                0
        );

        when(sessionRepositoryPort.findExpiredSessions(any(), any(), anyInt()))
                .thenReturn(Collections.emptyList());
        when(sessionRepositoryPort.findSessionsEligibleForCleanup(any(), any(), anyInt(), any(), anyInt()))
                .thenReturn(List.of(completingSession));

        SweepAbandonedMultipartUploadsResult result = useCase.execute(new SweepAbandonedMultipartUploadsCommand());

        assertThat(result.scannedCount()).isEqualTo(1);
        assertThat(result.skippedCount()).isEqualTo(1);
        assertThat(result.attemptedCount()).isEqualTo(0);
        assertThat(result.quarantinedCount()).isEqualTo(0);
        assertThat(result.verifiedCleanedCount()).isEqualTo(0);
        assertThat(result.failedCount()).isEqualTo(0);
        assertThat(result.expirationFailedCount()).isEqualTo(0);

        verify(sessionService, never()).executeGuardedMultipartAbort(any());
    }

    @Test
    @DisplayName("COMPLETED session is strictly skipped and never receives guarded abort")
    void shouldSkipCompletedSession() {
        UUID sessionId = UUID.randomUUID();
        MediaUploadSession completedSession = buildSession(
                sessionId,
                MediaUploadSessionStatus.COMPLETED,
                UploadSessionCleanupStatus.COMPLETED,
                "b2-upload-completed",
                T0.minus(1, ChronoUnit.HOURS),
                null,
                null,
                VALID_HASH,
                null,
                UUID.randomUUID(),
                0
        );

        when(sessionRepositoryPort.findExpiredSessions(any(), any(), anyInt()))
                .thenReturn(Collections.emptyList());
        when(sessionRepositoryPort.findSessionsEligibleForCleanup(any(), any(), anyInt(), any(), anyInt()))
                .thenReturn(List.of(completedSession));

        SweepAbandonedMultipartUploadsResult result = useCase.execute(new SweepAbandonedMultipartUploadsCommand());

        assertThat(result.scannedCount()).isEqualTo(1);
        assertThat(result.skippedCount()).isEqualTo(1);
        assertThat(result.attemptedCount()).isEqualTo(0);
        assertThat(result.quarantinedCount()).isEqualTo(0);
        assertThat(result.verifiedCleanedCount()).isEqualTo(0);
        assertThat(result.failedCount()).isEqualTo(0);
        assertThat(result.expirationFailedCount()).isEqualTo(0);

        verify(sessionService, never()).executeGuardedMultipartAbort(any());
    }

    @Test
    @DisplayName("Registered completed asset is never aborted even if status was somehow marked ABORTED")
    void shouldNeverAbortSessionWithRegisteredAssetId() {
        UUID sessionId = UUID.randomUUID();
        MediaUploadSession sessionWithAsset = buildSession(
                sessionId,
                MediaUploadSessionStatus.ABORTED,
                UploadSessionCleanupStatus.PENDING,
                "b2-upload-123",
                T0.minus(1, ChronoUnit.HOURS),
                null,
                null,
                null,
                null,
                UUID.randomUUID(), // registeredAssetId present
                0
        );

        when(sessionRepositoryPort.findExpiredSessions(any(), any(), anyInt()))
                .thenReturn(Collections.emptyList());
        when(sessionRepositoryPort.findSessionsEligibleForCleanup(any(), any(), anyInt(), any(), anyInt()))
                .thenReturn(List.of(sessionWithAsset));

        SweepAbandonedMultipartUploadsResult result = useCase.execute(new SweepAbandonedMultipartUploadsCommand());

        assertThat(result.scannedCount()).isEqualTo(1);
        assertThat(result.skippedCount()).isEqualTo(1);
        assertThat(result.attemptedCount()).isEqualTo(0);
        assertThat(result.quarantinedCount()).isEqualTo(0);
        assertThat(result.verifiedCleanedCount()).isEqualTo(0);
        assertThat(result.failedCount()).isEqualTo(0);
        assertThat(result.expirationFailedCount()).isEqualTo(0);

        verify(sessionService, never()).executeGuardedMultipartAbort(any());
    }

    @Test
    @DisplayName("Already-cleaned session is idempotent and skipped")
    void shouldHandleAlreadyCleanedSessionIdempotently() {
        UUID sessionId = UUID.randomUUID();
        MediaUploadSession cleanedSession = buildSession(
                sessionId,
                MediaUploadSessionStatus.CLEANED_UP,
                UploadSessionCleanupStatus.COMPLETED,
                "b2-upload-123",
                T0.minus(1, ChronoUnit.HOURS),
                null,
                null,
                null,
                null,
                null,
                0
        );

        when(sessionRepositoryPort.findExpiredSessions(any(), any(), anyInt()))
                .thenReturn(Collections.emptyList());
        when(sessionRepositoryPort.findSessionsEligibleForCleanup(any(), any(), anyInt(), any(), anyInt()))
                .thenReturn(List.of(cleanedSession));

        SweepAbandonedMultipartUploadsResult result = useCase.execute(new SweepAbandonedMultipartUploadsCommand());

        assertThat(result.scannedCount()).isEqualTo(1);
        assertThat(result.skippedCount()).isEqualTo(1);
        assertThat(result.attemptedCount()).isEqualTo(0);
        assertThat(result.quarantinedCount()).isEqualTo(0);
        assertThat(result.verifiedCleanedCount()).isEqualTo(0);
        assertThat(result.failedCount()).isEqualTo(0);
        assertThat(result.expirationFailedCount()).isEqualTo(0);

        verify(sessionService, never()).executeGuardedMultipartAbort(any());
    }

    @Test
    @DisplayName("One failed candidate does not block other candidates in the batch (failure isolation)")
    void shouldIsolateFailuresAcrossCandidates() {
        UUID session1Id = UUID.randomUUID();
        UUID session2Id = UUID.randomUUID();
        MediaUploadSession session1 = createEligibleAbortedSession(session1Id);
        MediaUploadSession session2 = createEligibleAbortedSession(session2Id);
        MediaUploadSession quarantinedSession2 = createQuarantinedSession(session2Id, MediaUploadSessionStatus.ABORTED);

        when(sessionRepositoryPort.findExpiredSessions(any(), any(), anyInt()))
                .thenReturn(Collections.emptyList());
        when(sessionRepositoryPort.findSessionsEligibleForCleanup(any(), any(), anyInt(), any(), anyInt()))
                .thenReturn(List.of(session1, session2));

        // Session 1 fails with concurrent lock failure
        when(sessionService.executeGuardedMultipartAbort(session1Id))
                .thenThrow(new OptimisticLockingFailureException("Concurrent claim conflict"));

        // Session 2 succeeds
        when(sessionService.executeGuardedMultipartAbort(session2Id))
                .thenReturn(quarantinedSession2);

        SweepAbandonedMultipartUploadsResult result = useCase.execute(new SweepAbandonedMultipartUploadsCommand());

        assertThat(result.scannedCount()).isEqualTo(2);
        assertThat(result.attemptedCount()).isEqualTo(2);
        assertThat(result.quarantinedCount()).isEqualTo(1);
        assertThat(result.verifiedCleanedCount()).isEqualTo(0);
        assertThat(result.failedCount()).isEqualTo(1);
        assertThat(result.expirationFailedCount()).isEqualTo(0);

        verify(sessionService).executeGuardedMultipartAbort(session1Id);
        verify(sessionService).executeGuardedMultipartAbort(session2Id);
    }

    @Test
    @DisplayName("Transient B2 error increments failedCount and candidate remains eligible for retry on next run")
    void shouldRemainRetryableOnTransientError() {
        UUID sessionId = UUID.randomUUID();
        MediaUploadSession session = createEligibleAbortedSession(sessionId);

        when(sessionRepositoryPort.findExpiredSessions(any(), any(), anyInt()))
                .thenReturn(Collections.emptyList());
        when(sessionRepositoryPort.findSessionsEligibleForCleanup(any(), any(), anyInt(), any(), anyInt()))
                .thenReturn(List.of(session));

        when(sessionService.executeGuardedMultipartAbort(sessionId))
                .thenThrow(new IllegalStateException("Transient provider communication timeout"));

        SweepAbandonedMultipartUploadsResult result = useCase.execute(new SweepAbandonedMultipartUploadsCommand());

        assertThat(result.scannedCount()).isEqualTo(1);
        assertThat(result.attemptedCount()).isEqualTo(1);
        assertThat(result.quarantinedCount()).isEqualTo(0);
        assertThat(result.verifiedCleanedCount()).isEqualTo(0);
        assertThat(result.failedCount()).isEqualTo(1);
        assertThat(result.expirationFailedCount()).isEqualTo(0);

        verify(sessionService).executeGuardedMultipartAbort(sessionId);
    }

    @Test
    @DisplayName("Candidate becoming ineligible before provider action (e.g. lease claimed or missing uploadId) is skipped")
    void shouldSkipCandidateWithHeldLeaseOrMissingUploadId() {
        UUID sessionId = UUID.randomUUID();
        MediaUploadSession sessionWithHeldLease = buildSession(
                sessionId,
                MediaUploadSessionStatus.ABORTED,
                UploadSessionCleanupStatus.PENDING,
                "b2-upload-123",
                T0.plus(24, ChronoUnit.HOURS),
                "CLEANUP_CLAIM:other-worker",
                T0.plus(5, ChronoUnit.MINUTES),
                null,
                null,
                null,
                0
        );

        when(sessionRepositoryPort.findExpiredSessions(any(), any(), anyInt()))
                .thenReturn(Collections.emptyList());
        when(sessionRepositoryPort.findSessionsEligibleForCleanup(any(), any(), anyInt(), any(), anyInt()))
                .thenReturn(List.of(sessionWithHeldLease));

        SweepAbandonedMultipartUploadsResult result = useCase.execute(new SweepAbandonedMultipartUploadsCommand());

        assertThat(result.scannedCount()).isEqualTo(1);
        assertThat(result.skippedCount()).isEqualTo(1);
        assertThat(result.attemptedCount()).isEqualTo(0);
        assertThat(result.quarantinedCount()).isEqualTo(0);
        assertThat(result.verifiedCleanedCount()).isEqualTo(0);
        assertThat(result.failedCount()).isEqualTo(0);
        assertThat(result.expirationFailedCount()).isEqualTo(0);

        verify(sessionService, never()).executeGuardedMultipartAbort(any());
    }

    @Test
    @DisplayName("Session without providerUploadId is skipped and never dispatched to provider")
    void shouldSkipSessionWithoutProviderUploadId() {
        UUID sessionId = UUID.randomUUID();
        MediaUploadSession sessionNoUploadId = buildSession(
                sessionId,
                MediaUploadSessionStatus.EXPIRED,
                UploadSessionCleanupStatus.PENDING,
                null, // no upload ID
                T0.minus(1, ChronoUnit.HOURS),
                null,
                null,
                null,
                null,
                null,
                0
        );

        when(sessionRepositoryPort.findExpiredSessions(any(), any(), anyInt()))
                .thenReturn(Collections.emptyList());
        when(sessionRepositoryPort.findSessionsEligibleForCleanup(any(), any(), anyInt(), any(), anyInt()))
                .thenReturn(List.of(sessionNoUploadId));

        SweepAbandonedMultipartUploadsResult result = useCase.execute(new SweepAbandonedMultipartUploadsCommand());

        assertThat(result.scannedCount()).isEqualTo(1);
        assertThat(result.skippedCount()).isEqualTo(1);
        assertThat(result.attemptedCount()).isEqualTo(0);
        assertThat(result.quarantinedCount()).isEqualTo(0);
        assertThat(result.verifiedCleanedCount()).isEqualTo(0);
        assertThat(result.failedCount()).isEqualTo(0);
        assertThat(result.expirationFailedCount()).isEqualTo(0);

        verify(sessionService, never()).executeGuardedMultipartAbort(any());
    }

    @Test
    @DisplayName("Command validation rejects non-positive batchSize, batchSize exceeding MAX_BATCH_SIZE, or negative maxRetries")
    void shouldValidateCommandParameters() {
        assertThatThrownBy(() -> new SweepAbandonedMultipartUploadsCommand(0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("batchSize must be between 1 and 100");

        assertThatThrownBy(() -> new SweepAbandonedMultipartUploadsCommand(101))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("batchSize must be between 1 and 100");

        assertThatThrownBy(() -> new SweepAbandonedMultipartUploadsCommand(50, -1, Duration.ofHours(1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maxRetryCount cannot be negative");
    }

    @Test
    @DisplayName("Two overlapping scheduler executions cannot bypass lease fencing when claiming the same candidate")
    void shouldPreventOverlappingExecutionsFromBypassingFencing() {
        UUID sessionId = UUID.randomUUID();
        MediaUploadSession candidate = createEligibleAbortedSession(sessionId);
        MediaUploadSession quarantinedCandidate = createQuarantinedSession(sessionId, MediaUploadSessionStatus.ABORTED);

        when(sessionRepositoryPort.findExpiredSessions(any(), any(), anyInt()))
                .thenReturn(Collections.emptyList());
        when(sessionRepositoryPort.findSessionsEligibleForCleanup(any(), any(), anyInt(), any(), anyInt()))
                .thenReturn(List.of(candidate));

        // Execution 1 wins and executes guarded multipart abort
        when(sessionService.executeGuardedMultipartAbort(sessionId))
                .thenReturn(quarantinedCandidate) // Worker 1 wins
                .thenThrow(new OptimisticLockingFailureException("Worker 2 lost race")); // Worker 2 loses

        SweepAbandonedMultipartUploadsResult result1 = useCase.execute(new SweepAbandonedMultipartUploadsCommand());
        assertThat(result1.quarantinedCount()).isEqualTo(1);
        assertThat(result1.verifiedCleanedCount()).isEqualTo(0);
        assertThat(result1.failedCount()).isEqualTo(0);
        assertThat(result1.expirationFailedCount()).isEqualTo(0);

        SweepAbandonedMultipartUploadsResult result2 = useCase.execute(new SweepAbandonedMultipartUploadsCommand());
        assertThat(result2.quarantinedCount()).isEqualTo(0);
        assertThat(result2.verifiedCleanedCount()).isEqualTo(0);
        assertThat(result2.failedCount()).isEqualTo(1);
        assertThat(result2.expirationFailedCount()).isEqualTo(0);

        verify(sessionService, times(2)).executeGuardedMultipartAbort(sessionId);
    }

    @Test
    @DisplayName("Exceptions containing sensitive presigned URLs or secrets are masked in logs")
    void shouldSanitizeExceptionsWithoutLeakingPresignedUrlsOrSignatures() {
        UUID activeSessionId = UUID.randomUUID();
        UUID abortCandidateId = UUID.randomUUID();

        MediaUploadSession activeSession = buildSession(
                activeSessionId,
                MediaUploadSessionStatus.INITIATED,
                UploadSessionCleanupStatus.NONE,
                "b2-upload-active",
                T0.minus(10, ChronoUnit.MINUTES),
                null,
                null,
                null,
                null,
                null,
                0
        );

        MediaUploadSession abortCandidate = createEligibleAbortedSession(abortCandidateId);

        when(sessionRepositoryPort.findExpiredSessions(any(), any(), anyInt()))
                .thenReturn(List.of(activeSession));
        when(sessionRepositoryPort.findSessionsEligibleForCleanup(any(), any(), anyInt(), any(), anyInt()))
                .thenReturn(List.of(abortCandidate));

        String sensitiveUrl = "https://s3.us-west-004.backblazeb2.com/bucket?X-Amz-Signature=secretToken12345&AWSAccessKeyId=SECRET_KEY";

        // Phase 1 throws exception with sensitive URL in message
        when(sessionService.expireSession(activeSessionId))
                .thenThrow(new RuntimeException("Failure contacting provider at " + sensitiveUrl));

        // Phase 2 throws exception with sensitive signature in message
        when(sessionService.executeGuardedMultipartAbort(abortCandidateId))
                .thenThrow(new IllegalStateException("Abort request failed: " + sensitiveUrl));

        SweepAbandonedMultipartUploadsResult result = useCase.execute(new SweepAbandonedMultipartUploadsCommand());

        assertThat(result.failedCount()).isEqualTo(1);
        assertThat(result.expirationFailedCount()).isEqualTo(1);

        // Verify log appender did not capture any sensitive substrings
        List<ILoggingEvent> logs = listAppender.list;
        assertThat(logs).isNotEmpty();

        for (ILoggingEvent event : logs) {
            String formattedMessage = event.getFormattedMessage();
            assertThat(formattedMessage).doesNotContain("secretToken12345");
            assertThat(formattedMessage).doesNotContain("SECRET_KEY");
            assertThat(formattedMessage).doesNotContain("https://");
            assertThat(formattedMessage).doesNotContain("X-Amz-Signature");
        }

        // Verify logs contain safe high-level error categories
        boolean foundPhase1Log = logs.stream()
                .anyMatch(event -> event.getLevel() == Level.WARN
                        && event.getFormattedMessage().contains("Failed to expire stale upload session")
                        && event.getFormattedMessage().contains("RuntimeException"));
        boolean foundPhase2Log = logs.stream()
                .anyMatch(event -> event.getLevel() == Level.WARN
                        && event.getFormattedMessage().contains("Guarded multipart abort failed for upload session")
                        && event.getFormattedMessage().contains("ELIGIBILITY_OR_LEASE_VIOLATION"));

        assertThat(foundPhase1Log).isTrue();
        assertThat(foundPhase2Log).isTrue();
    }
}
