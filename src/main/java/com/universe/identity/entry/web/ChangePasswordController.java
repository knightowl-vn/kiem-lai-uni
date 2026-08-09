package com.universe.identity.entry.web;

import com.universe.identity.application.password.ChangePasswordService;
import com.universe.shared.security.AuthenticatedEmailResolver;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.logout.SecurityContextLogoutHandler;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
public class ChangePasswordController {

    private final ChangePasswordService
            changePasswordService;

    private final AuthenticatedEmailResolver
            authenticatedEmailResolver;

    public ChangePasswordController(
            ChangePasswordService changePasswordService,
            AuthenticatedEmailResolver authenticatedEmailResolver
    ) {
        this.changePasswordService =
                changePasswordService;

        this.authenticatedEmailResolver =
                authenticatedEmailResolver;
    }

    @PostMapping("/profile/password")
    public String changePassword(
            @RequestParam("currentPassword")
            String currentPassword,

            @RequestParam("newPassword")
            String newPassword,

            @RequestParam("confirmNewPassword")
            String confirmNewPassword,

            Authentication authentication,
            HttpServletRequest request,
            HttpServletResponse response,
            RedirectAttributes redirectAttributes
    ) {
        try {
            String email =
                    authenticatedEmailResolver.require(
                            authentication
                    );

            changePasswordService.changePassword(
                    email,
                    currentPassword,
                    newPassword,
                    confirmNewPassword
            );

            logoutCurrentSession(
                    request,
                    response,
                    authentication
            );

            return "redirect:/login?passwordChanged";

        } catch (IllegalArgumentException
                 | IllegalStateException exception) {

            redirectAttributes.addFlashAttribute(
                    "passwordErrorMessage",
                    exception.getMessage()
            );

            redirectAttributes.addFlashAttribute(
                    "openPasswordModal",
                    true
            );

            return "redirect:/profile";
        }
    }

    private void logoutCurrentSession(
            HttpServletRequest request,
            HttpServletResponse response,
            Authentication authentication
    ) {
        new SecurityContextLogoutHandler()
                .logout(
                        request,
                        response,
                        authentication
                );

        HttpSession session =
                request.getSession(false);

        if (session != null) {
            session.invalidate();
        }

        SecurityContextHolder.clearContext();
    }
}