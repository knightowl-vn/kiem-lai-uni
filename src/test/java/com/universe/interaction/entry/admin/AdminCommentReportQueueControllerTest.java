package com.universe.interaction.entry.admin;

import com.universe.interaction.application.query.InteractionReportQueueFilter;
import com.universe.interaction.application.query.InteractionReportQueueSort;
import com.universe.interaction.application.query.ReportQueueLifecycleScope;
import com.universe.interaction.domain.CommentTargetType;
import com.universe.interaction.domain.report.ReportReason;
import com.universe.interaction.entry.admin.dto.AdminCommentReportQueuePageDTO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.ui.ExtendedModelMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AdminCommentReportQueueControllerTest {

    @Mock
    private AdminCommentReportQueueCoordinator coordinator;

    private AdminCommentReportQueueController controller;

    @BeforeEach
    void setUp() {
        controller = new AdminCommentReportQueueController(coordinator);
    }

    @Test
    @DisplayName("Constructor enforces non-null coordinator")
    void constructorEnforcesNonNullCoordinator() {
        assertThatThrownBy(() -> new AdminCommentReportQueueController(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("AdminCommentReportQueueCoordinator cannot be null");
    }

    @Test
    @DisplayName("Case A: Default request uses PENDING, OLDEST, page 0, size 20, sets no-cache headers and model attributes")
    void shouldHandleDefaultRequest() {
        ExtendedModelMap model = new ExtendedModelMap();
        MockHttpServletResponse response = new MockHttpServletResponse();

        AdminCommentReportQueuePageDTO mockPage = AdminCommentReportQueuePageDTO.empty(0, 20);
        when(coordinator.getReportQueue(any())).thenReturn(mockPage);

        String view = controller.reportQueue(
                null,
                null,
                null,
                null,
                0,
                20,
                model,
                response
        );

        // 1. View name verification
        assertThat(view).isEqualTo("admin/comments/reports");

        // 2. Coordinator invocation & filter verification
        ArgumentCaptor<InteractionReportQueueFilter> filterCaptor =
                ArgumentCaptor.forClass(InteractionReportQueueFilter.class);
        verify(coordinator).getReportQueue(filterCaptor.capture());
        InteractionReportQueueFilter capturedFilter = filterCaptor.getValue();

        assertThat(capturedFilter.scope()).isEqualTo(ReportQueueLifecycleScope.PENDING);
        assertThat(capturedFilter.reason()).isNull();
        assertThat(capturedFilter.targetType()).isNull();
        assertThat(capturedFilter.sort()).isEqualTo(InteractionReportQueueSort.OLDEST);
        assertThat(capturedFilter.page()).isEqualTo(0);
        assertThat(capturedFilter.size()).isEqualTo(20);

        // 3. Model contract verification
        assertThat(model.getAttribute("reportPage")).isSameAs(mockPage);
        assertThat(model.getAttribute("reports")).isEqualTo(mockPage.items());
        assertThat(model.getAttribute("pageTitle")).isEqualTo("Quản lý báo cáo bình luận");
        assertThat(model.getAttribute("activeMenu")).isEqualTo("comment-reports");
        assertThat(model.getAttribute("selectedScope")).isEqualTo(ReportQueueLifecycleScope.PENDING);
        assertThat(model.getAttribute("selectedReason")).isNull();
        assertThat(model.getAttribute("selectedTargetType")).isNull();
        assertThat(model.getAttribute("selectedSort")).isEqualTo(InteractionReportQueueSort.OLDEST);
        assertThat(model.getAttribute("pageSize")).isEqualTo(20);
        assertThat(model.getAttribute("page")).isEqualTo(0);

        assertThat(model.getAttribute("scopes")).isEqualTo(ReportQueueLifecycleScope.values());
        assertThat(model.getAttribute("reasons")).isEqualTo(ReportReason.values());
        assertThat(model.getAttribute("targetTypes")).isEqualTo(CommentTargetType.values());
        assertThat(model.getAttribute("sorts")).isEqualTo(InteractionReportQueueSort.values());

        // 4. No-cache headers verification
        assertThat(response.getHeader("Cache-Control"))
                .isEqualTo("no-store, no-cache, must-revalidate, max-age=0");
        assertThat(response.getHeader("Pragma"))
                .isEqualTo("no-cache");
        assertThat(response.getDateHeader("Expires"))
                .isEqualTo(0L);
    }

    @Test
    @DisplayName("Case B: Explicit filters normalize whitespace and case-insensitivity, preserving normalized values")
    void shouldHandleExplicitFiltersWithCaseAndWhitespaceNormalization() {
        ExtendedModelMap model = new ExtendedModelMap();
        MockHttpServletResponse response = new MockHttpServletResponse();

        AdminCommentReportQueuePageDTO mockPage = AdminCommentReportQueuePageDTO.empty(3, 50);
        when(coordinator.getReportQueue(any())).thenReturn(mockPage);

        String view = controller.reportQueue(
                "  processed  ",
                "  spam  ",
                "  novel_chapter  ",
                "  newest  ",
                3,
                50,
                model,
                response
        );

        assertThat(view).isEqualTo("admin/comments/reports");

        ArgumentCaptor<InteractionReportQueueFilter> filterCaptor =
                ArgumentCaptor.forClass(InteractionReportQueueFilter.class);
        verify(coordinator).getReportQueue(filterCaptor.capture());
        InteractionReportQueueFilter filter = filterCaptor.getValue();

        assertThat(filter.scope()).isEqualTo(ReportQueueLifecycleScope.PROCESSED);
        assertThat(filter.reason()).isEqualTo(ReportReason.SPAM);
        assertThat(filter.targetType()).isEqualTo(CommentTargetType.NOVEL_CHAPTER);
        assertThat(filter.sort()).isEqualTo(InteractionReportQueueSort.NEWEST);
        assertThat(filter.page()).isEqualTo(3);
        assertThat(filter.size()).isEqualTo(50);

        assertThat(model.getAttribute("selectedScope")).isEqualTo(ReportQueueLifecycleScope.PROCESSED);
        assertThat(model.getAttribute("selectedReason")).isEqualTo(ReportReason.SPAM);
        assertThat(model.getAttribute("selectedTargetType")).isEqualTo(CommentTargetType.NOVEL_CHAPTER);
        assertThat(model.getAttribute("selectedSort")).isEqualTo(InteractionReportQueueSort.NEWEST);
        assertThat(model.getAttribute("pageSize")).isEqualTo(50);
        assertThat(model.getAttribute("page")).isEqualTo(3);
    }

    @Test
    @DisplayName("Case C: ALL and blank optional filters normalize to null")
    void shouldNormalizeAllAndBlankFiltersToNull() {
        ExtendedModelMap model = new ExtendedModelMap();
        MockHttpServletResponse response = new MockHttpServletResponse();

        AdminCommentReportQueuePageDTO mockPage = AdminCommentReportQueuePageDTO.empty(0, 20);
        when(coordinator.getReportQueue(any())).thenReturn(mockPage);

        controller.reportQueue(
                "PENDING",
                "all",
                "   ",
                "",
                0,
                20,
                model,
                response
        );

        ArgumentCaptor<InteractionReportQueueFilter> filterCaptor =
                ArgumentCaptor.forClass(InteractionReportQueueFilter.class);
        verify(coordinator).getReportQueue(filterCaptor.capture());
        InteractionReportQueueFilter filter = filterCaptor.getValue();

        assertThat(filter.scope()).isEqualTo(ReportQueueLifecycleScope.PENDING);
        // reason="all" becomes null
        assertThat(filter.reason()).isNull();
        // targetType="   " becomes null
        assertThat(filter.targetType()).isNull();
        // sort="" falls back to OLDEST
        assertThat(filter.sort()).isEqualTo(InteractionReportQueueSort.OLDEST);

        assertThat(model.getAttribute("selectedScope")).isEqualTo(ReportQueueLifecycleScope.PENDING);
        assertThat(model.getAttribute("selectedReason")).isNull();
        assertThat(model.getAttribute("selectedTargetType")).isNull();
        assertThat(model.getAttribute("selectedSort")).isEqualTo(InteractionReportQueueSort.OLDEST);
    }

    @Test
    @DisplayName("Case C (variation): Blank scope normalizes safely to PENDING")
    void shouldNormalizeBlankScopeToPending() {
        ExtendedModelMap model = new ExtendedModelMap();
        MockHttpServletResponse response = new MockHttpServletResponse();

        AdminCommentReportQueuePageDTO mockPage = AdminCommentReportQueuePageDTO.empty(0, 20);
        when(coordinator.getReportQueue(any())).thenReturn(mockPage);

        controller.reportQueue(
                "   ",
                null,
                null,
                null,
                0,
                20,
                model,
                response
        );

        ArgumentCaptor<InteractionReportQueueFilter> filterCaptor =
                ArgumentCaptor.forClass(InteractionReportQueueFilter.class);
        verify(coordinator).getReportQueue(filterCaptor.capture());
        assertThat(filterCaptor.getValue().scope()).isEqualTo(ReportQueueLifecycleScope.PENDING);
        assertThat(model.getAttribute("selectedScope")).isEqualTo(ReportQueueLifecycleScope.PENDING);
    }

    @Test
    @DisplayName("Case D: Invalid scope safely falls back to PENDING without throwing or crashing")
    void shouldFallbackToPendingOnInvalidScope() {
        ExtendedModelMap model = new ExtendedModelMap();
        MockHttpServletResponse response = new MockHttpServletResponse();

        AdminCommentReportQueuePageDTO mockPage = AdminCommentReportQueuePageDTO.empty(0, 20);
        when(coordinator.getReportQueue(any())).thenReturn(mockPage);

        controller.reportQueue(
                "INVALID_SCOPE",
                null,
                null,
                null,
                0,
                20,
                model,
                response
        );

        ArgumentCaptor<InteractionReportQueueFilter> filterCaptor =
                ArgumentCaptor.forClass(InteractionReportQueueFilter.class);
        verify(coordinator).getReportQueue(filterCaptor.capture());
        assertThat(filterCaptor.getValue().scope()).isEqualTo(ReportQueueLifecycleScope.PENDING);
        assertThat(model.getAttribute("selectedScope")).isEqualTo(ReportQueueLifecycleScope.PENDING);
    }

    @Test
    @DisplayName("Case D: Invented scope RESOLVED safely falls back to PENDING")
    void shouldFallbackToPendingOnInventedScopeResolved() {
        ExtendedModelMap model = new ExtendedModelMap();
        MockHttpServletResponse response = new MockHttpServletResponse();

        AdminCommentReportQueuePageDTO mockPage = AdminCommentReportQueuePageDTO.empty(0, 20);
        when(coordinator.getReportQueue(any())).thenReturn(mockPage);

        controller.reportQueue(
                "RESOLVED",
                null,
                null,
                null,
                0,
                20,
                model,
                response
        );

        ArgumentCaptor<InteractionReportQueueFilter> filterCaptor =
                ArgumentCaptor.forClass(InteractionReportQueueFilter.class);
        verify(coordinator).getReportQueue(filterCaptor.capture());
        assertThat(filterCaptor.getValue().scope()).isEqualTo(ReportQueueLifecycleScope.PENDING);
        assertThat(model.getAttribute("selectedScope")).isEqualTo(ReportQueueLifecycleScope.PENDING);
    }

    @Test
    @DisplayName("Case D: Invalid sort throws IllegalArgumentException and never calls coordinator")
    void shouldThrowOnInvalidSort() {
        ExtendedModelMap model = new ExtendedModelMap();
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertThatThrownBy(() -> controller.reportQueue(
                null,
                null,
                null,
                "OLDEST_FIRST",
                0,
                20,
                model,
                response
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Thứ tự sắp xếp không hợp lệ: OLDEST_FIRST");

        verifyNoInteractions(coordinator);
    }

    @Test
    @DisplayName("Case D: Invalid reason throws IllegalArgumentException and never calls coordinator")
    void shouldThrowOnInvalidReason() {
        ExtendedModelMap model = new ExtendedModelMap();
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertThatThrownBy(() -> controller.reportQueue(
                null,
                "INAPPROPRIATE",
                null,
                null,
                0,
                20,
                model,
                response
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Lý do báo cáo không hợp lệ: INAPPROPRIATE");

        verifyNoInteractions(coordinator);
    }

    @Test
    @DisplayName("Case D: Invalid targetType throws IllegalArgumentException and never calls coordinator")
    void shouldThrowOnInvalidTargetType() {
        ExtendedModelMap model = new ExtendedModelMap();
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertThatThrownBy(() -> controller.reportQueue(
                null,
                null,
                "MANHUA",
                null,
                0,
                20,
                model,
                response
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Loại mục tiêu không hợp lệ: MANHUA");

        verifyNoInteractions(coordinator);
    }

    @Test
    @DisplayName("Case E: Pagination parameters are forwarded zero-based without one-based conversion")
    void shouldPreserveZeroBasedPaginationWithoutOffset() {
        ExtendedModelMap model = new ExtendedModelMap();
        MockHttpServletResponse response = new MockHttpServletResponse();

        AdminCommentReportQueuePageDTO mockPage = AdminCommentReportQueuePageDTO.empty(5, 15);
        when(coordinator.getReportQueue(any())).thenReturn(mockPage);

        controller.reportQueue(
                null,
                null,
                null,
                null,
                5,
                15,
                model,
                response
        );

        ArgumentCaptor<InteractionReportQueueFilter> filterCaptor =
                ArgumentCaptor.forClass(InteractionReportQueueFilter.class);
        verify(coordinator).getReportQueue(filterCaptor.capture());
        InteractionReportQueueFilter filter = filterCaptor.getValue();

        assertThat(filter.page()).isEqualTo(5);
        assertThat(filter.size()).isEqualTo(15);
        assertThat(model.getAttribute("page")).isEqualTo(5);
        assertThat(model.getAttribute("pageSize")).isEqualTo(15);
    }
}
