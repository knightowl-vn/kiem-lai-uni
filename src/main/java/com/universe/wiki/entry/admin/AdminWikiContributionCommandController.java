package com.universe.wiki.entry.admin;

import com.universe.identity.application.security.AuthenticatedRequestIdentity;
import com.universe.identity.infrastructure.security.AuthenticatedRequestIdentityAccessor;
import com.universe.identity.domain.UserRole;
import com.universe.wiki.application.contribution.workflow.AdminWikiContributionWorkflowUseCase;
import com.universe.wiki.application.contribution.workflow.ClaimWikiContributionCommand;
import com.universe.wiki.application.contribution.workflow.ReassignWikiContributionCommand;
import com.universe.wiki.application.contribution.workflow.RejectWikiContributionCommand;
import com.universe.wiki.application.contribution.workflow.ResolveWikiContributionCommand;
import com.universe.wiki.application.contribution.workflow.ReviewWikiContributionCommand;
import com.universe.wiki.application.exceptions.WikiContributionNotFoundException;
import com.universe.wiki.application.exceptions.WikiContributionStaleMutationException;
import com.universe.wiki.domain.contribution.WikiContribution;
import com.universe.wiki.domain.contribution.WikiContributionResolutionOutcome;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.Objects;
import java.util.UUID;

/**
 * Administrative controller for handling Wiki contribution workflow mutations.
 *
 * <p>Protected by Spring Security under {@code /admin/**}, requiring role {@code ADMIN}
 * or {@code SUPER_ADMIN} and valid CSRF token.
 *
 * <p>Guarantees:
 * <ul>
 *   <li>Actor identity is derived strictly from server authentication;</li>
 *   <li>Optimistic concurrency token (expectedVersion) is checked on every mutation;</li>
 *   <li>State transitions are strictly bounded by domain rules;</li>
 *   <li>Post-Redirect-Get (PRG) pattern with flash attributes for human administrator feedback;</li>
 *   <li>Concurrent modifications fail safely into a reviewable state without silent overwrites.</li>
 * </ul>
 */
@Controller
@RequestMapping("/admin/wiki/contributions")
public class AdminWikiContributionCommandController {

    public static final String FLASH_SUCCESS_REVIEW = "Đã chuyển trạng thái đóng góp sang Đang xem xét.";
    public static final String FLASH_SUCCESS_CLAIM = "Đã tiếp nhận phân công xử lý đóng góp này.";
    public static final String FLASH_SUCCESS_REASSIGN = "Đã phân công lại đóng góp thành công.";
    public static final String FLASH_SUCCESS_RESOLVE = "Đã chấp thuận và giải quyết đóng góp.";
    public static final String FLASH_SUCCESS_REJECT = "Đã từ chối đóng góp.";
    public static final String FLASH_ERROR_STALE = "Đóng góp bài viết Wiki đã bị thay đổi đồng thời bởi quản trị viên khác. Vui lòng kiểm tra lại trạng thái mới nhất.";
    public static final String FLASH_ERROR_VERSION_REQUIRED = "Phiên bản đồng thời (expectedVersion) không được để trống.";
    public static final String FLASH_ERROR_NOTE_REQUIRED = "Ghi chú xử lý không được để trống.";
    public static final String FLASH_ERROR_NOTE_BOUNDS = "Độ dài ghi chú xử lý phải từ 5 đến 2000 ký tự.";

    private final AdminWikiContributionWorkflowUseCase workflowUseCase;

    public AdminWikiContributionCommandController(AdminWikiContributionWorkflowUseCase workflowUseCase) {
        this.workflowUseCase = Objects.requireNonNull(workflowUseCase, "AdminWikiContributionWorkflowUseCase cannot be null");
    }

    /**
     * Bắt đầu xem xét đóng góp (NEW -> REVIEWING).
     */
    @PostMapping("/{contributionId}/review")
    public String reviewContribution(
            @PathVariable UUID contributionId,
            @RequestParam(name = "expectedVersion", required = false) Long expectedVersion,
            HttpServletRequest request,
            RedirectAttributes redirectAttributes
    ) {
        if (expectedVersion == null) {
            redirectAttributes.addFlashAttribute("errorMessage", FLASH_ERROR_VERSION_REQUIRED);
            return redirectToDetail(contributionId);
        }

        UUID actorId = resolveAdminUserId(request);

        try {
            workflowUseCase.review(new ReviewWikiContributionCommand(contributionId, actorId, expectedVersion));
            redirectAttributes.addFlashAttribute("successMessage", FLASH_SUCCESS_REVIEW);
            return redirectToDetail(contributionId);
        } catch (WikiContributionNotFoundException ex) {
            redirectAttributes.addFlashAttribute("errorMessage", ex.getMessage());
            return redirectToInbox();
        } catch (WikiContributionStaleMutationException ex) {
            redirectAttributes.addFlashAttribute("errorMessage", FLASH_ERROR_STALE);
            return redirectToDetail(contributionId);
        } catch (IllegalArgumentException | IllegalStateException ex) {
            redirectAttributes.addFlashAttribute("errorMessage", "Thao tác không hợp lệ: " + ex.getMessage());
            return redirectToDetail(contributionId);
        }
    }

