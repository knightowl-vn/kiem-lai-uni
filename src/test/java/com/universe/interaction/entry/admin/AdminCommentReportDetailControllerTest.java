package com.universe.interaction.entry.admin;

import com.universe.interaction.application.exceptions.InteractionReportNotFoundException;
import com.universe.interaction.domain.CommentStatus;
import com.universe.interaction.domain.CommentTargetType;
import com.universe.interaction.domain.report.ReportReason;
import com.universe.interaction.domain.report.ReportStatus;
import com.universe.interaction.entry.admin.dto.AdminCommentReportDetailDTO;
import com.universe.interaction.entry.admin.dto.AdminCommentReportTargetDTO;
import com.universe.interaction.entry.admin.dto.AdminCommentReportUserDTO;
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
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AdminCommentReportDetailControllerTest {

    @Mock
    private AdminCommentReportDetailCoordinator coordinator;

    private AdminCommentReportDetailController controller;

    private final UUID reportId = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private final UUID commentId = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private final UUID reporterId = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private final UUID authorId = UUID.fromString("44444444-4444-4444-4444-444444444444");
    private final UUID targetId = UUID.fromString("55555555-5555-5555-5555-555555555555");
    private final Instant baseTime = Instant.parse("2026-09-21T10:00:00Z");

    @BeforeEach
    void setUp() {
        controller = new AdminCommentReportDetailController(coordinator);
    }

    @Test
    @DisplayName("Constructor enforces non-null coordinator")
    void constructorEnforcesNonNullCoordinator() {
        assertThatThrownBy(() -> new AdminCommentReportDetailController(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("AdminCommentReportDetailCoordinator cannot be null");
    }

    @Test
    @DisplayName("Case A: Success - coordinator called once, view name, model attributes, and no-cache headers verified")
    void shouldRenderDetailSuccessfully() {
        ExtendedModelMap model = new ExtendedModelMap();
        MockHttpServletResponse response = new MockHttpServletResponse();
        RedirectAttributesModelMap redirectAttributes = new RedirectAttributesModelMap();

        AdminCommentReportDetailDTO mockDto = createSampleDetailDTO();
        when(coordinator.getDetail(reportId)).thenReturn(mockDto);

        String view = controller.reportDetail(reportId, model, response, redirectAttributes);

        // 1. View name verification
        assertThat(view).isEqualTo("admin/comments/report-detail");

        // 2. Coordinator invocation verification
        verify(coordinator, times(1)).getDetail(reportId);

        // 3. Model attribute verification
        assertThat(model.get("report")).isEqualTo(mockDto);
        assertThat(model.get("pageTitle")).isEqualTo("Chi tiết báo cáo bình luận");
        assertThat(model.get("activeMenu")).isEqualTo("comment-reports");

        // 4. HTTP no-cache headers verification
        assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store, no-cache, must-revalidate, max-age=0");
        assertThat(response.getHeader("Pragma")).isEqualTo("no-cache");
        assertThat(response.getDateHeader("Expires")).isEqualTo(0L);

        // 5. No redirect attributes on success
        assertThat(redirectAttributes.getFlashAttributes()).isEmpty();
    }

    @Test
    @DisplayName("Case B: Report not found - catches InteractionReportNotFoundException, adds flash errorMessage, redirects to /admin/comments/reports")
    void shouldRedirectToQueueWhenReportNotFound() {
        ExtendedModelMap model = new ExtendedModelMap();
        MockHttpServletResponse response = new MockHttpServletResponse();
        RedirectAttributesModelMap redirectAttributes = new RedirectAttributesModelMap();

        when(coordinator.getDetail(reportId)).thenThrow(new InteractionReportNotFoundException(reportId));

        String view = controller.reportDetail(reportId, model, response, redirectAttributes);

        // 1. Redirect view
        assertThat(view).isEqualTo("redirect:/admin/comments/reports");

        // 2. Exactly one coordinator call
        verify(coordinator, times(1)).getDetail(reportId);

        // 3. Flash errorMessage attribute
        assertThat(redirectAttributes.getFlashAttributes().get("errorMessage"))
                .isEqualTo("Không tìm thấy báo cáo: " + reportId);

        // 4. No report in model
        assertThat(model.get("report")).isNull();

        // 5. No-cache headers still set
        assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store, no-cache, must-revalidate, max-age=0");
        assertThat(response.getHeader("Pragma")).isEqualTo("no-cache");
        assertThat(response.getDateHeader("Expires")).isEqualTo(0L);
    }

    @Test
    @DisplayName("Case C: Unrelated runtime error propagates without being swallowed")
    void shouldPropagateUnrelatedRuntimeException() {
        ExtendedModelMap model = new ExtendedModelMap();
        MockHttpServletResponse response = new MockHttpServletResponse();
        RedirectAttributesModelMap redirectAttributes = new RedirectAttributesModelMap();

        when(coordinator.getDetail(reportId)).thenThrow(new IllegalStateException("Database connection failed"));

        assertThatThrownBy(() -> controller.reportDetail(reportId, model, response, redirectAttributes))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Database connection failed");

        verify(coordinator, times(1)).getDetail(reportId);
    }

    private AdminCommentReportDetailDTO createSampleDetailDTO() {
        return new AdminCommentReportDetailDTO(
                reportId,
                commentId,
                ReportReason.SPAM,
                "Mô tả báo cáo",
                "Snapshot evidence",
                ReportStatus.PENDING,
                baseTime,
                AdminCommentReportUserDTO.resolved(reporterId, "Alice Reporter", "https://img/alice.png"),
                null,
                null,
                null,
                true,
                "Live comment body",
                CommentStatus.ACTIVE,
                baseTime.minusSeconds(100),
                baseTime.minusSeconds(100),
                null,
                AdminCommentReportUserDTO.resolved(authorId, "Bob Author", null),
                AdminCommentReportTargetDTO.unresolved(CommentTargetType.NOVEL_CHAPTER, targetId)
        );
    }
}
