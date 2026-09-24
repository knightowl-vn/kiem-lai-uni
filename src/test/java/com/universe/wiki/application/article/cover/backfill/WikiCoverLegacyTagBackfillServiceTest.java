package com.universe.wiki.application.article.cover.backfill;

import com.universe.media.application.exceptions.MediaAssetNotFoundException;
import com.universe.media.contracts.interfaces.MediaContract;
import com.universe.media.domain.ClientTagConflictException;
import com.universe.wiki.application.article.cover.WikiCoverMediaCoordinator;
import com.universe.wiki.application.ports.WikiArticleRepositoryPort;
import com.universe.wiki.infrastructure.maintenance.WikiCoverLegacyTagBackfillProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("Wiki Cover Legacy Tag Backfill Service Unit Tests")
class WikiCoverLegacyTagBackfillServiceTest {

    private static final String CANONICAL_TAG = WikiCoverMediaCoordinator.WIKI_ARTICLE_COVER_CLIENT_TAG;

    @Mock
    private MediaContract mediaContract;

    @Mock
    private WikiArticleRepositoryPort articleRepositoryPort;

    private WikiCoverLegacyTagBackfillProperties properties;
    private WikiCoverLegacyTagBackfillService service;

    @BeforeEach
    void setUp() {
        properties = new WikiCoverLegacyTagBackfillProperties(true, "0 0 1 * * *", "Asia/Ho_Chi_Minh", 100);
        service = new WikiCoverLegacyTagBackfillService(mediaContract, articleRepositoryPort, properties);
    }

    @Test
    @DisplayName("Trả về kết quả rỗng khi không có ảnh bìa nào được tham chiếu")
    void shouldReturnEmptyResultWhenNoReferencedCoversExist() {
        when(articleRepositoryPort.findMaxCoverMediaAssetId()).thenReturn(Optional.empty());

        WikiCoverLegacyTagBackfillResult result = service.backfillLegacyCoverTags();

        assertThat(result.scannedAssets()).isZero();
        assertThat(result.successfulAssignmentsOrAlreadyTagged()).isZero();
        assertThat(result.conflicts()).isZero();
        assertThat(result.missingMediaAssets()).isZero();
        assertThat(result.failedAssets()).isZero();

        verify(articleRepositoryPort, never()).findDistinctCoverMediaAssetIdsKeyset(any());
        verify(mediaContract, never()).assignClientTagIfAbsent(any(), any());
    }

    @Test
    @DisplayName("Gán client_tag thành công cho tài nguyên bìa legacy được tham chiếu")
    void shouldAssignTagToReferencedCover() {
        UUID assetId = UUID.fromString("11111111-1111-1111-1111-111111111111");
        String assetIdStr = assetId.toString();

        when(articleRepositoryPort.findMaxCoverMediaAssetId()).thenReturn(Optional.of(assetIdStr));
        when(articleRepositoryPort.findDistinctCoverMediaAssetIdsKeyset(WikiReferencedCoverKeysetQuery.firstPage(assetIdStr, 100)))
                .thenReturn(List.of(assetIdStr));

        WikiCoverLegacyTagBackfillResult result = service.backfillLegacyCoverTags();

        assertThat(result.scannedAssets()).isEqualTo(1);
        assertThat(result.successfulAssignmentsOrAlreadyTagged()).isEqualTo(1);
        assertThat(result.conflicts()).isZero();
        assertThat(result.missingMediaAssets()).isZero();
        assertThat(result.failedAssets()).isZero();

        verify(mediaContract).assignClientTagIfAbsent(assetId, CANONICAL_TAG);
    }

    @Test
    @DisplayName("Xử lý idempotent khi tài nguyên đã có sẵn client_tag hợp lệ")
    void shouldHandleSameTagIdempotently() {
        UUID assetId = UUID.fromString("11111111-1111-1111-1111-111111111111");
        String assetIdStr = assetId.toString();

        when(articleRepositoryPort.findMaxCoverMediaAssetId()).thenReturn(Optional.of(assetIdStr));
        when(articleRepositoryPort.findDistinctCoverMediaAssetIdsKeyset(WikiReferencedCoverKeysetQuery.firstPage(assetIdStr, 100)))
                .thenReturn(List.of(assetIdStr));
        doNothing().when(mediaContract).assignClientTagIfAbsent(assetId, CANONICAL_TAG);

        WikiCoverLegacyTagBackfillResult result = service.backfillLegacyCoverTags();

        assertThat(result.scannedAssets()).isEqualTo(1);
        assertThat(result.successfulAssignmentsOrAlreadyTagged()).isEqualTo(1);
        assertThat(result.conflicts()).isZero();
        assertThat(result.missingMediaAssets()).isZero();
        assertThat(result.failedAssets()).isZero();
    }

