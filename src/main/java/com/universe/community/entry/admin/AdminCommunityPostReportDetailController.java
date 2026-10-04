package com.universe.community.entry.admin;

import com.universe.community.entry.admin.dto.AdminCommunityPostReportDetailDTO;
import com.universe.interaction.application.exceptions.InteractionReportNotFoundException;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.Objects;
import java.util.UUID;

/**
 * Admin controller for Community Post report moderation detail and public post navigation.
 */
@Controller
public class AdminCommunityPostReportDetailController {

    private static final String VIEW_NAME = "admin/community/report-detail";
    private static final String PAGE_TITLE = "Chi tiết báo cáo bài viết";
    private static final String ACTIVE_MENU = "community-reports";
    private static final String ERROR_CONTEXT_UNAVAILABLE = "Bài viết hiện không khả dụng để xem công khai.";

    private final AdminCommunityPostReportCoordinator coordinator;

    public AdminCommunityPostReportDetailController(AdminCommunityPostReportCoordinator coordinator) {
        this.coordinator = Objects.requireNonNull(
                coordinator,
                "AdminCommunityPostReportCoordinator cannot be null."
        );
    }

    @GetMapping("/admin/community/reports/{reportId}")
    public String reportDetail(
            @PathVariable UUID reportId,
            Model model,
            HttpServletResponse response,
            RedirectAttributes redirectAttributes
    ) {
        if (response != null) {
            disableCaching(response);
        }

        try {
            AdminCommunityPostReportDetailDTO report = coordinator.getReportDetail(reportId);

            model.addAttribute("report", report);
            model.addAttribute("pageTitle", PAGE_TITLE);
            model.addAttribute("activeMenu", ACTIVE_MENU);

            return VIEW_NAME;
        } catch (InteractionReportNotFoundException ex) {
            redirectAttributes.addFlashAttribute(
                    "errorMessage",
                    "Không tìm thấy báo cáo: " + reportId
            );
            return "redirect:/admin/community/reports";
        }
    }

    @GetMapping("/admin/community/reports/{reportId}/context")
    public String navigateToContext(
            @PathVariable UUID reportId,
            HttpServletResponse response,
            RedirectAttributes redirectAttributes
    ) {
        if (response != null) {
            disableCaching(response);
        }

        try {
            AdminCommunityPostReportDetailDTO report = coordinator.getReportDetail(reportId);

            if (!report.postExists() || report.postStatus() == null || !report.postStatus().isPublished()) {
                redirectAttributes.addFlashAttribute("errorMessage", ERROR_CONTEXT_UNAVAILABLE);
                return "redirect:/admin/community/reports/" + reportId;
            }

            return "redirect:/community/posts/" + report.targetPostId();

        } catch (InteractionReportNotFoundException ex) {
            redirectAttributes.addFlashAttribute(
                    "errorMessage",
                    "Không tìm thấy báo cáo: " + reportId
            );
            return "redirect:/admin/community/reports";
        }
    }

    private void disableCaching(HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store, no-cache, must-revalidate, max-age=0");
        response.setHeader("Pragma", "no-cache");
        response.setDateHeader("Expires", 0L);
    }
}
