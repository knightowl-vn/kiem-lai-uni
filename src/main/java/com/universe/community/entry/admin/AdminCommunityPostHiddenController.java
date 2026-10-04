package com.universe.community.entry.admin;

import com.universe.community.application.command.RestoreCommunityPostCommand;
import com.universe.community.application.usecase.RestoreCommunityPostUseCase;
import com.universe.community.domain.exception.CommunityPostNotFoundException;
import com.universe.community.entry.admin.dto.AdminCommunityPostHiddenPageDTO;
import com.universe.identity.application.security.AuthenticatedRequestIdentity;
import com.universe.identity.infrastructure.security.AuthenticatedRequestIdentityAccessor;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.Objects;
import java.util.UUID;

/**
 * Admin controller for Hidden Community Posts management and restoration.
 */
@Controller
public class AdminCommunityPostHiddenController {

    private static final String VIEW_NAME = "admin/community/hidden-posts";
    private static final String PAGE_TITLE = "Quản lý cộng đồng";
    private static final String PAGE_SUBTITLE = "Theo dõi, xét duyệt và xử lý nội dung cộng đồng.";
    private static final String ACTIVE_MENU = "community-hidden";
    private static final int MAX_PAGE_SIZE = 50;

    private final AdminCommunityPostReviewCoordinator coordinator;
    private final RestoreCommunityPostUseCase restoreUseCase;

    public AdminCommunityPostHiddenController(
            AdminCommunityPostReviewCoordinator coordinator,
            RestoreCommunityPostUseCase restoreUseCase
    ) {
        this.coordinator = Objects.requireNonNull(coordinator, "coordinator cannot be null.");
        this.restoreUseCase = Objects.requireNonNull(restoreUseCase, "restoreUseCase cannot be null.");
    }

    @GetMapping("/admin/community/posts/hidden")
    public String hiddenPosts(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            Model model,
            HttpServletResponse response
    ) {
        if (response != null) {
            disableCaching(response);
        }

        int safeSize = size > 0 ? Math.min(size, MAX_PAGE_SIZE) : 20;
        AdminCommunityPostHiddenPageDTO postPage = coordinator.getHiddenPosts(page, safeSize);

        model.addAttribute("postPage", postPage);
        model.addAttribute("posts", postPage.items());
        model.addAttribute("pageTitle", PAGE_TITLE);
        model.addAttribute("pageSubtitle", PAGE_SUBTITLE);
        model.addAttribute("activeMenu", ACTIVE_MENU);
        model.addAttribute("page", page);
        model.addAttribute("pageSize", safeSize);

        return VIEW_NAME;
    }

    @PostMapping("/admin/community/posts/{postId}/restore")
    public String restorePost(
            @PathVariable UUID postId,
            @RequestParam(required = false) String reason,
            HttpServletRequest request,
            RedirectAttributes redirectAttributes
    ) {
        UUID moderatorUserId = resolveModeratorUserId(request);
        try {
            restoreUseCase.execute(new RestoreCommunityPostCommand(postId, moderatorUserId, reason));
            redirectAttributes.addFlashAttribute("successMessage", "Đã khôi phục bài viết thành công.");
        } catch (CommunityPostNotFoundException ex) {
            redirectAttributes.addFlashAttribute("errorMessage", "Không tìm thấy bài viết: " + postId);
        } catch (IllegalStateException ex) {
            redirectAttributes.addFlashAttribute("errorMessage", ex.getMessage());
        }
        return "redirect:/admin/community/posts/hidden";
    }

    private UUID resolveModeratorUserId(HttpServletRequest request) {
        return AuthenticatedRequestIdentityAccessor.find(request)
                .map(AuthenticatedRequestIdentity::userId)
                .orElseThrow(() -> new AccessDeniedException("Yêu cầu thông tin định danh quản trị viên hợp lệ."));
    }

    private void disableCaching(HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store, no-cache, must-revalidate, max-age=0");
        response.setHeader("Pragma", "no-cache");
        response.setDateHeader("Expires", 0L);
    }
}
