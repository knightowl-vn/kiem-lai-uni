package com.universe.community.entry.admin;

import com.universe.community.application.command.UpdateCommunitySettingsCommand;
import com.universe.community.application.usecase.GetCommunitySettingsUseCase;
import com.universe.community.application.usecase.UpdateCommunitySettingsUseCase;
import com.universe.community.domain.CommunityPublicationMode;
import com.universe.community.domain.CommunitySettings;
import com.universe.community.domain.exception.CommunitySettingsOptimisticLockException;
import com.universe.community.entry.admin.dto.AdminCommunityPostUserDTO;
import com.universe.community.entry.admin.dto.AdminCommunitySettingsDTO;
import com.universe.identity.application.security.AuthenticatedRequestIdentity;
import com.universe.identity.contracts.dto.UserPublicProfileDTO;
import com.universe.identity.contracts.interfaces.UserIdentityContract;
import com.universe.identity.infrastructure.security.AuthenticatedRequestIdentityAccessor;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Admin controller for viewing and configuring Community runtime settings.
 */
@Controller
public class AdminCommunitySettingsController {

    private static final String VIEW_NAME = "admin/community/settings";
    private static final String PAGE_TITLE = "Quản lý cộng đồng";
    private static final String PAGE_SUBTITLE = "Theo dõi, xét duyệt và xử lý nội dung cộng đồng.";
    private static final String ACTIVE_MENU = "community-settings";

    private final GetCommunitySettingsUseCase getSettingsUseCase;
    private final UpdateCommunitySettingsUseCase updateSettingsUseCase;
    private final UserIdentityContract userIdentityContract;

    public AdminCommunitySettingsController(
            GetCommunitySettingsUseCase getSettingsUseCase,
            UpdateCommunitySettingsUseCase updateSettingsUseCase,
            UserIdentityContract userIdentityContract
    ) {
        this.getSettingsUseCase = Objects.requireNonNull(getSettingsUseCase, "GetCommunitySettingsUseCase cannot be null.");
        this.updateSettingsUseCase = Objects.requireNonNull(updateSettingsUseCase, "UpdateCommunitySettingsUseCase cannot be null.");
        this.userIdentityContract = Objects.requireNonNull(userIdentityContract, "UserIdentityContract cannot be null.");
    }

    @GetMapping("/admin/community/settings")
    public String settingsPage(Model model, HttpServletResponse response) {
        if (response != null) {
            disableCaching(response);
        }

        CommunitySettings settings = getSettingsUseCase.execute();
        AdminCommunityPostUserDTO updaterDto = resolveUpdater(settings.getUpdatedByUserId());

        AdminCommunitySettingsDTO settingsDto = new AdminCommunitySettingsDTO(
                settings.getPublicationMode(),
                settings.getVersion(),
                settings.getUpdatedAt(),
                updaterDto
        );

        model.addAttribute("settings", settingsDto);
        model.addAttribute("pageTitle", PAGE_TITLE);
        model.addAttribute("pageSubtitle", PAGE_SUBTITLE);
        model.addAttribute("activeMenu", ACTIVE_MENU);

        return VIEW_NAME;
    }

    @PostMapping("/admin/community/settings")
    public String updateSettings(
            @RequestParam("publicationMode") String publicationModeStr,
            @RequestParam("version") long version,
            HttpServletRequest request,
            RedirectAttributes redirectAttributes
    ) {
        UUID actorUserId = resolveModeratorUserId(request);

        CommunityPublicationMode mode;
        try {
            mode = CommunityPublicationMode.valueOf(publicationModeStr.trim().toUpperCase());
        } catch (Exception ex) {
            redirectAttributes.addFlashAttribute("errorMessage", "Chế độ đăng bài không hợp lệ: " + publicationModeStr);
            return "redirect:/admin/community/settings";
        }

        try {
            updateSettingsUseCase.execute(new UpdateCommunitySettingsCommand(mode, version, actorUserId));
            redirectAttributes.addFlashAttribute("successMessage", "Cập nhật cài đặt cộng đồng thành công.");
        } catch (CommunitySettingsOptimisticLockException ex) {
            redirectAttributes.addFlashAttribute("errorMessage", "Cài đặt đã được người khác cập nhật trước đó. Vui lòng tải lại trang.");
        } catch (Exception ex) {
            redirectAttributes.addFlashAttribute("errorMessage", "Không thể cập nhật cài đặt: " + ex.getMessage());
        }

        return "redirect:/admin/community/settings";
    }

    private AdminCommunityPostUserDTO resolveUpdater(UUID userId) {
        if (userId == null || new UUID(0L, 0L).equals(userId)) {
            return null;
        }
        try {
            Map<UUID, UserPublicProfileDTO> profileMap = userIdentityContract.findPublicProfilesByIds(Set.of(userId));
            if (profileMap != null && profileMap.containsKey(userId)) {
                UserPublicProfileDTO profile = profileMap.get(userId);
                return AdminCommunityPostUserDTO.resolved(userId, profile.displayName(), profile.avatarUrl(), profile.publicHandle());
            }
        } catch (Exception ignored) {
        }
        return AdminCommunityPostUserDTO.unresolved(userId);
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
