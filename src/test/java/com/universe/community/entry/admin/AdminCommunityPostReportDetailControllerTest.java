package com.universe.community.entry.admin;

import com.universe.community.domain.CommunityPostStatus;
import com.universe.community.entry.admin.dto.AdminCommunityPostReportDetailDTO;
import com.universe.community.entry.admin.dto.AdminCommunityPostUserDTO;
import com.universe.interaction.application.exceptions.InteractionReportNotFoundException;
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
import org.springframework.web.servlet.mvc.support.RedirectAttributesModelMap;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("AdminCommunityPostReportDetailController Tests")
class AdminCommunityPostReportDetailControllerTest {

    @Mock
    private AdminCommunityPostReportCoordinator coordinator;

    private AdminCommunityPostReportDetailController controller;

    private final UUID reportId = UUID.randomUUID();
    private final UUID postId = UUID.randomUUID();
    private final Instant now = Instant.parse("2026-10-03T12:00:00Z");

    @BeforeEach
    void setUp() {
        controller = new AdminCommunityPostReportDetailController(coordinator);
    }

    @Test
    @DisplayName("Renders report detail view when report exists")
    void shouldRenderReportDetail() {
        ExtendedModelMap model = new ExtendedModelMap();
        MockHttpServletResponse response = new MockHttpServletResponse();
        RedirectAttributesModelMap redirectAttributes = new RedirectAttributesModelMap();

        AdminCommunityPostReportDetailDTO mockDetail = new AdminCommunityPostReportDetailDTO(
                reportId, AdminCommunityPostUserDTO.unresolved(UUID.randomUUID()),
                ReportReason.SPAM, "Description", "Snapshot", null, now,
                ReportStatus.PENDING, postId, true, CommunityPostStatus.PUBLISHED,
                "Current caption", null, AdminCommunityPostUserDTO.unresolved(UUID.randomUUID()),
                now, now, 1, null, null, null, List.of()
        );
        when(coordinator.getReportDetail(reportId)).thenReturn(mockDetail);

        String view = controller.reportDetail(reportId, model, response, redirectAttributes);

        assertThat(view).isEqualTo("admin/community/report-detail");
        assertThat(model.get("report")).isEqualTo(mockDetail);
        assertThat(model.get("pageTitle")).isEqualTo("Chi tiết báo cáo bài viết");
        assertThat(model.get("activeMenu")).isEqualTo("community-reports");
        assertThat(response.getHeader("Cache-Control")).contains("no-cache");
    }

    @Test
    @DisplayName("Redirects to report queue with flash error when report not found")
    void shouldRedirectWhenReportNotFound() {
        ExtendedModelMap model = new ExtendedModelMap();
        MockHttpServletResponse response = new MockHttpServletResponse();
        RedirectAttributesModelMap redirectAttributes = new RedirectAttributesModelMap();

        when(coordinator.getReportDetail(reportId)).thenThrow(new InteractionReportNotFoundException(reportId));

        String view = controller.reportDetail(reportId, model, response, redirectAttributes);

        assertThat(view).isEqualTo("redirect:/admin/community/reports");
        assertThat(redirectAttributes.getFlashAttributes()).containsKey("errorMessage");
    }

    @Test
    @DisplayName("Navigates to public post context when post exists and is PUBLISHED")
    void shouldNavigateToPublicPostWhenPublished() {
        MockHttpServletResponse response = new MockHttpServletResponse();
        RedirectAttributesModelMap redirectAttributes = new RedirectAttributesModelMap();

        AdminCommunityPostReportDetailDTO mockDetail = new AdminCommunityPostReportDetailDTO(
                reportId, AdminCommunityPostUserDTO.unresolved(UUID.randomUUID()),
                ReportReason.SPAM, "Description", "Snapshot", null, now,
                ReportStatus.PENDING, postId, true, CommunityPostStatus.PUBLISHED,
                "Current caption", null, AdminCommunityPostUserDTO.unresolved(UUID.randomUUID()),
                now, now, 1, null, null, null, List.of()
        );
        when(coordinator.getReportDetail(reportId)).thenReturn(mockDetail);

        String destination = controller.navigateToContext(reportId, response, redirectAttributes);

        assertThat(destination).isEqualTo("redirect:/community/posts/" + postId);
    }

    @Test
    @DisplayName("Redirects back to detail with flash error when post is not PUBLISHED")
    void shouldRedirectBackWhenPostNotPublished() {
        MockHttpServletResponse response = new MockHttpServletResponse();
        RedirectAttributesModelMap redirectAttributes = new RedirectAttributesModelMap();

        AdminCommunityPostReportDetailDTO mockDetail = new AdminCommunityPostReportDetailDTO(
                reportId, AdminCommunityPostUserDTO.unresolved(UUID.randomUUID()),
                ReportReason.SPAM, "Description", "Snapshot", null, now,
                ReportStatus.PENDING, postId, true, CommunityPostStatus.HIDDEN,
                "Current caption", null, AdminCommunityPostUserDTO.unresolved(UUID.randomUUID()),
                now, now, 1, null, null, null, List.of()
        );
        when(coordinator.getReportDetail(reportId)).thenReturn(mockDetail);

        String destination = controller.navigateToContext(reportId, response, redirectAttributes);

        assertThat(destination).isEqualTo("redirect:/admin/community/reports/" + reportId);
        assertThat(redirectAttributes.getFlashAttributes().get("errorMessage"))
                .isEqualTo("Bài viết hiện không khả dụng để xem công khai.");
    }
}
