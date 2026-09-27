package com.universe.wiki.application.orphan;

import com.universe.media.application.exceptions.MediaAssetNotFoundException;
import com.universe.media.contracts.interfaces.MediaContract;
import com.universe.shared.time.ClockPort;
import com.universe.wiki.application.ports.WikiCoverOrphanRepositoryPort.PrepareDeletionFenceResult;
import com.universe.wiki.application.ports.WikiArticleRepositoryPort;
import com.universe.wiki.application.ports.WikiCoverOrphanRecord;
import com.universe.wiki.application.ports.WikiCoverOrphanRepositoryPort;
import com.universe.wiki.domain.orphan.WikiCoverOrphanStatus;
import com.universe.wiki.infrastructure.maintenance.WikiCoverOrphanProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("Wiki Cover Orphan Reconciliation Service Tests")
class WikiCoverOrphanReconciliationServiceTest {

    @Mock
    private WikiCoverOrphanRepositoryPort orphanRepositoryPort;

    @Mock
    private WikiArticleRepositoryPort articleRepositoryPort;

    @Mock
    private MediaContract mediaContract;

    @Mock
    private ClockPort clockPort;

    private WikiCoverOrphanProperties properties;
    private WikiCoverOrphanReconciliationService reconciliationService;

    private final Instant now = Instant.parse("2026-09-24T10:00:00Z");

    @BeforeEach
    void setUp() {
        properties = new WikiCoverOrphanProperties(
                true,
                "0 0 2 * * *",
                "0 0 3 * * *",
                "Asia/Ho_Chi_Minh",
                300L,
                100,
                30L,   // 30 days orphan grace
                300L,  // 300 seconds processing lease duration
                10     // batch size 10
        );

        reconciliationService = new WikiCoverOrphanReconciliationService(
                orphanRepositoryPort,
                articleRepositoryPort,
                mediaContract,
                clockPort,
                properties
        );
    }

    private WikiCoverOrphanRecord createRecord(UUID assetId, Instant firstSeen) {
        return new WikiCoverOrphanRecord(
                assetId,
                WikiCoverOrphanStatus.PENDING,
                firstSeen,
                null,
                null,
                0,
                null,
                firstSeen,
                firstSeen
        );
    }

    @Test
    @DisplayName("Thực thi phục hồi stale lease trước khi truy vấn các ứng viên mới")
    void shouldExecuteStaleLeaseRecoveryBeforeClaimingNewCandidates() {
        when(clockPort.now()).thenReturn(now);
        Instant expectedLeaseCutoff = now.minus(Duration.ofSeconds(300));
        when(orphanRepositoryPort.recoverStaleProcessing(expectedLeaseCutoff, now)).thenReturn(3);
        when(orphanRepositoryPort.findEligiblePendingCandidates(any(), anyInt())).thenReturn(Collections.emptyList());

        WikiCoverOrphanReconciliationResult result = reconciliationService.reconcileOrphans();

        assertThat(result.recoveredStaleLeases()).isEqualTo(3);
        assertThat(result.eligibleClaimed()).isEqualTo(0);
        assertThat(result.successfullyDeleted()).isEqualTo(0);
        assertThat(result.activeReReferenced()).isEqualTo(0);
        assertThat(result.failedAttempts()).isEqualTo(0);

        verify(orphanRepositoryPort).recoverStaleProcessing(expectedLeaseCutoff, now);
    }

    @Test
    @DisplayName("Ranh giới ân hạn 30 ngày: tính toán chính xác graceCutoff = now - 30 ngày và giới hạn kích thước batch")
    void shouldEnforce30DayGraceBoundaryAndBatchLimit() {
        when(clockPort.now()).thenReturn(now);
        Instant expectedGraceCutoff = now.minus(Duration.ofDays(30));

        when(orphanRepositoryPort.findEligiblePendingCandidates(expectedGraceCutoff, 10))
                .thenReturn(Collections.emptyList());

        WikiCoverOrphanReconciliationResult result = reconciliationService.reconcileOrphans();

        assertThat(result.eligibleClaimed()).isEqualTo(0);
        verify(orphanRepositoryPort).findEligiblePendingCandidates(expectedGraceCutoff, 10);
    }

