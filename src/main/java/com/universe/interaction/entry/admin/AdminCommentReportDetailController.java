package com.universe.interaction.entry.admin;

import com.universe.interaction.application.exceptions.InteractionReportNotFoundException;
import com.universe.interaction.entry.admin.dto.AdminCommentReportDetailDTO;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.Objects;
import java.util.UUID;

/**
 * Admin controller for rendering the read-only comment report detail page.
 *
 * <p>Handles path variable binding, delegates composition to {@link AdminCommentReportDetailCoordinator},
 * applies HTTP no-cache directives, and enforces the standard human-navigation redirect on missing reports.
 */
@Controller
public class AdminCommentReportDetailController {

    private static final String VIEW_NAME = "admin/comments/report-detail";
    private static final String PAGE_TITLE = "Chi tiết báo cáo bình luận";
    private static final String ACTIVE_MENU = "comment-reports";

    private final AdminCommentReportDetailCoordinator coordinator;

    public AdminCommentReportDetailController(AdminCommentReportDetailCoordinator coordinator) {
        this.coordinator = Objects.requireNonNull(
                coordinator,
                "AdminCommentReportDetailCoordinator cannot be null"
        );
    }

    @GetMapping("/admin/comments/reports/{reportId}")
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
            AdminCommentReportDetailDTO report = coordinator.getDetail(reportId);

            model.addAttribute("report", report);
            model.addAttribute("pageTitle", PAGE_TITLE);
            model.addAttribute("activeMenu", ACTIVE_MENU);

            return VIEW_NAME;
        } catch (InteractionReportNotFoundException ex) {
            redirectAttributes.addFlashAttribute(
                    "errorMessage",
                    "Không tìm thấy báo cáo: " + reportId
            );
            return "redirect:/admin/comments/reports";
        }
    }

    private void disableCaching(HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store, no-cache, must-revalidate, max-age=0");
        response.setHeader("Pragma", "no-cache");
        response.setDateHeader("Expires", 0L);
    }
}