    @Test
    @DisplayName("Xử lý ngoại lệ ClientTagConflictException và tiếp tục quét các tài nguyên khác")
    void shouldHandleConflictAndContinueScan() {
        UUID conflictedId = UUID.fromString("11111111-1111-1111-1111-111111111111");
        UUID validId = UUID.fromString("22222222-2222-2222-2222-222222222222");
        String upperBound = validId.toString();

        when(articleRepositoryPort.findMaxCoverMediaAssetId()).thenReturn(Optional.of(upperBound));
        when(articleRepositoryPort.findDistinctCoverMediaAssetIdsKeyset(WikiReferencedCoverKeysetQuery.firstPage(upperBound, 100)))
                .thenReturn(List.of(conflictedId.toString(), validId.toString()));

        doThrow(new ClientTagConflictException(conflictedId, "novel.chapter.illustration", CANONICAL_TAG))
                .when(mediaContract).assignClientTagIfAbsent(conflictedId, CANONICAL_TAG);
        doNothing().when(mediaContract).assignClientTagIfAbsent(validId, CANONICAL_TAG);

        WikiCoverLegacyTagBackfillResult result = service.backfillLegacyCoverTags();

        assertThat(result.scannedAssets()).isEqualTo(2);
        assertThat(result.successfulAssignmentsOrAlreadyTagged()).isEqualTo(1);
        assertThat(result.conflicts()).isEqualTo(1);
        assertThat(result.missingMediaAssets()).isZero();
        assertThat(result.failedAssets()).isZero();

        verify(mediaContract).assignClientTagIfAbsent(conflictedId, CANONICAL_TAG);
        verify(mediaContract).assignClientTagIfAbsent(validId, CANONICAL_TAG);
    }

    @Test
    @DisplayName("Xử lý ngoại lệ MediaAssetNotFoundException và tiếp tục quét các tài nguyên khác")
    void shouldHandleMediaNotFoundAndContinueScan() {
        UUID missingId = UUID.fromString("11111111-1111-1111-1111-111111111111");
        UUID validId = UUID.fromString("22222222-2222-2222-2222-222222222222");
        String upperBound = validId.toString();

        when(articleRepositoryPort.findMaxCoverMediaAssetId()).thenReturn(Optional.of(upperBound));
        when(articleRepositoryPort.findDistinctCoverMediaAssetIdsKeyset(WikiReferencedCoverKeysetQuery.firstPage(upperBound, 100)))
                .thenReturn(List.of(missingId.toString(), validId.toString()));

        doThrow(new MediaAssetNotFoundException(missingId))
                .when(mediaContract).assignClientTagIfAbsent(missingId, CANONICAL_TAG);
        doNothing().when(mediaContract).assignClientTagIfAbsent(validId, CANONICAL_TAG);

        WikiCoverLegacyTagBackfillResult result = service.backfillLegacyCoverTags();

        assertThat(result.scannedAssets()).isEqualTo(2);
        assertThat(result.successfulAssignmentsOrAlreadyTagged()).isEqualTo(1);
        assertThat(result.conflicts()).isZero();
        assertThat(result.missingMediaAssets()).isEqualTo(1);
        assertThat(result.failedAssets()).isZero();

        verify(mediaContract).assignClientTagIfAbsent(missingId, CANONICAL_TAG);
        verify(mediaContract).assignClientTagIfAbsent(validId, CANONICAL_TAG);
    }

