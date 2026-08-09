package com.universe.identity.entry.admin;

import com.universe.identity.application.admin.AdminUserService;
import com.universe.identity.contracts.admin.dto.AdminUserDetailView;
import com.universe.identity.contracts.admin.dto.AdminUserView;
import com.universe.identity.domain.UserRole;

import org.springframework.data.domain.Page;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.Locale;
import java.util.Objects;

@Controller
public class AdminUserController {

    private final AdminUserService adminUserService;

    public AdminUserController(
            AdminUserService adminUserService
    ) {
        this.adminUserService =
                Objects.requireNonNull(
                        adminUserService,
                        "Admin user service không được để trống."
                );
    }

    @GetMapping("/admin/users")
    public String users(
            @RequestParam(defaultValue = "")
            String keyword,

            @RequestParam(defaultValue = "ALL")
            String status,

            @RequestParam(defaultValue = "ALL")
            String role,

            @RequestParam(defaultValue = "ALL")
            String provider,

            @RequestParam(defaultValue = "0")
            int page,

            @RequestParam(defaultValue = "10")
            int size,

            Model model
    ) {
        Page<AdminUserView> usersPage =
                adminUserService.findUsers(
                        keyword,
                        status,
                        role,
                        provider,
                        page,
                        size
                );

        model.addAttribute(
                "usersPage",
                usersPage
        );

        model.addAttribute(
                "users",
                usersPage.getContent()
        );

        model.addAttribute(
                "keyword",
                keyword
        );

        model.addAttribute(
                "statusFilter",
                status
        );

        model.addAttribute(
                "roleFilter",
                role
        );

        model.addAttribute(
                "providerFilter",
                provider
        );

        model.addAttribute(
                "pageSize",
                size
        );

        model.addAttribute(
                "pageTitle",
                "Quản lý người dùng"
        );

        model.addAttribute(
                "activeMenu",
                "users"
        );

        return "admin/users";
    }

    @GetMapping("/admin/users/{id}")
    public String userDetail(
            @PathVariable("id")
            String userId,

            Authentication authentication,
            Model model,
            RedirectAttributes redirectAttributes
    ) {
        try {
            String currentAdminEmail =
                    resolveEmail(
                            authentication
                    );

            AdminUserDetailView user =
                    adminUserService.getUserDetail(
                            userId
                    );

            UserRole currentAdminRole =
                    adminUserService.getCurrentActorRole(
                            currentAdminEmail
                    );

            String targetUserRole =
                    normalizeRole(
                            user.role()
                    );

            boolean isSelf =
                    user.email() != null
                            && user.email()
                            .equalsIgnoreCase(
                                    currentAdminEmail
                            );

            boolean targetIsSuperAdmin =
                    UserRole.SUPER_ADMIN.name()
                            .equals(targetUserRole);

            boolean targetIsRegularUser =
                    UserRole.USER.name()
                            .equals(targetUserRole);

            boolean canChangeStatus =
                    !isSelf
                            && !targetIsSuperAdmin
                            && (
                            currentAdminRole
                                    == UserRole.SUPER_ADMIN
                                    || (
                                    currentAdminRole
                                            == UserRole.ADMIN
                                            && targetIsRegularUser
                            )
                    );

            boolean canChangeRole =
                    !isSelf
                            && currentAdminRole
                            == UserRole.SUPER_ADMIN
                            && !targetIsSuperAdmin;

            model.addAttribute(
                    "user",
                    user
            );

            model.addAttribute(
                    "currentAdminRole",
                    currentAdminRole.name()
            );

            model.addAttribute(
                    "isSelf",
                    isSelf
            );

            model.addAttribute(
                    "canChangeStatus",
                    canChangeStatus
            );

            model.addAttribute(
                    "canChangeRole",
                    canChangeRole
            );

            model.addAttribute(
                    "pageTitle",
                    "Chi tiết người dùng"
            );

            model.addAttribute(
                    "activeMenu",
                    "users"
            );

            return "admin/user-detail";

        } catch (IllegalArgumentException
                 | IllegalStateException exception) {

            redirectAttributes.addFlashAttribute(
                    "errorMessage",
                    exception.getMessage()
            );

            return "redirect:/admin/users";
        }
    }

    @PostMapping("/admin/users/{id}/status")
    public String changeStatus(
            @PathVariable("id")
            String userId,

            @RequestParam("status")
            String status,

            Authentication authentication,
            RedirectAttributes redirectAttributes
    ) {
        try {
            adminUserService.changeStatus(
                    userId,
                    status,
                    resolveEmail(authentication)
            );

            redirectAttributes.addFlashAttribute(
                    "successMessage",
                    "Cập nhật trạng thái tài khoản thành công."
            );

        } catch (IllegalArgumentException
                 | IllegalStateException exception) {

            redirectAttributes.addFlashAttribute(
                    "errorMessage",
                    exception.getMessage()
            );
        }

        return "redirect:/admin/users/" + userId;
    }

    @PostMapping("/admin/users/{id}/role")
    public String changeRole(
            @PathVariable("id")
            String userId,

            @RequestParam("role")
            String role,

            Authentication authentication,
            RedirectAttributes redirectAttributes
    ) {
        try {
            adminUserService.changeRole(
                    userId,
                    role,
                    resolveEmail(authentication)
            );

            redirectAttributes.addFlashAttribute(
                    "successMessage",
                    "Cập nhật quyền tài khoản thành công."
            );

        } catch (IllegalArgumentException
                 | IllegalStateException exception) {

            redirectAttributes.addFlashAttribute(
                    "errorMessage",
                    exception.getMessage()
            );
        }

        return "redirect:/admin/users/" + userId;
    }

    private String resolveEmail(
            Authentication authentication
    ) {
        if (authentication == null
                || !authentication.isAuthenticated()
                || authentication
                instanceof AnonymousAuthenticationToken) {

            throw new IllegalStateException(
                    "Không xác định được tài khoản Admin."
            );
        }

        Object principal =
                authentication.getPrincipal();

        if (principal instanceof OAuth2User oauth2User) {
            String email =
                    oauth2User.getAttribute(
                            "email"
                    );

            if (email == null || email.isBlank()) {
                throw new IllegalStateException(
                        "Google không trả về email Admin."
                );
            }

            return normalizeEmail(email);
        }

        String email =
                authentication.getName();

        if (email == null || email.isBlank()) {
            throw new IllegalStateException(
                    "Không xác định được email Admin."
            );
        }

        return normalizeEmail(email);
    }

    private String normalizeEmail(
            String email
    ) {
        return email.trim()
                .toLowerCase(Locale.ROOT);
    }

    private String normalizeRole(
            String role
    ) {
        if (role == null || role.isBlank()) {
            return UserRole.USER.name();
        }

        return role.trim()
                .toUpperCase(Locale.ROOT);
    }
}