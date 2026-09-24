package com.universe.wiki.infrastructure.maintenance;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Validated configuration properties for Wiki cover legacy client-tag backfill (MS-05G8C6).
 */
@Component
public class WikiCoverLegacyTagBackfillProperties {

    public static final int MIN_PAGE_SIZE = 1;
    public static final int MAX_PAGE_SIZE = 100;

    private final boolean enabled;
    private final String cron;
    private final String zone;
    private final int pageSize;

    public WikiCoverLegacyTagBackfillProperties(
            @Value("${wiki.cover-orphan.backfill.enabled:false}") boolean enabled,
            @Value("${wiki.cover-orphan.backfill.cron:0 0 1 * * *}") String cron,
            @Value("${wiki.cover-orphan.backfill.zone:Asia/Ho_Chi_Minh}") String zone,
            @Value("${wiki.cover-orphan.backfill.page-size:100}") int pageSize
    ) {
        if (pageSize < MIN_PAGE_SIZE || pageSize > MAX_PAGE_SIZE) {
            throw new IllegalArgumentException(
                    "wiki.cover-orphan.backfill.page-size must be between " + MIN_PAGE_SIZE + " and " + MAX_PAGE_SIZE + ", but found: " + pageSize
            );
        }

        this.enabled = enabled;
        this.cron = cron != null ? cron.trim() : "0 0 1 * * *";
        this.zone = zone != null && !zone.isBlank() ? zone.trim() : "Asia/Ho_Chi_Minh";
        this.pageSize = pageSize;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public String getCron() {
        return cron;
    }

    public String getZone() {
        return zone;
    }

    public int getPageSize() {
        return pageSize;
    }
}