    @Test
    @DisplayName("Xử lý ngoại lệ RuntimeException bất ngờ trên một tài nguyên và tiếp tục quét")
    void shouldHandleTransientRuntimeExceptionAndContinueScan() {
        UUID failedId = UUID.fromString("11111111-1111-1111-1111-111111111111");
        UUID validId = UUID.fromString("22222222-2222-2222-2222-222222222222");
        String upperBound = validId.toString();

        when(articleRepositoryPort.findMaxCoverMediaAssetId()).thenReturn(Optional.of(upperBound));
        when(articleRepositoryPort.findDistinctCoverMediaAssetIdsKeyset(WikiReferencedCoverKeysetQuery.firstPage(upperBound, 100)))
                .thenReturn(List.of(failedId.toString(), validId.toString()));

        doThrow(new RuntimeException("Transient DB timeout"))
                .when(mediaContract).assignClientTagIfAbsent(failedId, CANONICAL_TAG);
        doNothing().when(mediaContract).assignClientTagIfAbsent(validId, CANONICAL_TAG);

        WikiCoverLegacyTagBackfillResult result = service.backfillLegacyCoverTags();

        assertThat(result.scannedAssets()).isEqualTo(2);
        assertThat(result.successfulAssignmentsOrAlreadyTagged()).isEqualTo(1);
        assertThat(result.conflicts()).isZero();
        assertThat(result.missingMediaAssets()).isZero();
        assertThat(result.failedAssets()).isEqualTo(1);

        verify(mediaContract).assignClientTagIfAbsent(failedId, CANONICAL_TAG);
        verify(mediaContract).assignClientTagIfAbsent(validId, CANONICAL_TAG);
    }

    @Test
    @DisplayName("Bảo vệ trước định dạng UUID không hợp lệ từ database")
    void shouldHandleInvalidUuidFormatAndContinueScan() {
        String invalidIdStr = "not-a-valid-uuid";
        UUID validId = UUID.fromString("22222222-2222-2222-2222-222222222222");
        String upperBound = validId.toString();

        when(articleRepositoryPort.findMaxCoverMediaAssetId()).thenReturn(Optional.of(upperBound));
        when(articleRepositoryPort.findDistinctCoverMediaAssetIdsKeyset(WikiReferencedCoverKeysetQuery.firstPage(upperBound, 100)))
                .thenReturn(List.of(invalidIdStr, validId.toString()));

        WikiCoverLegacyTagBackfillResult result = service.backfillLegacyCoverTags();

        assertThat(result.scannedAssets()).isEqualTo(2);
        assertThat(result.successfulAssignmentsOrAlreadyTagged()).isEqualTo(1);
        assertThat(result.conflicts()).isZero();
        assertThat(result.missingMediaAssets()).isZero();
        assertThat(result.failedAssets()).isEqualTo(1);

        verify(mediaContract, never()).assignClientTagIfAbsent(eq(null), any());
        verify(mediaContract).assignClientTagIfAbsent(validId, CANONICAL_TAG);
    }

    @Test
    @DisplayName("Khử trùng lặp ảnh bìa được dùng chung giữa nhiều bài viết qua câu truy vấn DISTINCT")
    void shouldDeduplicateSharedCoverReferencesViaDistinctQuery() {
        UUID sharedAssetId = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
        String sharedIdStr = sharedAssetId.toString();

        when(articleRepositoryPort.findMaxCoverMediaAssetId()).thenReturn(Optional.of(sharedIdStr));
        // Query returns distinct list containing the ID only once
        when(articleRepositoryPort.findDistinctCoverMediaAssetIdsKeyset(WikiReferencedCoverKeysetQuery.firstPage(sharedIdStr, 100)))
                .thenReturn(List.of(sharedIdStr));

        WikiCoverLegacyTagBackfillResult result = service.backfillLegacyCoverTags();

        assertThat(result.scannedAssets()).isEqualTo(1);
        assertThat(result.successfulAssignmentsOrAlreadyTagged()).isEqualTo(1);
        verify(mediaContract, times(1)).assignClientTagIfAbsent(sharedAssetId, CANONICAL_TAG);
    }

