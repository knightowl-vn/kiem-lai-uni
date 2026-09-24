package com.universe.wiki.application.orphan;

import com.universe.media.contracts.dto.FindActiveMediaAssetsKeysetQuery;
import com.universe.media.contracts.dto.MediaAssetCandidateDTO;
import com.universe.media.contracts.interfaces.MediaContract;
import com.universe.shared.time.ClockPort;
import com.universe.wiki.application.article.cover.WikiCoverMediaCoordinator;
import com.universe.wiki.application.ports.WikiArticleRepositoryPort;
import com.universe.wiki.application.ports.WikiCoverOrphanRepositoryPort;
import com.universe.wiki.application.ports.WikiCoverOrphanRepositoryPort.ClearReferencedOrphanResult;
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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("Wiki Cover Orphan Discovery Service Tests")
class WikiCoverOrphanDiscoveryServiceTest {

    @Mock
    private MediaContract mediaContract;

    @Mock
    private WikiArticleRepositoryPort articleRepositoryPort;

    @Mock
    private WikiCoverOrphanRepositoryPort orphanRepositoryPort;

    @Mock
    private ClockPort clockPort;

    private WikiCoverOrphanProperties properties;
    private WikiCoverOrphanDiscoveryService discoveryService;

    private final Instant now = Instant.parse("2026-09-24T10:00:00Z");

    @BeforeEach
    void setUp() {
        properties = new WikiCoverOrphanProperties(
                true,
                "0 0 2 * * *",
                "0 0 3 * * *",
                "Asia/Ho_Chi_Minh",
                300L,  // 5 minutes safety window
                2,     // small page size for pagination tests
                30L,
                300L,
                50
        );

        discoveryService = new WikiCoverOrphanDiscoveryService(
                mediaContract,
                articleRepositoryPort,
                orphanRepositoryPort,
                clockPort,
                properties
        );
    }

    @Test
    @DisplayName("Crash orphan: ghi nhận quan sát orphan với now() khi asset không còn reference trong Wiki")
    void shouldRecordOrphanObservationWhenAssetHasNoWikiReferences() {
        when(clockPort.now()).thenReturn(now);

        UUID orphanAssetId = UUID.randomUUID();
        Instant candidateCreatedAt = now.minus(Duration.ofMinutes(10));
        MediaAssetCandidateDTO candidate = new MediaAssetCandidateDTO(orphanAssetId, candidateCreatedAt);

        when(mediaContract.findActiveAssetsByClientTagKeyset(any())).thenReturn(
                List.of(candidate),
                Collections.emptyList()
        );
        when(articleRepositoryPort.hasCoverReference(orphanAssetId)).thenReturn(false);

        WikiCoverOrphanDiscoveryResult result = discoveryService.discoverOrphans();

        assertThat(result.scannedCandidates()).isEqualTo(1);
        assertThat(result.observedOrphans()).isEqualTo(1);
        assertThat(result.activeReferenced()).isEqualTo(0);
        assertThat(result.failedInspections()).isEqualTo(0);

        verify(orphanRepositoryPort).recordOrphanObservation(orphanAssetId, now);
        verify(orphanRepositoryPort, never()).deleteByMediaAssetId(orphanAssetId);
    }

    @Test
    @DisplayName("Referenced asset: xóa epoch orphan cũ khi asset được phát hiện vẫn đang có reference")
    void shouldClearOrphanEpochWhenDiscoveredAssetIsStillReferenced() {
        when(clockPort.now()).thenReturn(now);

        UUID referencedAssetId = UUID.randomUUID();
        Instant candidateCreatedAt = now.minus(Duration.ofMinutes(10));
        MediaAssetCandidateDTO candidate = new MediaAssetCandidateDTO(referencedAssetId, candidateCreatedAt);

        when(mediaContract.findActiveAssetsByClientTagKeyset(any())).thenReturn(
                List.of(candidate),
                Collections.emptyList()
        );
        when(articleRepositoryPort.hasCoverReference(referencedAssetId)).thenReturn(true);

        WikiCoverOrphanDiscoveryResult result = discoveryService.discoverOrphans();

        assertThat(result.scannedCandidates()).isEqualTo(1);
        assertThat(result.observedOrphans()).isEqualTo(0);
        assertThat(result.activeReferenced()).isEqualTo(1);
        assertThat(result.failedInspections()).isEqualTo(0);

        verify(orphanRepositoryPort).clearNonDeletingOrphanForReference(referencedAssetId);
        verify(orphanRepositoryPort, never()).recordOrphanObservation(eq(referencedAssetId), any());
    }

