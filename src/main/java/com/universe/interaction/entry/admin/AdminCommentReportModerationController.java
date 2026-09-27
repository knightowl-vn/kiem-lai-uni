package com.universe.interaction.entry.admin;

import com.universe.identity.application.security.AuthenticatedRequestIdentity;
import com.universe.identity.infrastructure.security.AuthenticatedRequestIdentityAccessor;
import com.universe.interaction.application.exceptions.CommentNotFoundException;
import com.universe.interaction.application.exceptions.InteractionReportNotFoundException;
import com.universe.interaction.application.exceptions.ReportAlreadyResolvedException;
import com.universe.interaction.application.mutation.ResolveCommentReportCommand;
import com.universe.interaction.application.mutation.ResolveCommentReportUseCase;
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
 * Dedicated Admin HTTP mutation controller for resolving comment reports with moderation actions.
 *
 * <p>Responsibilities:
 * <ul>
 *   <li>Resolves authenticated moderator identity via {@link AuthenticatedRequestIdentityAccessor};</li>
 *   <li>Parses and validates moderation action string ({@link ReportModerationAction#DELETE_COMMENT}, {@link ReportModerationAction#NO_ACTION});</li>
 *   <li>Constructs {@link ResolveCommentReportCommand} and delegates to {@link ResolveCommentReportUseCase};</li>
 *   <li>Redirects back to report detail or report queue with flash feedback;</li>
 *   <li>Contains zero direct repository dependencies;</li>
 *   <li>Propagates unexpected runtime exceptions without broad suppression.</li>
 * </ul>
 */
@Controller
public class AdminCommentReportModerationController {

    public static final String FLASH_SUCCESS_DELETE = "Đã xóa bình luận và xử lý báo cáo.";
    public static final String FLASH_SUCCESS_NO_ACTION = "Đã đóng báo cáo mà không thực hiện hành động.";
    public static final String FLASH_ERROR_ALREADY_RESOLVED = "Báo cáo đã được xử lý trước đó.";
    public static final String FLASH_ERROR_COMMENT_NOT_FOUND = "Bình luận hiện không còn khả dụng.";
    public static final String FLASH_ERROR_INVALID_ACTION = "Hành động xử lý báo cáo không hợp lệ.";

    private final ResolveCommentReportUseCase resolveCommentReportUseCase;

    public AdminCommentReportModerationController(ResolveCommentReportUseCase resolveCommentReportUseCase) {
        this.resolveCommentReportUseCase = Objects.requireNonNull(
                resolveCommentReportUseCase,
                "ResolveCommentReportUseCase cannot be null"
        );
    }

    /**
     * Resolves an interaction comment report with a moderation action.
     *
     * @param reportId the UUID of the report to resolve
     * @param actionRaw the raw string representation of the moderation action
     * @param request the current HTTP servlet request containing authenticated identity
     * @param redirectAttributes flash attributes for post-redirect feedback
     * @return redirect view name
     */
    @PostMapping("/admin/comments/reports/{reportId}/resolve")
    public String resolveReport(
            @PathVariable UUID reportId,
            @RequestParam(name = "action", required = false) String actionRaw,
            HttpServletRequest request,
            RedirectAttributes redirectAttributes
    ) {
        UUID moderatorUserId = resolveModeratorUserId(request);

        ReportModerationAction action = parseModerationAction(actionRaw);
        if (action == null) {
            redirectAttributes.addFlashAttribute("errorMessage", FLASH_ERROR_INVALID_ACTION);
            return "redirect:/admin/comments/reports/" + reportId;
        }

        try {
            ResolveCommentReportCommand command = new ResolveCommentReportCommand(
                    reportId,
                    moderatorUserId,
                    action
            );
            resolveCommentReportUseCase.execute(command);

            if (action == ReportModerationAction.DELETE_COMMENT) {
                redirectAttributes.addFlashAttribute("successMessage", FLASH_SUCCESS_DELETE);
            } else if (action == ReportModerationAction.NO_ACTION) {
                redirectAttributes.addFlashAttribute("successMessage", FLASH_SUCCESS_NO_ACTION);
            }

            return "redirect:/admin/comments/reports/" + reportId;

        } catch (InteractionReportNotFoundException ex) {
            redirectAttributes.addFlashAttribute("errorMessage", "Không tìm thấy báo cáo: " + reportId);
            return "redirect:/admin/comments/reports";

        } catch (ReportAlreadyResolvedException ex) {
            redirectAttributes.addFlashAttribute("errorMessage", FLASH_ERROR_ALREADY_RESOLVED);
            return "redirect:/admin/comments/reports/" + reportId;

        } catch (CommentNotFoundException ex) {
            redirectAttributes.addFlashAttribute("errorMessage", FLASH_ERROR_COMMENT_NOT_FOUND);
            return "redirect:/admin/comments/reports/" + reportId;
        }
    }

    private ReportModerationAction parseModerationAction(String actionRaw) {
        if (actionRaw == null || actionRaw.isBlank()) {
            return null;
        }
        return switch (actionRaw) {
            case "DELETE_COMMENT" -> ReportModerationAction.DELETE_COMMENT;
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