    /**
     * Tiếp nhận đóng góp đang xem xét (legacy unassigned claim).
     */
    @PostMapping("/{contributionId}/claim")
    public String claimContribution(
            @PathVariable UUID contributionId,
            @RequestParam(name = "expectedVersion", required = false) Long expectedVersion,
            HttpServletRequest request,
            RedirectAttributes redirectAttributes
    ) {
        if (expectedVersion == null) {
            redirectAttributes.addFlashAttribute("errorMessage", FLASH_ERROR_VERSION_REQUIRED);
            return redirectToDetail(contributionId);
        }

        UUID actorId = resolveAdminUserId(request);

        try {
            workflowUseCase.claim(new ClaimWikiContributionCommand(contributionId, actorId, expectedVersion));
            redirectAttributes.addFlashAttribute("successMessage", FLASH_SUCCESS_CLAIM);
            return redirectToDetail(contributionId);
        } catch (WikiContributionNotFoundException ex) {
            redirectAttributes.addFlashAttribute("errorMessage", ex.getMessage());
            return redirectToInbox();
        } catch (WikiContributionStaleMutationException ex) {
            redirectAttributes.addFlashAttribute("errorMessage", FLASH_ERROR_STALE);
            return redirectToDetail(contributionId);
        } catch (IllegalArgumentException | IllegalStateException ex) {
            redirectAttributes.addFlashAttribute("errorMessage", "Thao tác không hợp lệ: " + ex.getMessage());
            return redirectToDetail(contributionId);
        }
    }

    /**
     * Phân công lại đóng góp đang xem xét cho quản trị viên khác (chỉ dành cho SUPER_ADMIN).
     */
    @PostMapping("/{contributionId}/reassign")
    public String reassignContribution(
            @PathVariable UUID contributionId,
            @RequestParam(name = "targetUserId", required = false) UUID targetUserId,
            @RequestParam(name = "reason", required = false) String reason,
            @RequestParam(name = "expectedVersion", required = false) Long expectedVersion,
            HttpServletRequest request,
            RedirectAttributes redirectAttributes
    ) {
        if (expectedVersion == null) {
            redirectAttributes.addFlashAttribute("errorMessage", FLASH_ERROR_VERSION_REQUIRED);
            return redirectToDetail(contributionId);
        }
        if (targetUserId == null) {
            redirectAttributes.addFlashAttribute("errorMessage", "Vui lòng chọn quản trị viên nhận phân công.");
            return redirectToDetail(contributionId);
        }
        if (reason == null || reason.trim().length() < ReassignWikiContributionCommand.MIN_REASON_LENGTH || reason.trim().length() > ReassignWikiContributionCommand.MAX_REASON_LENGTH) {
            redirectAttributes.addFlashAttribute("errorMessage", String.format("Lý do phân công lại phải từ %d đến %d ký tự.",
                    ReassignWikiContributionCommand.MIN_REASON_LENGTH, ReassignWikiContributionCommand.MAX_REASON_LENGTH));
            return redirectToDetail(contributionId);
        }

        AuthenticatedRequestIdentity identity = resolveAdminIdentity(request);
        if (identity.role() != UserRole.SUPER_ADMIN) {
            throw new AccessDeniedException("Chỉ Quản trị viên cấp cao (SUPER_ADMIN) mới có quyền phân công lại đóng góp.");
        }

        try {
            workflowUseCase.reassign(new ReassignWikiContributionCommand(contributionId, identity.userId(), targetUserId, reason, expectedVersion));
            redirectAttributes.addFlashAttribute("successMessage", FLASH_SUCCESS_REASSIGN);
            return redirectToDetail(contributionId);
        } catch (WikiContributionNotFoundException ex) {
            redirectAttributes.addFlashAttribute("errorMessage", ex.getMessage());
            return redirectToInbox();
        } catch (WikiContributionStaleMutationException ex) {
            redirectAttributes.addFlashAttribute("errorMessage", FLASH_ERROR_STALE);
            return redirectToDetail(contributionId);
        } catch (IllegalArgumentException | IllegalStateException ex) {
            redirectAttributes.addFlashAttribute("errorMessage", "Thao tác không hợp lệ: " + ex.getMessage());
            return redirectToDetail(contributionId);
        }
    }

    public String resolveContribution(
            UUID contributionId,
            Long expectedVersion,
            String resolutionNote,
            HttpServletRequest request,
            RedirectAttributes redirectAttributes
    ) {
        return resolveContribution(contributionId, expectedVersion, "APPLIED", resolutionNote, request, redirectAttributes);
    }