    @Test
    @DisplayName("Fixed upper bound: giữ nguyên upper bound cố định qua tất cả các trang phân trang keyset")
    void shouldEnforceFixedUpperBoundAcrossPagination() {
        when(clockPort.now()).thenReturn(now);

        Instant expectedUpperBound = now.minus(Duration.ofSeconds(300));

        UUID asset1 = UUID.randomUUID();
        UUID asset2 = UUID.randomUUID();
        UUID asset3 = UUID.randomUUID();

        Instant t1 = now.minus(Duration.ofMinutes(20));
        Instant t2 = now.minus(Duration.ofMinutes(15));
        Instant t3 = now.minus(Duration.ofMinutes(10));

        MediaAssetCandidateDTO c1 = new MediaAssetCandidateDTO(asset1, t1);
        MediaAssetCandidateDTO c2 = new MediaAssetCandidateDTO(asset2, t2);
        MediaAssetCandidateDTO c3 = new MediaAssetCandidateDTO(asset3, t3);

        // Page 1: c1, c2 (size == pageSize 2)
        // Page 2: c3 (size < pageSize 2) -> termination
        when(mediaContract.findActiveAssetsByClientTagKeyset(any())).thenReturn(
                List.of(c1, c2),
                List.of(c3)
        );
        when(articleRepositoryPort.hasCoverReference(any())).thenReturn(false);

        WikiCoverOrphanDiscoveryResult result = discoveryService.discoverOrphans();

        assertThat(result.scannedCandidates()).isEqualTo(3);
        assertThat(result.observedOrphans()).isEqualTo(3);

        ArgumentCaptor<FindActiveMediaAssetsKeysetQuery> queryCaptor =
                ArgumentCaptor.forClass(FindActiveMediaAssetsKeysetQuery.class);
        verify(mediaContract, times(2)).findActiveAssetsByClientTagKeyset(queryCaptor.capture());

        List<FindActiveMediaAssetsKeysetQuery> queries = queryCaptor.getAllValues();
        FindActiveMediaAssetsKeysetQuery page1Query = queries.get(0);
        FindActiveMediaAssetsKeysetQuery page2Query = queries.get(1);

        assertThat(page1Query.clientTag()).isEqualTo(WikiCoverMediaCoordinator.WIKI_ARTICLE_COVER_CLIENT_TAG);
        assertThat(page1Query.createdBeforeUpperBound()).isEqualTo(expectedUpperBound);
        assertThat(page1Query.lastCreatedAt()).isNull();
        assertThat(page1Query.lastAssetId()).isNull();
        assertThat(page1Query.pageSize()).isEqualTo(2);

        assertThat(page2Query.clientTag()).isEqualTo(WikiCoverMediaCoordinator.WIKI_ARTICLE_COVER_CLIENT_TAG);
        assertThat(page2Query.createdBeforeUpperBound()).isEqualTo(expectedUpperBound); // Identical fixed upper bound
        assertThat(page2Query.lastCreatedAt()).isEqualTo(t2);
        assertThat(page2Query.lastAssetId()).isEqualTo(asset2);
        assertThat(page2Query.pageSize()).isEqualTo(2);
    }

    @Test
    @DisplayName("Keyset starvation safety: phân trang tuần tự đến khi danh sách rỗng, không bỏ sót candidate")
    void shouldPaginateUntilExhaustionWithoutStarvation() {
        when(clockPort.now()).thenReturn(now);

        UUID asset1 = UUID.randomUUID();
        UUID asset2 = UUID.randomUUID();
        UUID asset3 = UUID.randomUUID();
        UUID asset4 = UUID.randomUUID();

        Instant t = now.minus(Duration.ofMinutes(10));
        MediaAssetCandidateDTO c1 = new MediaAssetCandidateDTO(asset1, t);
        MediaAssetCandidateDTO c2 = new MediaAssetCandidateDTO(asset2, t);
        MediaAssetCandidateDTO c3 = new MediaAssetCandidateDTO(asset3, t);
        MediaAssetCandidateDTO c4 = new MediaAssetCandidateDTO(asset4, t);

        // Page 1: c1, c2 (size 2 == pageSize)
        // Page 2: c3, c4 (size 2 == pageSize)
        // Page 3: empty list -> terminates
        when(mediaContract.findActiveAssetsByClientTagKeyset(any())).thenReturn(
                List.of(c1, c2),
                List.of(c3, c4),
                Collections.emptyList()
        );
        when(articleRepositoryPort.hasCoverReference(any())).thenReturn(false);

        WikiCoverOrphanDiscoveryResult result = discoveryService.discoverOrphans();

        assertThat(result.scannedCandidates()).isEqualTo(4);
        assertThat(result.observedOrphans()).isEqualTo(4);
        verify(mediaContract, times(3)).findActiveAssetsByClientTagKeyset(any());
        verify(orphanRepositoryPort, times(4)).recordOrphanObservation(any(), eq(now));
    }

