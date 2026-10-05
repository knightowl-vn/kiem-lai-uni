package com.universe.community.entry.admin;

import com.universe.community.domain.exception.CommunityPostNotFoundException;
import com.universe.identity.application.security.AuthenticatedRequestIdentity;
import com.universe.identity.infrastructure.security.AuthenticatedRequestIdentityAccessor;
import com.universe.interaction.application.exceptions.InteractionReportNotFoundException;
import com.universe.interaction.application.exceptions.ReportAlreadyResolvedException;
import com.universe.interaction.domain.report.ReportModerationAction;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.Objects;
import java.util.UUID;

/**
 * Dedicated Admin HTTP mutation controller for resolving Community post reports.
 */
@Controller
public class AdminCommunityPostReportModerationController {

    public static final String FLASH_SUCCESS_HIDE = "Đã ẩn bài viết và xử lý báo cáo.";
    public static final String FLASH_SUCCESS_NO_ACTION = "Đã đóng báo cáo mà không thực hiện hành động.";
    public static final String FLASH_ERROR_ALREADY_RESOLVED = "Báo cáo đã được xử lý trước đó.";
    public static final String FLASH_ERROR_POST_NOT_FOUND = "Bài viết hiện không còn tồn tại.";
    public static final String FLASH_ERROR_INVALID_ACTION = "Hành động xử lý báo cáo không hợp lệ.";

    private final ResolveCommunityPostReportUseCase resolveCommunityPostReportUseCase;

    public AdminCommunityPostReportModerationController(ResolveCommunityPostReportUseCase resolveCommunityPostReportUseCase) {
        this.resolveCommunityPostReportUseCase = Objects.requireNonNull(
                resolveCommunityPostReportUseCase,
                "ResolveCommunityPostReportUseCase cannot be null."
        );
    }

    @PostMapping("/admin/community/reports/{reportId}/resolve")
    public String resolveReport(
            @PathVariable UUID reportId,
            @RequestParam(name = "action", required = false) String actionRaw,
            @RequestParam(name = "reason", required = false) String reason,
            HttpServletRequest request,
            RedirectAttributes redirectAttributes
    ) {
        UUID moderatorUserId = resolveModeratorUserId(request);

        ReportModerationAction action = parseModerationAction(actionRaw);
        if (action == null) {
            redirectAttributes.addFlashAttribute("errorMessage", FLASH_ERROR_INVALID_ACTION);
            return "redirect:/admin/community/reports/" + reportId;
        }

        try {
            ResolveCommunityPostReportCommand command = new ResolveCommunityPostReportCommand(
                    reportId,
                    moderatorUserId,
                    action,
                    reason
            );
            resolveCommunityPostReportUseCase.execute(command);

            if (action == ReportModerationAction.CONTENT_HIDDEN) {
                redirectAttributes.addFlashAttribute("successMessage", FLASH_SUCCESS_HIDE);
            } else if (action == ReportModerationAction.NO_ACTION) {
                redirectAttributes.addFlashAttribute("successMessage", FLASH_SUCCESS_NO_ACTION);
            }

            return "redirect:/admin/community/reports/" + reportId;

        } catch (InteractionReportNotFoundException ex) {
            redirectAttributes.addFlashAttribute("errorMessage", "Không tìm thấy báo cáo: " + reportId);
            return "redirect:/admin/community/reports";

        } catch (ReportAlreadyResolvedException ex) {
            redirectAttributes.addFlashAttribute("errorMessage", FLASH_ERROR_ALREADY_RESOLVED);
            return "redirect:/admin/community/reports/" + reportId;

        } catch (CommunityPostNotFoundException ex) {
            redirectAttributes.addFlashAttribute("errorMessage", FLASH_ERROR_POST_NOT_FOUND);
            return "redirect:/admin/community/reports/" + reportId;

        } catch (IllegalStateException ex) {
            redirectAttributes.addFlashAttribute("errorMessage", ex.getMessage());
            return "redirect:/admin/community/reports/" + reportId;
        }
    }

    private ReportModerationAction parseModerationAction(String actionRaw) {
        if (actionRaw == null || actionRaw.isBlank()) {
            return null;
        }
        return switch (actionRaw.trim()) {
            case "CONTENT_HIDDEN" -> ReportModerationAction.CONTENT_HIDDEN;
            case "NO_ACTION" -> ReportModerationAction.NO_ACTION;
            default -> null;
        };
    }

    private UUID resolveModeratorUserId(HttpServletRequest request) {
        return AuthenticatedRequestIdentityAccessor.find(request)
                .map(AuthenticatedRequestIdentity::userId)
                .orElseThrow(() -> new AccessDeniedException("Yêu cầu thông tin định danh quản trị viên hợp lệ."));
    }
}