    /**
     * Chấp thuận và giải quyết đóng góp (REVIEWING -> RESOLVED).
     */
    @PostMapping("/{contributionId}/resolve")
    public String resolveContribution(
            @PathVariable UUID contributionId,
            @RequestParam(name = "expectedVersion", required = false) Long expectedVersion,
            @RequestParam(name = "outcome", required = false) String outcomeStr,
            @RequestParam(name = "resolutionNote", required = false) String resolutionNote,
            HttpServletRequest request,
            RedirectAttributes redirectAttributes
    ) {
        if (expectedVersion == null) {
            redirectAttributes.addFlashAttribute("errorMessage", FLASH_ERROR_VERSION_REQUIRED);
            return redirectToDetail(contributionId);
        }

        WikiContributionResolutionOutcome outcome;
        try {
            outcome = outcomeStr != null ? WikiContributionResolutionOutcome.valueOf(outcomeStr.trim()) : WikiContributionResolutionOutcome.APPLIED;
        } catch (IllegalArgumentException ex) {
            redirectAttributes.addFlashAttribute("errorMessage", "Kết quả giải quyết không hợp lệ.");
            return redirectToDetail(contributionId);
        }

        String validationError = validateNote(resolutionNote);
        if (validationError != null) {
            redirectAttributes.addFlashAttribute("errorMessage", validationError);
            return redirectToDetail(contributionId);
        }

        UUID actorId = resolveAdminUserId(request);

        try {
            workflowUseCase.resolve(new ResolveWikiContributionCommand(contributionId, actorId, expectedVersion, outcome, resolutionNote));
            redirectAttributes.addFlashAttribute("successMessage", FLASH_SUCCESS_RESOLVE);
            return redirectToDetail(contributionId);
        } catch (WikiContributionNotFoundException ex) {
            redirectAttributes.addFlashAttribute("errorMessage", ex.getMessage());
            return redirectToInbox();
        } catch (WikiContributionStaleMutationException ex) {
            redirectAttributes.addFlashAttribute("errorMessage", FLASH_ERROR_STALE);
            return redirectToDetail(contributionId);
        } catch (IllegalArgumentException | IllegalStateException ex) {
            redirectAttributes.addFlashAttribute("errorMessage", "Thao tác không hợp lệ: " + ex.getMessage());
            return redirectToDetail(contributionId);
        }
    }

    /**
     * Từ chối đóng góp (REVIEWING -> REJECTED).
     */
    @PostMapping("/{contributionId}/reject")
    public String rejectContribution(
            @PathVariable UUID contributionId,
            @RequestParam(name = "expectedVersion", required = false) Long expectedVersion,
            @RequestParam(name = "resolutionNote", required = false) String resolutionNote,
            HttpServletRequest request,
            RedirectAttributes redirectAttributes
    ) {
        if (expectedVersion == null) {
            redirectAttributes.addFlashAttribute("errorMessage", FLASH_ERROR_VERSION_REQUIRED);
            return redirectToDetail(contributionId);
        }

        String validationError = validateNote(resolutionNote);
        if (validationError != null) {
            redirectAttributes.addFlashAttribute("errorMessage", validationError);
            return redirectToDetail(contributionId);
        }

        UUID actorId = resolveAdminUserId(request);

        try {
            workflowUseCase.reject(new RejectWikiContributionCommand(contributionId, actorId, expectedVersion, resolutionNote));
            redirectAttributes.addFlashAttribute("successMessage", FLASH_SUCCESS_REJECT);
            return redirectToDetail(contributionId);
        } catch (WikiContributionNotFoundException ex) {
            redirectAttributes.addFlashAttribute("errorMessage", ex.getMessage());
            return redirectToInbox();
        } catch (WikiContributionStaleMutationException ex) {
            redirectAttributes.addFlashAttribute("errorMessage", FLASH_ERROR_STALE);
            return redirectToDetail(contributionId);
        } catch (IllegalArgumentException | IllegalStateException ex) {
            redirectAttributes.addFlashAttribute("errorMessage", "Thao tác không hợp lệ: " + ex.getMessage());
            return redirectToDetail(contributionId);
        }
    }

    private String validateNote(String note) {
        if (note == null || note.trim().isEmpty()) {
            return FLASH_ERROR_NOTE_REQUIRED;
        }
        String trimmed = note.trim();
        if (trimmed.length() < WikiContribution.MIN_RESOLUTION_NOTE_LENGTH || trimmed.length() > WikiContribution.MAX_RESOLUTION_NOTE_LENGTH) {
            return FLASH_ERROR_NOTE_BOUNDS;
        }
        return null;
    }

    private AuthenticatedRequestIdentity resolveAdminIdentity(HttpServletRequest request) {
        return AuthenticatedRequestIdentityAccessor.find(request)
                .orElseThrow(() -> new AccessDeniedException("Yêu cầu thông tin định danh quản trị viên hợp lệ."));
    }

    private UUID resolveAdminUserId(HttpServletRequest request) {
        return resolveAdminIdentity(request).userId();
    }

    private String redirectToDetail(UUID contributionId) {
        return "redirect:/admin/wiki/contributions/" + contributionId;
    }

    private String redirectToInbox() {
        return "redirect:/admin/wiki/contributions";
    }
}