    @Test
    @DisplayName("Duyệt keyset nhiều trang theo thứ tự tăng dần mà không dùng OFFSET")
    void shouldTraverseMultiplePagesWithDeterministicDbOrdering() {
        String id1 = "11111111-1111-1111-1111-111111111111";
        String id2 = "22222222-2222-2222-2222-222222222222";
        String id3 = "33333333-3333-3333-3333-333333333333";
        String id4 = "44444444-4444-4444-4444-444444444444";
        String id5 = "55555555-5555-5555-5555-555555555555";
        String upperBound = id5;
        int pageSize = 2;

        when(articleRepositoryPort.findMaxCoverMediaAssetId()).thenReturn(Optional.of(upperBound));

        // Page 1
        when(articleRepositoryPort.findDistinctCoverMediaAssetIdsKeyset(
                WikiReferencedCoverKeysetQuery.firstPage(upperBound, pageSize)))
                .thenReturn(List.of(id1, id2));

        // Page 2
        when(articleRepositoryPort.findDistinctCoverMediaAssetIdsKeyset(
                WikiReferencedCoverKeysetQuery.nextPage(id2, upperBound, pageSize)))
                .thenReturn(List.of(id3, id4));

        // Page 3
        when(articleRepositoryPort.findDistinctCoverMediaAssetIdsKeyset(
                WikiReferencedCoverKeysetQuery.nextPage(id4, upperBound, pageSize)))
                .thenReturn(List.of(id5));

        WikiCoverLegacyTagBackfillResult result = service.backfillLegacyCoverTags(pageSize);

        assertThat(result.scannedAssets()).isEqualTo(5);
        assertThat(result.successfulAssignmentsOrAlreadyTagged()).isEqualTo(5);
        assertThat(result.conflicts()).isZero();
        assertThat(result.missingMediaAssets()).isZero();
        assertThat(result.failedAssets()).isZero();

        ArgumentCaptor<UUID> captor = ArgumentCaptor.forClass(UUID.class);
        verify(mediaContract, times(5)).assignClientTagIfAbsent(captor.capture(), eq(CANONICAL_TAG));
        assertThat(captor.getAllValues()).containsExactly(
                UUID.fromString(id1),
                UUID.fromString(id2),
                UUID.fromString(id3),
                UUID.fromString(id4),
                UUID.fromString(id5)
        );
    }

    @Test
    @DisplayName("Cố định runUpperBound ngay từ đầu lần chạy và không đuổi theo tài nguyên mới ngoài biên")
    void shouldEnforceFixedUpperBoundAndNotChaseNewAssets() {
        String id1 = "11111111-1111-1111-1111-111111111111";
        String id2 = "22222222-2222-2222-2222-222222222222";
        String fixedUpperBound = id2;
        int pageSize = 1;

        when(articleRepositoryPort.findMaxCoverMediaAssetId()).thenReturn(Optional.of(fixedUpperBound));

        // Page 1: returns id1, bounded by fixedUpperBound
        when(articleRepositoryPort.findDistinctCoverMediaAssetIdsKeyset(
                WikiReferencedCoverKeysetQuery.firstPage(fixedUpperBound, pageSize)))
                .thenReturn(List.of(id1));

        // Page 2: returns id2, bounded by fixedUpperBound
        when(articleRepositoryPort.findDistinctCoverMediaAssetIdsKeyset(
                WikiReferencedCoverKeysetQuery.nextPage(id1, fixedUpperBound, pageSize)))
                .thenReturn(List.of(id2));

        // Next page returns empty because fixedUpperBound was reached
        when(articleRepositoryPort.findDistinctCoverMediaAssetIdsKeyset(
                WikiReferencedCoverKeysetQuery.nextPage(id2, fixedUpperBound, pageSize)))
                .thenReturn(Collections.emptyList());

        WikiCoverLegacyTagBackfillResult result = service.backfillLegacyCoverTags(pageSize);

        assertThat(result.scannedAssets()).isEqualTo(2);
        assertThat(result.successfulAssignmentsOrAlreadyTagged()).isEqualTo(2);

        // Verify all calls were strictly bounded by fixedUpperBound
        verify(articleRepositoryPort).findDistinctCoverMediaAssetIdsKeyset(
                WikiReferencedCoverKeysetQuery.firstPage(fixedUpperBound, pageSize));
        verify(articleRepositoryPort).findDistinctCoverMediaAssetIdsKeyset(
                WikiReferencedCoverKeysetQuery.nextPage(id1, fixedUpperBound, pageSize));
        verify(articleRepositoryPort).findDistinctCoverMediaAssetIdsKeyset(
                WikiReferencedCoverKeysetQuery.nextPage(id2, fixedUpperBound, pageSize));
    }

    @Test
    @DisplayName("Từ chối pageSize không hợp lệ khi chạy thủ công")
    void shouldRejectInvalidPageSize() {
        assertThatThrownBy(() -> service.backfillLegacyCoverTags(0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("pageSize must be between 1 and 100");

        assertThatThrownBy(() -> service.backfillLegacyCoverTags(101))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("pageSize must be between 1 and 100");
    }
}
