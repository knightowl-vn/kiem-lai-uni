package com.universe.wiki.infrastructure.maintenance;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("Wiki Cover Orphan Configuration Properties Tests")
class WikiCoverOrphanPropertiesTest {

    @Test
    @DisplayName("Khởi tạo thành công với các giá trị hợp lệ")
    void shouldInitializeWithValidValues() {
        WikiCoverOrphanProperties props = new WikiCoverOrphanProperties(
                true,
                "0 0 2 * * *",
                "0 0 3 * * *",
                "Asia/Ho_Chi_Minh",
                600L,
                50,
                35L,
                180L,
                25
        );

        assertThat(props.isEnabled()).isTrue();
        assertThat(props.getDiscoveryCron()).isEqualTo("0 0 2 * * *");
        assertThat(props.getReconciliationCron()).isEqualTo("0 0 3 * * *");
        assertThat(props.getZone()).isEqualTo("Asia/Ho_Chi_Minh");
        assertThat(props.getDiscoverySafetyWindow()).isEqualTo(Duration.ofSeconds(600));
        assertThat(props.getDiscoveryPageSize()).isEqualTo(50);
        assertThat(props.getOrphanGrace()).isEqualTo(Duration.ofDays(35));
        assertThat(props.getProcessingLeaseDuration()).isEqualTo(Duration.ofSeconds(180));
        assertThat(props.getReconciliationBatchSize()).isEqualTo(25);
    }

    @Test
    @DisplayName("Khởi tạo thành công với các giá trị mặc định")
    void shouldInitializeWithDefaults() {
        WikiCoverOrphanProperties props = new WikiCoverOrphanProperties(
                false,
                null,
                null,
                null,
                300L,
                100,
                30L,
                300L,
                50
        );

        assertThat(props.isEnabled()).isFalse();
        assertThat(props.getDiscoveryCron()).isEqualTo("0 0 2 * * *");
        assertThat(props.getReconciliationCron()).isEqualTo("0 0 3 * * *");
        assertThat(props.getZone()).isEqualTo("Asia/Ho_Chi_Minh");
        assertThat(props.getDiscoverySafetyWindow()).isEqualTo(Duration.ofSeconds(300));
        assertThat(props.getDiscoveryPageSize()).isEqualTo(100);
        assertThat(props.getOrphanGrace()).isEqualTo(Duration.ofDays(30));
        assertThat(props.getProcessingLeaseDuration()).isEqualTo(Duration.ofSeconds(300));
        assertThat(props.getReconciliationBatchSize()).isEqualTo(50);
    }

    @Test
    @DisplayName("Từ chối orphanGrace < 30 ngày theo hợp đồng bắt buộc")
    void shouldRejectOrphanGraceLessThan30Days() {
        assertThatThrownBy(() -> new WikiCoverOrphanProperties(
                true, "0 0 2 * * *", "0 0 3 * * *", "Asia/Ho_Chi_Minh",
                300L, 100, 29L, 300L, 50
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("orphan-grace-days must be at least 30 days");
    }

    @Test
    @DisplayName("Từ chối discoveryPageSize < 1")
    void shouldRejectDiscoveryPageSizeLessThan1() {
        assertThatThrownBy(() -> new WikiCoverOrphanProperties(
                true, "0 0 2 * * *", "0 0 3 * * *", "Asia/Ho_Chi_Minh",
                300L, 0, 30L, 300L, 50
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("discovery-page-size must be between 1 and 100");
    }

    @Test
    @DisplayName("Từ chối discoveryPageSize > 100")
    void shouldRejectDiscoveryPageSizeGreaterThan100() {
        assertThatThrownBy(() -> new WikiCoverOrphanProperties(
                true, "0 0 2 * * *", "0 0 3 * * *", "Asia/Ho_Chi_Minh",
                300L, 101, 30L, 300L, 50
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("discovery-page-size must be between 1 and 100");
    }

    @Test
    @DisplayName("Từ chối discoverySafetyWindowSeconds < 0")
    void shouldRejectNegativeDiscoverySafetyWindow() {
        assertThatThrownBy(() -> new WikiCoverOrphanProperties(
                true, "0 0 2 * * *", "0 0 3 * * *", "Asia/Ho_Chi_Minh",
                -1L, 100, 30L, 300L, 50
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("discovery-safety-window-seconds must be non-negative");
    }

    @Test
    @DisplayName("Từ chối processingLeaseDurationSeconds <= 0")
    void shouldRejectNonPositiveProcessingLeaseDuration() {
        assertThatThrownBy(() -> new WikiCoverOrphanProperties(
                true, "0 0 2 * * *", "0 0 3 * * *", "Asia/Ho_Chi_Minh",
                300L, 100, 30L, 0L, 50
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("processing-lease-duration-seconds must be greater than 0");
    }

    @Test
    @DisplayName("Từ chối reconciliationBatchSize <= 0")
    void shouldRejectNonPositiveReconciliationBatchSize() {
        assertThatThrownBy(() -> new WikiCoverOrphanProperties(
                true, "0 0 2 * * *", "0 0 3 * * *", "Asia/Ho_Chi_Minh",
                300L, 100, 30L, 300L, 0
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("reconciliation-batch-size must be greater than 0");
    }
}
