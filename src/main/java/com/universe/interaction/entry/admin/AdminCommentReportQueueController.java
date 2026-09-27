package com.universe.interaction.entry.admin;

import com.universe.interaction.application.query.InteractionReportQueueFilter;
import com.universe.interaction.application.query.InteractionReportQueueSort;
import com.universe.interaction.application.query.ReportQueueLifecycleScope;
import com.universe.interaction.domain.CommentTargetType;
import com.universe.interaction.domain.report.ReportReason;
import com.universe.interaction.entry.admin.dto.AdminCommentReportQueuePageDTO;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.Locale;
import java.util.Objects;

/**
 * Admin controller for rendering the comment report queue management page.
 *
 * <p>Handles HTTP parameter normalization, delegates query composition to
 * {@link AdminCommentReportQueueCoordinator}, enforces HTTP no-cache directives,
 * and populates the model contract for the Thymeleaf view.
 */
@Controller
public class AdminCommentReportQueueController {

    private static final String VIEW_NAME = "admin/comments/reports";
    private static final String PAGE_TITLE = "Quản lý báo cáo bình luận";
    private static final String ACTIVE_MENU = "comment-reports";
    private static final int MAX_PAGE_SIZE = 50;

    private final AdminCommentReportQueueCoordinator coordinator;

    public AdminCommentReportQueueController(AdminCommentReportQueueCoordinator coordinator) {
        this.coordinator = Objects.requireNonNull(
                coordinator,
                "AdminCommentReportQueueCoordinator cannot be null"
        );
    }

    @GetMapping("/admin/comments/reports")
    public String reportQueue(
            @RequestParam(required = false) String scope,
            @RequestParam(required = false) String reason,
            @RequestParam(required = false) String targetType,
            @RequestParam(required = false) String sort,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            Model model,
            HttpServletResponse response
    ) {
        if (response != null) {
            disableCaching(response);
        }

        int safeSize = size > 0 ? Math.min(size, MAX_PAGE_SIZE) : size;

        ReportQueueLifecycleScope normalizedScope = normalizeScope(scope);
        ReportReason normalizedReason = normalizeReason(reason);
        CommentTargetType normalizedTargetType = normalizeTargetType(targetType);
        InteractionReportQueueSort normalizedSort = normalizeSort(sort);

        InteractionReportQueueFilter filter = new InteractionReportQueueFilter(
                normalizedScope,
                normalizedReason,
                normalizedTargetType,
                normalizedSort,
                page,
                safeSize
        );

        AdminCommentReportQueuePageDTO reportPage = coordinator.getReportQueue(filter);

        model.addAttribute("reportPage", reportPage);
        model.addAttribute("reports", reportPage.items());
        model.addAttribute("pageTitle", PAGE_TITLE);
        model.addAttribute("activeMenu", ACTIVE_MENU);

        model.addAttribute("selectedScope", normalizedScope);
        model.addAttribute("selectedReason", normalizedReason);
        model.addAttribute("selectedTargetType", normalizedTargetType);
        model.addAttribute("selectedSort", normalizedSort);
        model.addAttribute("pageSize", safeSize);
        model.addAttribute("page", page);

        model.addAttribute("scopes", ReportQueueLifecycleScope.values());
        model.addAttribute("reasons", ReportReason.values());
        model.addAttribute("targetTypes", CommentTargetType.values());
        model.addAttribute("sorts", InteractionReportQueueSort.values());

        return VIEW_NAME;
    }

    private ReportQueueLifecycleScope normalizeScope(String scope) {
        if (scope == null) {
            return ReportQueueLifecycleScope.PENDING;
        }
        String trimmed = scope.trim();
        if (trimmed.isEmpty()) {
            return ReportQueueLifecycleScope.PENDING;
        }
        try {
            return ReportQueueLifecycleScope.valueOf(trimmed.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            return ReportQueueLifecycleScope.PENDING;
        }
    }

    private InteractionReportQueueSort normalizeSort(String sort) {
        if (sort == null || sort.trim().isEmpty()) {
            return InteractionReportQueueSort.OLDEST;
        }
        String trimmed = sort.trim();
        try {
            return InteractionReportQueueSort.valueOf(trimmed.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("Thứ tự sắp xếp không hợp lệ: " + sort, ex);
        }
    }

    private ReportReason normalizeReason(String reason) {
        if (reason == null) {
            return null;
        }
        String trimmed = reason.trim();
        if (trimmed.isEmpty() || "ALL".equalsIgnoreCase(trimmed)) {
            return null;
        }
        try {
            return ReportReason.valueOf(trimmed.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("Lý do báo cáo không hợp lệ: " + reason, ex);
        }
    }

    private CommentTargetType normalizeTargetType(String targetType) {
        if (targetType == null) {
            return null;
        }
        String trimmed = targetType.trim();
        if (trimmed.isEmpty() || "ALL".equalsIgnoreCase(trimmed)) {
            return null;
        }
        try {
            return CommentTargetType.valueOf(trimmed.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("Loại mục tiêu không hợp lệ: " + targetType, ex);
        }
    }

    private void disableCaching(HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store, no-cache, must-revalidate, max-age=0");
        response.setHeader("Pragma", "no-cache");
        response.setDateHeader("Expires", 0L);
    }
}