    @Test
    @DisplayName("Bỏ qua ứng viên khi tranh chấp atomic claim thất bại (worker khác đã claim trước)")
    void shouldSkipCandidateWhenAtomicClaimFails() {
        when(clockPort.now()).thenReturn(now);
        Instant graceCutoff = now.minus(Duration.ofDays(30));
        UUID assetId = UUID.randomUUID();
        WikiCoverOrphanRecord record = createRecord(assetId, graceCutoff.minusSeconds(10));

        when(orphanRepositoryPort.findEligiblePendingCandidates(graceCutoff, 10)).thenReturn(List.of(record));
        when(orphanRepositoryPort.claimIfEligible(eq(assetId), eq(graceCutoff), any(UUID.class), eq(now))).thenReturn(false);

        WikiCoverOrphanReconciliationResult result = reconciliationService.reconcileOrphans();

        assertThat(result.eligibleClaimed()).isEqualTo(0);
        assertThat(result.successfullyDeleted()).isEqualTo(0);

        verify(articleRepositoryPort, never()).hasCoverReference(any());
        verify(mediaContract, never()).delete(any());
        verify(orphanRepositoryPort, never()).deleteClaimedDeletingEpoch(any(), any());
    }

    @Test
    @DisplayName("Tái tham chiếu: nếu asset đã được bài viết tham chiếu lại, hủy epoch orphan và không xóa Media")
    void shouldInvalidateEpochWhenCandidateIsReReferencedByArticle() {
        when(clockPort.now()).thenReturn(now);
        Instant graceCutoff = now.minus(Duration.ofDays(30));
        UUID assetId = UUID.randomUUID();
        WikiCoverOrphanRecord record = createRecord(assetId, graceCutoff.minusSeconds(10));

        when(orphanRepositoryPort.findEligiblePendingCandidates(graceCutoff, 10)).thenReturn(List.of(record));
        when(orphanRepositoryPort.claimIfEligible(eq(assetId), eq(graceCutoff), any(UUID.class), eq(now))).thenReturn(true);
        when(orphanRepositoryPort.prepareDeletionFence(eq(assetId), any(UUID.class), eq(now)))
                .thenReturn(PrepareDeletionFenceResult.REFERENCED);

        WikiCoverOrphanReconciliationResult result = reconciliationService.reconcileOrphans();

        assertThat(result.eligibleClaimed()).isEqualTo(1);
        assertThat(result.activeReReferenced()).isEqualTo(1);
        assertThat(result.successfullyDeleted()).isEqualTo(0);

        ArgumentCaptor<UUID> tokenCaptor = ArgumentCaptor.forClass(UUID.class);
        verify(orphanRepositoryPort).claimIfEligible(eq(assetId), eq(graceCutoff), tokenCaptor.capture(), eq(now));
        UUID claimToken = tokenCaptor.getValue();

        verify(orphanRepositoryPort).prepareDeletionFence(assetId, claimToken, now);
        verify(mediaContract, never()).delete(any());
    }

    @Test
    @DisplayName("Thành công: xóa Media asset logic và dọn dẹp hàng orphan với claim token hợp lệ")
    void shouldSuccessfullyDeleteMediaAssetAndCleanOrphanEpoch() {
        when(clockPort.now()).thenReturn(now);
        Instant graceCutoff = now.minus(Duration.ofDays(30));
        UUID assetId = UUID.randomUUID();
        WikiCoverOrphanRecord record = createRecord(assetId, graceCutoff.minusSeconds(10));

        when(orphanRepositoryPort.findEligiblePendingCandidates(graceCutoff, 10)).thenReturn(List.of(record));
        when(orphanRepositoryPort.claimIfEligible(eq(assetId), eq(graceCutoff), any(UUID.class), eq(now))).thenReturn(true);
        when(orphanRepositoryPort.prepareDeletionFence(eq(assetId), any(UUID.class), eq(now)))
                .thenReturn(PrepareDeletionFenceResult.FENCED_FOR_DELETION);

        WikiCoverOrphanReconciliationResult result = reconciliationService.reconcileOrphans();

        assertThat(result.eligibleClaimed()).isEqualTo(1);
        assertThat(result.successfullyDeleted()).isEqualTo(1);
        assertThat(result.activeReReferenced()).isEqualTo(0);
        assertThat(result.failedAttempts()).isEqualTo(0);

        ArgumentCaptor<UUID> tokenCaptor = ArgumentCaptor.forClass(UUID.class);
        verify(orphanRepositoryPort).claimIfEligible(eq(assetId), eq(graceCutoff), tokenCaptor.capture(), eq(now));
        UUID claimToken = tokenCaptor.getValue();

        verify(orphanRepositoryPort).prepareDeletionFence(assetId, claimToken, now);
        verify(mediaContract).delete(assetId);
        verify(orphanRepositoryPort).deleteClaimedDeletingEpoch(assetId, claimToken);
    }

