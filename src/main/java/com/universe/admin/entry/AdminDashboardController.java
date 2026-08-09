package com.universe.admin.entry;

import com.universe.admin.application.AdminDashboardService;
import com.universe.identity.contracts.dto.UserDTO;
import com.universe.identity.contracts.interfaces.UserIdentityContract;

import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

import java.util.Objects;

@Controller
public class AdminDashboardController {

    private final AdminDashboardService dashboardService;

    private final UserIdentityContract
            userIdentityContract;

    public AdminDashboardController(
            AdminDashboardService dashboardService,
            UserIdentityContract userIdentityContract
    ) {
        this.dashboardService =
                Objects.requireNonNull(
                        dashboardService,
                        "Dashboard service không được để trống."
                );

        this.userIdentityContract =
                Objects.requireNonNull(
                        userIdentityContract,
                        "User identity contract không được để trống."
                );
    }

    @GetMapping({
            "/admin",
            "/admin/dashboard"
    })
    public String dashboard(
            Authentication authentication,
            Model model
    ) {
        model.addAttribute(
                "stats",
                dashboardService.getStatistics()
        );

        model.addAttribute(
                "recentUsers",
                dashboardService.getRecentUsers()
        );

        model.addAttribute(
                "currentAdminDisplayName",
                resolveCurrentAdminDisplayName(
                        authentication
                )
        );

        model.addAttribute(
                "pageTitle",
                "Tổng quan"
        );

        model.addAttribute(
                "activeMenu",
                "dashboard"
        );

        return "admin/dashboard";
    }

    private String resolveCurrentAdminDisplayName(
            Authentication authentication
    ) {
        String email =
                resolveCurrentAdminEmail(
                        authentication
                );

        UserDTO currentAdmin =
                userIdentityContract
                        .findByEmail(email)
                        .orElseThrow(() ->
                                new IllegalStateException(
                                        "Không tìm thấy tài khoản quản trị đang đăng nhập."
                                )
                        );

        return currentAdmin.displayName();
    }

    private String resolveCurrentAdminEmail(
            Authentication authentication
    ) {
        if (authentication == null
                || !authentication.isAuthenticated()
                || authentication
                instanceof AnonymousAuthenticationToken) {

            throw new IllegalStateException(
                    "Không xác định được tài khoản quản trị."
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
                        "Google không trả về email quản trị viên."
                );
            }

            return email.trim();
        }

        String email =
                authentication.getName();

        if (email == null || email.isBlank()) {
            throw new IllegalStateException(
                    "Không xác định được email quản trị viên."
            );
        }

        return email.trim();
    }
}