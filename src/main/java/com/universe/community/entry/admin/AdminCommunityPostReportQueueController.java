package com.universe.community.entry.admin;

import com.universe.community.entry.admin.dto.AdminCommunityPostReportPageDTO;
import com.universe.interaction.domain.report.ReportReason;
import com.universe.interaction.domain.report.ReportStatus;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.Locale;
import java.util.Objects;

/**
 * Admin controller for rendering the Community post report queue management page.
 */
@Controller
public class AdminCommunityPostReportQueueController {

    private static final String VIEW_NAME = "admin/community/reports";
    private static final String PAGE_TITLE = "Quản lý cộng đồng";
    private static final String PAGE_SUBTITLE = "Theo dõi, xét duyệt và xử lý nội dung cộng đồng.";
    private static final String ACTIVE_MENU = "community-reports";
    private static final int MAX_PAGE_SIZE = 50;

    private final AdminCommunityPostReportCoordinator coordinator;

    public AdminCommunityPostReportQueueController(AdminCommunityPostReportCoordinator coordinator) {
        this.coordinator = Objects.requireNonNull(
                coordinator,
                "AdminCommunityPostReportCoordinator cannot be null."
        );
    }

    @GetMapping("/admin/community/reports")
    public String reportQueue(
            @RequestParam(required = false) String scope,
            @RequestParam(required = false) String reason,
            @RequestParam(required = false, defaultValue = "NEWEST") String sort,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            Model model,
            HttpServletResponse response
    ) {
        if (response != null) {
            disableCaching(response);
        }

        int safeSize = size > 0 ? Math.min(size, MAX_PAGE_SIZE) : 20;
        ReportStatus normalizedStatus = normalizeStatus(scope);
        ReportReason normalizedReason = normalizeReason(reason);
        boolean oldestFirst = "OLDEST".equalsIgnoreCase(sort);

        AdminCommunityPostReportPageDTO reportPage = coordinator.getReportQueue(
                normalizedStatus,
                normalizedReason,
                oldestFirst,
                page,
                safeSize
        );

        model.addAttribute("reportPage", reportPage);
        model.addAttribute("reports", reportPage.items());
        model.addAttribute("pageTitle", PAGE_TITLE);
        model.addAttribute("pageSubtitle", PAGE_SUBTITLE);
        model.addAttribute("activeMenu", ACTIVE_MENU);

        model.addAttribute("selectedScope", scope != null ? scope.toUpperCase(Locale.ROOT) : "PENDING");
        model.addAttribute("selectedReason", normalizedReason);
        model.addAttribute("selectedSort", oldestFirst ? "OLDEST" : "NEWEST");
        model.addAttribute("page", page);
        model.addAttribute("pageSize", safeSize);

        model.addAttribute("reasons", ReportReason.values());

        return VIEW_NAME;
    }

    private ReportStatus normalizeStatus(String scope) {
        if (scope == null || scope.isBlank() || "PENDING".equalsIgnoreCase(scope)) {
            return ReportStatus.PENDING;
        }
        if ("ALL".equalsIgnoreCase(scope)) {
            return null;
        }
        try {
            return ReportStatus.valueOf(scope.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            return ReportStatus.PENDING;
        }
    }

    private ReportReason normalizeReason(String reason) {
        if (reason == null || reason.isBlank() || "ALL".equalsIgnoreCase(reason)) {
            return null;
        }
        try {
            return ReportReason.valueOf(reason.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private void disableCaching(HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store, no-cache, must-revalidate, max-age=0");
        response.setHeader("Pragma", "no-cache");
        response.setDateHeader("Expires", 0L);
    }
}
