package com.universe.wiki.application.article.cover.backfill;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("Wiki Referenced Cover Keyset Query Tests")
class WikiReferencedCoverKeysetQueryTest {

    private static final String VALID_UPPER_BOUND = "ffffffff-ffff-ffff-ffff-ffffffffffff";
    private static final String VALID_LAST_ASSET_ID = "11111111-1111-1111-1111-111111111111";

    @Test
    @DisplayName("Khởi tạo firstPage thành công với cursor null")
    void shouldCreateFirstPageSuccessfully() {
        WikiReferencedCoverKeysetQuery query = WikiReferencedCoverKeysetQuery.firstPage(VALID_UPPER_BOUND, 50);

        assertThat(query.lastAssetId()).isNull();
        assertThat(query.runUpperBound()).isEqualTo(VALID_UPPER_BOUND);
        assertThat(query.pageSize()).isEqualTo(50);
    }

    @Test
    @DisplayName("Khởi tạo nextPage thành công với cursor non-null")
    void shouldCreateNextPageSuccessfully() {
        WikiReferencedCoverKeysetQuery query = WikiReferencedCoverKeysetQuery.nextPage(VALID_LAST_ASSET_ID, VALID_UPPER_BOUND, 20);

        assertThat(query.lastAssetId()).isEqualTo(VALID_LAST_ASSET_ID);
        assertThat(query.runUpperBound()).isEqualTo(VALID_UPPER_BOUND);
        assertThat(query.pageSize()).isEqualTo(20);
    }

    @Test
    @DisplayName("Từ chối runUpperBound null hoặc blank")
    void shouldRejectInvalidRunUpperBound() {
        assertThatThrownBy(() -> WikiReferencedCoverKeysetQuery.firstPage(null, 50))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("runUpperBound cannot be null");

        assertThatThrownBy(() -> WikiReferencedCoverKeysetQuery.firstPage("   ", 50))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("runUpperBound cannot be blank");
    }

    @Test
    @DisplayName("Từ chối pageSize <= 0 hoặc > 100")
    void shouldRejectInvalidPageSize() {
        assertThatThrownBy(() -> WikiReferencedCoverKeysetQuery.firstPage(VALID_UPPER_BOUND, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("pageSize must be between 1 and 100");

        assertThatThrownBy(() -> WikiReferencedCoverKeysetQuery.firstPage(VALID_UPPER_BOUND, -5))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("pageSize must be between 1 and 100");

        assertThatThrownBy(() -> WikiReferencedCoverKeysetQuery.firstPage(VALID_UPPER_BOUND, 101))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("pageSize must be between 1 and 100");
    }

    @Test
    @DisplayName("Từ chối lastAssetId blank khi khởi tạo")
    void shouldRejectBlankLastAssetId() {
        assertThatThrownBy(() -> new WikiReferencedCoverKeysetQuery("  ", VALID_UPPER_BOUND, 50))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("lastAssetId cannot be blank when provided");
    }

    @Test
    @DisplayName("Từ chối nextPage với lastAssetId null")
    void shouldRejectNullLastAssetIdInNextPage() {
        assertThatThrownBy(() -> WikiReferencedCoverKeysetQuery.nextPage(null, VALID_UPPER_BOUND, 50))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("lastAssetId cannot be null for subsequent page");
    }
}
