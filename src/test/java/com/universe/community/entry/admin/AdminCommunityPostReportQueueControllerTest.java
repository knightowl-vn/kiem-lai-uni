package com.universe.community.entry.admin;

import com.universe.community.entry.admin.dto.AdminCommunityPostReportPageDTO;
import com.universe.interaction.domain.report.ReportReason;
import com.universe.interaction.domain.report.ReportStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.ui.ExtendedModelMap;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("AdminCommunityPostReportQueueController Tests")
class AdminCommunityPostReportQueueControllerTest {

    @Mock
    private AdminCommunityPostReportCoordinator coordinator;

    private AdminCommunityPostReportQueueController controller;

    @BeforeEach
    void setUp() {
        controller = new AdminCommunityPostReportQueueController(coordinator);
    }

    @Test
    @DisplayName("Renders report queue with defaults (PENDING scope, NEWEST sort, page 0, size 20) and no-cache headers")
    void shouldRenderQueueWithDefaults() {
        ExtendedModelMap model = new ExtendedModelMap();
        MockHttpServletResponse response = new MockHttpServletResponse();

        AdminCommunityPostReportPageDTO mockPage = new AdminCommunityPostReportPageDTO(List.of(), 0, 20, 0L);
        when(coordinator.getReportQueue(eq(ReportStatus.PENDING), eq(null), eq(false), eq(0), eq(20)))
                .thenReturn(mockPage);

        String view = controller.reportQueue(null, null, "NEWEST", 0, 20, model, response);

        assertThat(view).isEqualTo("admin/community/reports");
        assertThat(model.get("reportPage")).isEqualTo(mockPage);
        assertThat(model.get("pageTitle")).isEqualTo("Quản lý cộng đồng");
        assertThat(model.get("pageSubtitle")).isEqualTo("Theo dõi, xét duyệt và xử lý nội dung cộng đồng.");
        assertThat(model.get("activeMenu")).isEqualTo("community-reports");
        assertThat(model.get("selectedScope")).isEqualTo("PENDING");
        assertThat(response.getHeader("Cache-Control")).contains("no-cache");
    }

    @Test
    @DisplayName("Caps size at max page size (50) and normalizes filters")
    void shouldCapPageSizeAndNormalizeFilters() {
        ExtendedModelMap model = new ExtendedModelMap();
        MockHttpServletResponse response = new MockHttpServletResponse();

        AdminCommunityPostReportPageDTO mockPage = new AdminCommunityPostReportPageDTO(List.of(), 1, 50, 100L);
        when(coordinator.getReportQueue(eq(ReportStatus.RESOLVED_ACTION_TAKEN), eq(ReportReason.HARASSMENT), eq(true), eq(1), eq(50)))
                .thenReturn(mockPage);

        String view = controller.reportQueue("RESOLVED_ACTION_TAKEN", "HARASSMENT", "OLDEST", 1, 999, model, response);

        assertThat(view).isEqualTo("admin/community/reports");
        assertThat(model.get("pageSize")).isEqualTo(50);
        assertThat(model.get("selectedSort")).isEqualTo("OLDEST");
    }
}