    @Test
    @DisplayName("Failure isolation: lỗi kiểm tra của một candidate không làm gián đoạn toàn bộ batch scan")
    void shouldIsolateIndividualCandidateFailuresAndContinueScan() {
        when(clockPort.now()).thenReturn(now);

        UUID goodAsset1 = UUID.randomUUID();
        UUID failingAsset = UUID.randomUUID();
        UUID goodAsset2 = UUID.randomUUID();

        Instant t = now.minus(Duration.ofMinutes(10));
        MediaAssetCandidateDTO c1 = new MediaAssetCandidateDTO(goodAsset1, t);
        MediaAssetCandidateDTO c2 = new MediaAssetCandidateDTO(failingAsset, t);
        MediaAssetCandidateDTO c3 = new MediaAssetCandidateDTO(goodAsset2, t);

        when(mediaContract.findActiveAssetsByClientTagKeyset(any())).thenReturn(
                List.of(c1, c2, c3) // size 3 > pageSize (terminates because size < 2 is false, but we can return 3 if mocked)
        );
        // Since page size is 2, returning 3 means candidates.size() < pageSize is false. So let's mock second call to return empty list.
        when(mediaContract.findActiveAssetsByClientTagKeyset(any())).thenReturn(
                List.of(c1, c2),
                List.of(c3)
        );

        when(articleRepositoryPort.hasCoverReference(goodAsset1)).thenReturn(false);
        when(articleRepositoryPort.hasCoverReference(failingAsset)).thenThrow(new RuntimeException("DB connection timeout"));
        when(articleRepositoryPort.hasCoverReference(goodAsset2)).thenReturn(true);

        WikiCoverOrphanDiscoveryResult result = discoveryService.discoverOrphans();

        assertThat(result.scannedCandidates()).isEqualTo(3);
        assertThat(result.observedOrphans()).isEqualTo(1);
        assertThat(result.activeReferenced()).isEqualTo(1);
        assertThat(result.failedInspections()).isEqualTo(1);

        verify(orphanRepositoryPort).recordOrphanObservation(goodAsset1, now);
        verify(orphanRepositoryPort).clearNonDeletingOrphanForReference(goodAsset2);
    }

    @Test
    @DisplayName("DELETING invariant breach: bảo toàn hàng DELETING và ghi nhận telemetry khi phát hiện reference")
    void shouldPreserveDeletingFenceAndRecordTelemetryWhenReferencedAssetIsDeleting() {
        when(clockPort.now()).thenReturn(now);

        UUID deletingAssetId = UUID.randomUUID();
        Instant candidateCreatedAt = now.minus(Duration.ofMinutes(10));
        MediaAssetCandidateDTO candidate = new MediaAssetCandidateDTO(deletingAssetId, candidateCreatedAt);

        when(mediaContract.findActiveAssetsByClientTagKeyset(any())).thenReturn(
                List.of(candidate),
                Collections.emptyList()
        );
        when(articleRepositoryPort.hasCoverReference(deletingAssetId)).thenReturn(true);
        when(orphanRepositoryPort.clearNonDeletingOrphanForReference(deletingAssetId))
                .thenReturn(ClearReferencedOrphanResult.DELETING_PRESERVED);

        WikiCoverOrphanDiscoveryResult result = discoveryService.discoverOrphans();

        assertThat(result.scannedCandidates()).isEqualTo(1);
        assertThat(result.observedOrphans()).isEqualTo(0);
        assertThat(result.activeReferenced()).isEqualTo(1);
        assertThat(result.deletingPreserved()).isEqualTo(1);
        assertThat(result.failedInspections()).isEqualTo(0);

        verify(orphanRepositoryPort).clearNonDeletingOrphanForReference(deletingAssetId);
        verify(orphanRepositoryPort, never()).recordOrphanObservation(eq(deletingAssetId), any());
    }

    @Test
    @DisplayName("Xử lý nhẹ nhàng khi không tìm thấy candidate nào phù hợp")
    void shouldHandleEmptyCandidateListGracefully() {
        when(clockPort.now()).thenReturn(now);
        when(mediaContract.findActiveAssetsByClientTagKeyset(any())).thenReturn(Collections.emptyList());

        WikiCoverOrphanDiscoveryResult result = discoveryService.discoverOrphans();

        assertThat(result.scannedCandidates()).isEqualTo(0);
        assertThat(result.observedOrphans()).isEqualTo(0);
        assertThat(result.activeReferenced()).isEqualTo(0);
        assertThat(result.failedInspections()).isEqualTo(0);

        verify(articleRepositoryPort, never()).hasCoverReference(any());
        verify(orphanRepositoryPort, never()).recordOrphanObservation(any(), any());
    }
}
