package com.universe.wiki.infrastructure.maintenance;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Validated configuration properties for Wiki cover orphan discovery and reconciliation (MS-05G8C5).
 */
@Component
public class WikiCoverOrphanProperties {

    public static final int MIN_PAGE_SIZE = 1;
    public static final int MAX_PAGE_SIZE = 100;
    public static final long MIN_ORPHAN_GRACE_DAYS = 30L;

    private final boolean enabled;
    private final String discoveryCron;
    private final String reconciliationCron;
    private final String zone;
    private final Duration discoverySafetyWindow;
    private final int discoveryPageSize;
    private final Duration orphanGrace;
    private final Duration processingLeaseDuration;
    private final int reconciliationBatchSize;

    public WikiCoverOrphanProperties(
            @Value("${wiki.cover-orphan.schedule.enabled:false}") boolean enabled,
            @Value("${wiki.cover-orphan.schedule.discovery-cron:0 0 2 * * *}") String discoveryCron,
            @Value("${wiki.cover-orphan.schedule.reconciliation-cron:0 0 3 * * *}") String reconciliationCron,
            @Value("${wiki.cover-orphan.schedule.zone:Asia/Ho_Chi_Minh}") String zone,
            @Value("${wiki.cover-orphan.discovery-safety-window-seconds:300}") long discoverySafetyWindowSeconds,
            @Value("${wiki.cover-orphan.discovery-page-size:100}") int discoveryPageSize,
            @Value("${wiki.cover-orphan.orphan-grace-days:30}") long orphanGraceDays,
            @Value("${wiki.cover-orphan.processing-lease-duration-seconds:300}") long processingLeaseDurationSeconds,
            @Value("${wiki.cover-orphan.reconciliation-batch-size:50}") int reconciliationBatchSize
    ) {
        if (discoveryPageSize < MIN_PAGE_SIZE || discoveryPageSize > MAX_PAGE_SIZE) {
            throw new IllegalArgumentException(
                    "wiki.cover-orphan.discovery-page-size must be between " + MIN_PAGE_SIZE + " and " + MAX_PAGE_SIZE + ", but found: " + discoveryPageSize
            );
        }
        if (orphanGraceDays < MIN_ORPHAN_GRACE_DAYS) {
            throw new IllegalArgumentException(
                    "wiki.cover-orphan.orphan-grace-days must be at least " + MIN_ORPHAN_GRACE_DAYS + " days, but found: " + orphanGraceDays
            );
        }
        if (discoverySafetyWindowSeconds < 0) {
            throw new IllegalArgumentException(
                    "wiki.cover-orphan.discovery-safety-window-seconds must be non-negative, but found: " + discoverySafetyWindowSeconds
            );
        }
        if (processingLeaseDurationSeconds <= 0) {
            throw new IllegalArgumentException(
                    "wiki.cover-orphan.processing-lease-duration-seconds must be greater than 0, but found: " + processingLeaseDurationSeconds
            );
        }
        if (reconciliationBatchSize <= 0) {
            throw new IllegalArgumentException(
                    "wiki.cover-orphan.reconciliation-batch-size must be greater than 0, but found: " + reconciliationBatchSize
            );
        }

        this.enabled = enabled;
        this.discoveryCron = discoveryCron != null ? discoveryCron.trim() : "0 0 2 * * *";
        this.reconciliationCron = reconciliationCron != null ? reconciliationCron.trim() : "0 0 3 * * *";
        this.zone = zone != null && !zone.isBlank() ? zone.trim() : "Asia/Ho_Chi_Minh";
        this.discoverySafetyWindow = Duration.ofSeconds(discoverySafetyWindowSeconds);
        this.discoveryPageSize = discoveryPageSize;
        this.orphanGrace = Duration.ofDays(orphanGraceDays);
        this.processingLeaseDuration = Duration.ofSeconds(processingLeaseDurationSeconds);
        this.reconciliationBatchSize = reconciliationBatchSize;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public String getDiscoveryCron() {
        return discoveryCron;
    }

    public String getReconciliationCron() {
        return reconciliationCron;
    }

    public String getZone() {
        return zone;
    }

    public Duration getDiscoverySafetyWindow() {
        return discoverySafetyWindow;
    }

    public int getDiscoveryPageSize() {
        return discoveryPageSize;
    }

    public Duration getOrphanGrace() {
        return orphanGrace;
    }

    public Duration getProcessingLeaseDuration() {
        return processingLeaseDuration;
    }

    public int getReconciliationBatchSize() {
        return reconciliationBatchSize;
    }
}
