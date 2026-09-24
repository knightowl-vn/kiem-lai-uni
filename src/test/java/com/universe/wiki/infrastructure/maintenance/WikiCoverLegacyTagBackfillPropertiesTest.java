package com.universe.wiki.infrastructure.maintenance;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("Wiki Cover Legacy Tag Backfill Configuration Properties Tests")
class WikiCoverLegacyTagBackfillPropertiesTest {

    @Test
    @DisplayName("Khởi tạo thành công với các giá trị hợp lệ")
    void shouldInitializeWithValidValues() {
        WikiCoverLegacyTagBackfillProperties props = new WikiCoverLegacyTagBackfillProperties(
                true,
                "0 0 1 * * *",
                "Asia/Ho_Chi_Minh",
                50
        );

        assertThat(props.isEnabled()).isTrue();
        assertThat(props.getCron()).isEqualTo("0 0 1 * * *");
        assertThat(props.getZone()).isEqualTo("Asia/Ho_Chi_Minh");
        assertThat(props.getPageSize()).isEqualTo(50);
    }

    @Test
    @DisplayName("Khởi tạo thành công với các giá trị mặc định")
    void shouldInitializeWithDefaults() {
        WikiCoverLegacyTagBackfillProperties props = new WikiCoverLegacyTagBackfillProperties(
                false,
                null,
                null,
                100
        );

        assertThat(props.isEnabled()).isFalse();
        assertThat(props.getCron()).isEqualTo("0 0 1 * * *");
        assertThat(props.getZone()).isEqualTo("Asia/Ho_Chi_Minh");
        assertThat(props.getPageSize()).isEqualTo(100);
    }

    @Test
    @DisplayName("Từ chối pageSize < 1")
    void shouldRejectPageSizeLessThan1() {
        assertThatThrownBy(() -> new WikiCoverLegacyTagBackfillProperties(
                true, "0 0 1 * * *", "Asia/Ho_Chi_Minh", 0
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("page-size must be between 1 and 100");
    }

    @Test
    @DisplayName("Từ chối pageSize > 100")
    void shouldRejectPageSizeGreaterThan100() {
        assertThatThrownBy(() -> new WikiCoverLegacyTagBackfillProperties(
                true, "0 0 1 * * *", "Asia/Ho_Chi_Minh", 101
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("page-size must be between 1 and 100");
    }
}
