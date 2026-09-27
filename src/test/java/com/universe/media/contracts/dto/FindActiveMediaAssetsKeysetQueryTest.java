package com.universe.media.contracts.dto;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FindActiveMediaAssetsKeysetQueryTest {

    private static final String VALID_TAG = "wiki.article.cover";
    private static final Instant UPPER_BOUND = Instant.parse("2026-09-01T12:00:00Z");
    private static final Instant T0 = Instant.parse("2026-09-01T10:00:00Z");
    private static final UUID ASSET_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");

    @Nested
    @DisplayName("Page Size Validation")
    class PageSizeValidation {

        @Test
        @DisplayName("pageSize = 1 is accepted")
        void shouldAcceptPageSizeOfOne() {
            FindActiveMediaAssetsKeysetQuery query = new FindActiveMediaAssetsKeysetQuery(
                    VALID_TAG,
                    UPPER_BOUND,
                    null,
                    null,
                    1
            );
            assertThat(query.pageSize()).isEqualTo(1);
        }

        @Test
        @DisplayName("ordinary pageSize (20) is accepted")
        void shouldAcceptOrdinaryPageSize() {
            FindActiveMediaAssetsKeysetQuery query = new FindActiveMediaAssetsKeysetQuery(
                    VALID_TAG,
                    UPPER_BOUND,
                    null,
                    null,
                    20
            );
            assertThat(query.pageSize()).isEqualTo(20);
        }

        @Test
        @DisplayName("pageSize equal to MAX_PAGE_SIZE (100) is accepted")
        void shouldAcceptMaxPageSize() {
            FindActiveMediaAssetsKeysetQuery query = new FindActiveMediaAssetsKeysetQuery(
                    VALID_TAG,
                    UPPER_BOUND,
                    null,
                    null,
                    FindActiveMediaAssetsKeysetQuery.MAX_PAGE_SIZE
            );
            assertThat(query.pageSize()).isEqualTo(100);
        }

        @Test
        @DisplayName("contract MAX_PAGE_SIZE matches application MAX_PAGE_SIZE")
        void shouldMatchApplicationMaxPageSize() {
            assertThat(FindActiveMediaAssetsKeysetQuery.MAX_PAGE_SIZE)
                    .isEqualTo(com.universe.media.application.asset.FindActiveMediaAssetsKeysetQuery.MAX_PAGE_SIZE)
                    .isEqualTo(100);
        }

        @Test
        @DisplayName("pageSize = 0 is rejected")
        void shouldRejectZeroPageSize() {
            assertThatThrownBy(() -> new FindActiveMediaAssetsKeysetQuery(
                    VALID_TAG,
                    UPPER_BOUND,
                    null,
                    null,
                    0
            )).isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Page size must be greater than zero");
        }

        @Test
        @DisplayName("negative pageSize is rejected")
        void shouldRejectNegativePageSize() {
            assertThatThrownBy(() -> new FindActiveMediaAssetsKeysetQuery(
                    VALID_TAG,
                    UPPER_BOUND,
                    null,
                    null,
                    -5
            )).isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Page size must be greater than zero");
        }

        @Test
        @DisplayName("pageSize above MAX_PAGE_SIZE (101) is rejected")
        void shouldRejectPageSizeExceedingMax() {
            assertThatThrownBy(() -> new FindActiveMediaAssetsKeysetQuery(
                    VALID_TAG,
                    UPPER_BOUND,
                    null,
                    null,
                    101
            )).isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Page size cannot exceed 100");
        }
    }

    @Nested
    @DisplayName("Client Tag Validation")
    class ClientTagValidation {

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
        @DisplayName("empty clientTag is rejected")
        void shouldRejectEmptyClientTag() {
            assertThatThrownBy(() -> new FindActiveMediaAssetsKeysetQuery(
                    "",
                    UPPER_BOUND,
                    null,
                    null,
                    20
            )).isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Client tag cannot be blank");
        }

        @Test
        @DisplayName("clientTag of length 64 is accepted")
        void shouldAccept64CharClientTag() {
            String tag64 = "a".repeat(64);
            FindActiveMediaAssetsKeysetQuery query = new FindActiveMediaAssetsKeysetQuery(
                    tag64,
                    UPPER_BOUND,
                    null,
                    null,
                    20
            );
            assertThat(query.clientTag()).isEqualTo(tag64);
        }

        @Test
        @DisplayName("clientTag exceeding 64 chars is rejected")
        void shouldRejectClientTagExceeding64Chars() {
            String tag65 = "a".repeat(65);
            assertThatThrownBy(() -> new FindActiveMediaAssetsKeysetQuery(
                    tag65,
                    UPPER_BOUND,
                    null,
                    null,
                    20
            )).isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Client tag cannot exceed 64 characters");
        }
    }

    @Nested
    @DisplayName("Upper Bound Validation")
    class UpperBoundValidation {

        @Test
        @DisplayName("null createdBeforeUpperBound is rejected")
        void shouldRejectNullUpperBound() {
            assertThatThrownBy(() -> new FindActiveMediaAssetsKeysetQuery(
                    VALID_TAG,
                    null,
                    null,
                    null,
                    20
            )).isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("Created before upper bound cannot be null");
        }
    }

    @Nested
    @DisplayName("Cursor Invariant Validation")
    class CursorInvariantValidation {

        @Test
        @DisplayName("first page: both lastCreatedAt and lastAssetId null is accepted")
        void shouldAcceptBothCursorFieldsNullForFirstPage() {
            FindActiveMediaAssetsKeysetQuery query = FindActiveMediaAssetsKeysetQuery.firstPage(
                    VALID_TAG,
                    UPPER_BOUND,
                    20
            );
            assertThat(query.lastCreatedAt()).isNull();
            assertThat(query.lastAssetId()).isNull();
        }

        @Test
        @DisplayName("subsequent page: both lastCreatedAt and lastAssetId non-null is accepted")
        void shouldAcceptBothCursorFieldsNonNullForSubsequentPage() {
            FindActiveMediaAssetsKeysetQuery query = FindActiveMediaAssetsKeysetQuery.nextPage(
                    VALID_TAG,
                    UPPER_BOUND,
                    T0,
                    ASSET_ID,
                    20
            );
            assertThat(query.lastCreatedAt()).isEqualTo(T0);
            assertThat(query.lastAssetId()).isEqualTo(ASSET_ID);
        }

        @Test
        @DisplayName("partial cursor: lastCreatedAt null with non-null lastAssetId is rejected")
        void shouldRejectNullCreatedAtWithNonNullAssetId() {
            assertThatThrownBy(() -> new FindActiveMediaAssetsKeysetQuery(
                    VALID_TAG,
                    UPPER_BOUND,
                    null,
                    ASSET_ID,
                    20
            )).isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Cursor requires both lastCreatedAt and lastAssetId to be present, or both to be null");
        }

        @Test
        @DisplayName("partial cursor: non-null lastCreatedAt with null lastAssetId is rejected")
        void shouldRejectNonNullCreatedAtWithNullAssetId() {
            assertThatThrownBy(() -> new FindActiveMediaAssetsKeysetQuery(
                    VALID_TAG,
                    UPPER_BOUND,
                    T0,
                    null,
                    20
            )).isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Cursor requires both lastCreatedAt and lastAssetId to be present, or both to be null");
        }
    }
}
