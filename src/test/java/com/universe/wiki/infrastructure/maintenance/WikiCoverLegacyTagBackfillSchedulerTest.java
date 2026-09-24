package com.universe.wiki.infrastructure.maintenance;

import com.universe.wiki.application.article.cover.backfill.WikiCoverLegacyTagBackfillResult;
import com.universe.wiki.application.article.cover.backfill.WikiCoverLegacyTagBackfillService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("Wiki Cover Legacy Tag Backfill Scheduler Tests")
class WikiCoverLegacyTagBackfillSchedulerTest {

    @Mock
    private WikiCoverLegacyTagBackfillService backfillService;

    private WikiCoverLegacyTagBackfillScheduler scheduler;

    @BeforeEach
    void setUp() {
        scheduler = new WikiCoverLegacyTagBackfillScheduler(backfillService);
    }

    @Test
    @DisplayName("Uỷ quyền trực tiếp sang WikiCoverLegacyTagBackfillService khi chạy scheduled")
    void shouldDelegateDirectlyToService() {
        when(backfillService.backfillLegacyCoverTags())
                .thenReturn(new WikiCoverLegacyTagBackfillResult(5, 5, 0, 0, 0));

        scheduler.runBackfill();

        verify(backfillService).backfillLegacyCoverTags();
    }

    @Test
    @DisplayName("Bắt và ghi log ngoại lệ bất ngờ mà không làm vỡ scheduler")
    void shouldIsolateUnexpectedException() {
        when(backfillService.backfillLegacyCoverTags())
                .thenThrow(new RuntimeException("Database connectivity interrupted"));

        // Must not rethrow
        scheduler.runBackfill();

        verify(backfillService).backfillLegacyCoverTags();
    }
}