    @Test
    @DisplayName("MediaAssetNotFoundException: coi như đã dọn dẹp thành công và xóa hàng orphan")
    void shouldCleanOrphanEpochWhenMediaAssetNotFound() {
        when(clockPort.now()).thenReturn(now);
        Instant graceCutoff = now.minus(Duration.ofDays(30));
        UUID assetId = UUID.randomUUID();
        WikiCoverOrphanRecord record = createRecord(assetId, graceCutoff.minusSeconds(10));

        when(orphanRepositoryPort.findEligiblePendingCandidates(graceCutoff, 10)).thenReturn(List.of(record));
        when(orphanRepositoryPort.claimIfEligible(eq(assetId), eq(graceCutoff), any(UUID.class), eq(now))).thenReturn(true);
        when(orphanRepositoryPort.prepareDeletionFence(eq(assetId), any(UUID.class), eq(now)))
                .thenReturn(PrepareDeletionFenceResult.FENCED_FOR_DELETION);
        doThrow(new MediaAssetNotFoundException(assetId)).when(mediaContract).delete(assetId);

        WikiCoverOrphanReconciliationResult result = reconciliationService.reconcileOrphans();

        assertThat(result.eligibleClaimed()).isEqualTo(1);
        assertThat(result.successfullyDeleted()).isEqualTo(1);
        assertThat(result.failedAttempts()).isEqualTo(0);

        ArgumentCaptor<UUID> tokenCaptor = ArgumentCaptor.forClass(UUID.class);
        verify(orphanRepositoryPort).claimIfEligible(eq(assetId), eq(graceCutoff), tokenCaptor.capture(), eq(now));
        UUID claimToken = tokenCaptor.getValue();

        verify(orphanRepositoryPort).deleteClaimedDeletingEpoch(assetId, claimToken);
    }

    @Test
    @DisplayName("Lỗi tạm thời khi xóa Media: release deleting claim để retry liên tục, bảo toàn epoch")
    void shouldReleaseClaimForRetryOnTransientFailureAndPreserveEpoch() {
        when(clockPort.now()).thenReturn(now);
        Instant graceCutoff = now.minus(Duration.ofDays(30));
        UUID assetId = UUID.randomUUID();
        WikiCoverOrphanRecord record = createRecord(assetId, graceCutoff.minusSeconds(10));

        when(orphanRepositoryPort.findEligiblePendingCandidates(graceCutoff, 10)).thenReturn(List.of(record));
        when(orphanRepositoryPort.claimIfEligible(eq(assetId), eq(graceCutoff), any(UUID.class), eq(now))).thenReturn(true);
        when(orphanRepositoryPort.prepareDeletionFence(eq(assetId), any(UUID.class), eq(now)))
                .thenReturn(PrepareDeletionFenceResult.FENCED_FOR_DELETION);
        doThrow(new RuntimeException("Transient S3 / DB connection error")).when(mediaContract).delete(assetId);

        WikiCoverOrphanReconciliationResult result = reconciliationService.reconcileOrphans();

        assertThat(result.eligibleClaimed()).isEqualTo(1);
        assertThat(result.successfullyDeleted()).isEqualTo(0);
        assertThat(result.failedAttempts()).isEqualTo(1);

        ArgumentCaptor<UUID> tokenCaptor = ArgumentCaptor.forClass(UUID.class);
        verify(orphanRepositoryPort).claimIfEligible(eq(assetId), eq(graceCutoff), tokenCaptor.capture(), eq(now));
        UUID claimToken = tokenCaptor.getValue();

        verify(orphanRepositoryPort, never()).deleteClaimedDeletingEpoch(any(), any());
        verify(orphanRepositoryPort).releaseDeletingClaimForRetry(
                eq(assetId),
                eq(claimToken),
                contains("Transient S3 / DB connection error"),
                eq(now)
        );
    }

    @Test
    @DisplayName("Mất quyền sở hữu claim trước khi xóa Media: worker hủy thao tác xóa và không gọi MediaContract")
    void shouldAbortDeleteWhenClaimOwnershipLostBeforeDelete() {
        when(clockPort.now()).thenReturn(now);
        Instant graceCutoff = now.minus(Duration.ofDays(30));
        UUID assetId = UUID.randomUUID();
        WikiCoverOrphanRecord record = createRecord(assetId, graceCutoff.minusSeconds(10));

        when(orphanRepositoryPort.findEligiblePendingCandidates(graceCutoff, 10)).thenReturn(List.of(record));
        when(orphanRepositoryPort.claimIfEligible(eq(assetId), eq(graceCutoff), any(UUID.class), eq(now))).thenReturn(true);
        // Ownership check returns false (stale lease recovery revoked lease while worker paused)
        when(orphanRepositoryPort.prepareDeletionFence(eq(assetId), any(UUID.class), eq(now)))
                .thenReturn(PrepareDeletionFenceResult.LOST_OWNERSHIP);

        WikiCoverOrphanReconciliationResult result = reconciliationService.reconcileOrphans();

        assertThat(result.eligibleClaimed()).isEqualTo(1);
        assertThat(result.successfullyDeleted()).isEqualTo(0);
        assertThat(result.failedAttempts()).isEqualTo(0);

        verify(mediaContract, never()).delete(any());
        verify(orphanRepositoryPort, never()).deleteClaimedDeletingEpoch(any(), any());
    }

