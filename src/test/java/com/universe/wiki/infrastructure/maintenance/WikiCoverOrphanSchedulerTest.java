package com.universe.wiki.infrastructure.maintenance;

import com.universe.wiki.application.orphan.WikiCoverOrphanDiscoveryResult;
import com.universe.wiki.application.orphan.WikiCoverOrphanDiscoveryService;
import com.universe.wiki.application.orphan.WikiCoverOrphanReconciliationResult;
import com.universe.wiki.application.orphan.WikiCoverOrphanReconciliationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("Wiki Cover Orphan Scheduler Tests")
class WikiCoverOrphanSchedulerTest {

    @Mock
    private WikiCoverOrphanDiscoveryService discoveryService;

    @Mock
    private WikiCoverOrphanReconciliationService reconciliationService;

    private WikiCoverOrphanScheduler scheduler;

    @BeforeEach
    void setUp() {
        scheduler = new WikiCoverOrphanScheduler(discoveryService, reconciliationService);
    }

    @Test
    @DisplayName("Thực thi discovery định kỳ và ghi log kết quả thành công")
    void shouldRunScheduledDiscoveryAndLogSummary() {
        when(discoveryService.discoverOrphans()).thenReturn(
                new WikiCoverOrphanDiscoveryResult(10, 2, 8, 0)
        );

        scheduler.runDiscovery();

        verify(discoveryService).discoverOrphans();
    }

    @Test
    @DisplayName("Bắt và xử lý ngoại lệ bất ngờ khi chạy discovery mà không làm sập tiến trình")
    void shouldCatchAndLogUnexpectedErrorsDuringDiscovery() {
        doThrow(new RuntimeException("Discovery infrastructure failure"))
                .when(discoveryService).discoverOrphans();

        // Must not throw
        scheduler.runDiscovery();

        verify(discoveryService).discoverOrphans();
    }

    @Test
    @DisplayName("Thực thi reconciliation định kỳ và ghi log kết quả thành công")
    void shouldRunScheduledReconciliationAndLogSummary() {
        when(reconciliationService.reconcileOrphans()).thenReturn(
                new WikiCoverOrphanReconciliationResult(1, 2, 2, 0, 0)
        );

        scheduler.runReconciliation();

        verify(reconciliationService).reconcileOrphans();
    }

    @Test
    @DisplayName("Bắt và xử lý ngoại lệ bất ngờ khi chạy reconciliation mà không làm sập tiến trình")
    void shouldCatchAndLogUnexpectedErrorsDuringReconciliation() {
        doThrow(new RuntimeException("Reconciliation infrastructure failure"))
                .when(reconciliationService).reconcileOrphans();

        // Must not throw
        scheduler.runReconciliation();

        verify(reconciliationService).reconcileOrphans();
    }
}
