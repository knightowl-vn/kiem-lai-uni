package com.universe.media.application.asset;

import com.universe.media.application.ports.MediaAssetCandidate;
import com.universe.media.application.ports.MediaAssetRepositoryPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FindActiveMediaAssetsByClientTagKeysetUseCaseTest {

    private static final String CLIENT_TAG = "wiki.article.cover";
    private static final Instant UPPER_BOUND = Instant.parse("2026-09-01T12:00:00Z");
    private static final Instant T0 = Instant.parse("2026-09-01T10:00:00Z");
    private static final UUID ASSET_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");

    @Mock
    private MediaAssetRepositoryPort mediaAssetRepositoryPort;

    private FindActiveMediaAssetsByClientTagKeysetUseCase useCase;

    @BeforeEach
    void setUp() {
        useCase = new FindActiveMediaAssetsByClientTagKeysetUseCase(mediaAssetRepositoryPort);
    }

    @Test
    @DisplayName("delegates first-page query to persistence port")
    void shouldDelegateFirstPageQueryToPort() {
        FindActiveMediaAssetsKeysetQuery query =
                FindActiveMediaAssetsKeysetQuery.firstPage(CLIENT_TAG, UPPER_BOUND, 20);

        MediaAssetCandidate candidate = new MediaAssetCandidate(ASSET_ID, T0);
        when(mediaAssetRepositoryPort.findActiveByClientTagKeyset(
                CLIENT_TAG,
                UPPER_BOUND,
                null,
                null,
                20
        )).thenReturn(List.of(candidate));

        List<MediaAssetCandidate> result = useCase.execute(query);

        assertThat(result).containsExactly(candidate);
        verify(mediaAssetRepositoryPort).findActiveByClientTagKeyset(
                CLIENT_TAG,
                UPPER_BOUND,
                null,
                null,
                20
        );
    }

    @Test
    @DisplayName("delegates subsequent-page query to persistence port")
    void shouldDelegateSubsequentPageQueryToPort() {
        FindActiveMediaAssetsKeysetQuery query =
                FindActiveMediaAssetsKeysetQuery.nextPage(CLIENT_TAG, UPPER_BOUND, T0, ASSET_ID, 20);

        UUID nextAssetId = UUID.fromString("22222222-2222-2222-2222-222222222222");
        Instant nextCreatedAt = T0.plusSeconds(10);
        MediaAssetCandidate candidate = new MediaAssetCandidate(nextAssetId, nextCreatedAt);

        when(mediaAssetRepositoryPort.findActiveByClientTagKeyset(
                CLIENT_TAG,
                UPPER_BOUND,
                T0,
                ASSET_ID,
                20
        )).thenReturn(List.of(candidate));

        List<MediaAssetCandidate> result = useCase.execute(query);

        assertThat(result).containsExactly(candidate);
        verify(mediaAssetRepositoryPort).findActiveByClientTagKeyset(
                CLIENT_TAG,
                UPPER_BOUND,
                T0,
                ASSET_ID,
                20
        );
    }

    @Test
    @DisplayName("fails fast on null query")
    void shouldFailFastOnNullQuery() {
        assertThatThrownBy(() -> useCase.execute(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("FindActiveMediaAssetsKeysetQuery cannot be null.");

        verifyNoInteractions(mediaAssetRepositoryPort);
    }

    @Nested
    @DisplayName("Application Query Boundary Validation")
    class ApplicationQueryValidation {

        @Test
        @DisplayName("null clientTag is rejected")
        void shouldRejectNullClientTag() {
            assertThatThrownBy(() -> new FindActiveMediaAssetsKeysetQuery(
                    null,
                    UPPER_BOUND,
                    null,
                    null,
                    20
            )).isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("Client tag cannot be null");
        }

        @Test
        @DisplayName("blank clientTag is rejected")
        void shouldRejectBlankClientTag() {
            assertThatThrownBy(() -> new FindActiveMediaAssetsKeysetQuery(
                    "   ",
                    UPPER_BOUND,
                    null,
                    null,
                    20
            )).isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Client tag cannot be blank");
        }

        @Test
        @DisplayName("clientTag exceeding 64 chars is rejected")
        void shouldRejectLongClientTag() {
            assertThatThrownBy(() -> new FindActiveMediaAssetsKeysetQuery(
                    "a".repeat(65),
                    UPPER_BOUND,
                    null,
                    null,
                    20
            )).isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Client tag cannot exceed 64 characters");
        }

        @Test
        @DisplayName("null upper bound is rejected")
        void shouldRejectNullUpperBound() {
            assertThatThrownBy(() -> new FindActiveMediaAssetsKeysetQuery(
                    CLIENT_TAG,
                    null,
                    null,
                    null,
                    20
            )).isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("Created before upper bound cannot be null");
        }

        @Test
        @DisplayName("pageSize <= 0 is rejected")
        void shouldRejectZeroOrNegativePageSize() {
            assertThatThrownBy(() -> new FindActiveMediaAssetsKeysetQuery(
                    CLIENT_TAG,
                    UPPER_BOUND,
                    null,
                    null,
                    0
            )).isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Page size must be greater than zero");

            assertThatThrownBy(() -> new FindActiveMediaAssetsKeysetQuery(
                    CLIENT_TAG,
                    UPPER_BOUND,
                    null,
                    null,
                    -1
            )).isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Page size must be greater than zero");
        }

        @Test
        @DisplayName("pageSize exceeding MAX_PAGE_SIZE (100) is rejected")
        void shouldRejectPageSizeExceedingMax() {
            assertThatThrownBy(() -> new FindActiveMediaAssetsKeysetQuery(
                    CLIENT_TAG,
                    UPPER_BOUND,
                    null,
                    null,
                    101
            )).isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Page size cannot exceed 100");
        }

        @Test
        @DisplayName("partial cursor: null createdAt with non-null assetId is rejected")
        void shouldRejectNullCreatedAtWithNonNullAssetId() {
            assertThatThrownBy(() -> new FindActiveMediaAssetsKeysetQuery(
                    CLIENT_TAG,
                    UPPER_BOUND,
                    null,
                    ASSET_ID,
                    20
            )).isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Cursor requires both lastCreatedAt and lastAssetId to be present, or both to be null");
        }

        @Test
        @DisplayName("partial cursor: non-null createdAt with null assetId is rejected")
        void shouldRejectNonNullCreatedAtWithNullAssetId() {
            assertThatThrownBy(() -> new FindActiveMediaAssetsKeysetQuery(
                    CLIENT_TAG,
                    UPPER_BOUND,
                    T0,
                    null,
                    20
            )).isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Cursor requires both lastCreatedAt and lastAssetId to be present, or both to be null");
        }

        @Test
        @DisplayName("valid cursor states: first page (both null) and subsequent page (both set)")
        void shouldAcceptValidCursorStates() {
            FindActiveMediaAssetsKeysetQuery first = FindActiveMediaAssetsKeysetQuery.firstPage(CLIENT_TAG, UPPER_BOUND, 20);
            assertThat(first.lastCreatedAt()).isNull();
            assertThat(first.lastAssetId()).isNull();

            FindActiveMediaAssetsKeysetQuery next = FindActiveMediaAssetsKeysetQuery.nextPage(CLIENT_TAG, UPPER_BOUND, T0, ASSET_ID, 20);
            assertThat(next.lastCreatedAt()).isEqualTo(T0);
            assertThat(next.lastAssetId()).isEqualTo(ASSET_ID);
        }
    }
}