    @Test
    @DisplayName("Phase 1 retry: xử lý các ứng viên DELETING quá hạn hoặc chưa có claim token")
    void shouldRetryStaleDeletingCandidatesSuccessfully() {
        when(clockPort.now()).thenReturn(now);
        Instant leaseCutoff = now.minus(Duration.ofSeconds(300));
        UUID deletingAssetId = UUID.randomUUID();
        WikiCoverOrphanRecord deletingRecord = new WikiCoverOrphanRecord(
                deletingAssetId,
                WikiCoverOrphanStatus.DELETING,
                now.minus(Duration.ofDays(31)),
                null,
                null,
                1,
                "Previous transient error",
                now.minus(Duration.ofDays(31)),
                now.minusSeconds(600)
        );

        when(orphanRepositoryPort.findStaleDeletingCandidates(leaseCutoff, 10)).thenReturn(List.of(deletingRecord));
        when(orphanRepositoryPort.claimDeletingForRetry(eq(deletingAssetId), eq(leaseCutoff), any(UUID.class), eq(now))).thenReturn(true);
        when(orphanRepositoryPort.findEligiblePendingCandidates(any(), anyInt())).thenReturn(Collections.emptyList());

        WikiCoverOrphanReconciliationResult result = reconciliationService.reconcileOrphans();

        assertThat(result.successfullyDeleted()).isEqualTo(1);
        verify(mediaContract).delete(deletingAssetId);
        ArgumentCaptor<UUID> tokenCaptor = ArgumentCaptor.forClass(UUID.class);
        verify(orphanRepositoryPort).claimDeletingForRetry(eq(deletingAssetId), eq(leaseCutoff), tokenCaptor.capture(), eq(now));
        verify(orphanRepositoryPort).deleteClaimedDeletingEpoch(eq(deletingAssetId), eq(tokenCaptor.getValue()));
    }

    @Test
    @DisplayName("Phase 1 retry: lỗi tạm thời khi retry DELETING giải phóng claim để tiếp tục retry vòng sau")
    void shouldReleaseDeletingClaimOnTransientFailureDuringRetry() {
        when(clockPort.now()).thenReturn(now);
        Instant leaseCutoff = now.minus(Duration.ofSeconds(300));
        UUID deletingAssetId = UUID.randomUUID();
        WikiCoverOrphanRecord deletingRecord = new WikiCoverOrphanRecord(
                deletingAssetId,
                WikiCoverOrphanStatus.DELETING,
                now.minus(Duration.ofDays(31)),
                null,
                null,
                1,
                "Previous transient error",
                now.minus(Duration.ofDays(31)),
                now.minusSeconds(600)
        );

        when(orphanRepositoryPort.findStaleDeletingCandidates(leaseCutoff, 10)).thenReturn(List.of(deletingRecord));
        when(orphanRepositoryPort.claimDeletingForRetry(eq(deletingAssetId), eq(leaseCutoff), any(UUID.class), eq(now))).thenReturn(true);
        doThrow(new RuntimeException("Transient storage outage")).when(mediaContract).delete(deletingAssetId);
        when(orphanRepositoryPort.findEligiblePendingCandidates(any(), anyInt())).thenReturn(Collections.emptyList());

        WikiCoverOrphanReconciliationResult result = reconciliationService.reconcileOrphans();

        assertThat(result.successfullyDeleted()).isEqualTo(0);
        assertThat(result.failedAttempts()).isEqualTo(1);
        verify(mediaContract).delete(deletingAssetId);
        ArgumentCaptor<UUID> tokenCaptor = ArgumentCaptor.forClass(UUID.class);
        verify(orphanRepositoryPort).claimDeletingForRetry(eq(deletingAssetId), eq(leaseCutoff), tokenCaptor.capture(), eq(now));
        verify(orphanRepositoryPort).releaseDeletingClaimForRetry(
                eq(deletingAssetId),
                eq(tokenCaptor.getValue()),
                contains("Transient storage outage"),
                eq(now)
        );
    }
}
