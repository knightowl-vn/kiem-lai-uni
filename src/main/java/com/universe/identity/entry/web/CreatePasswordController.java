package com.universe.identity.entry.web;

import com.universe.identity.application.password.CreatePasswordService;
import com.universe.shared.security.AuthenticatedEmailResolver;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.security.core.Authentication;
import org.springframework.security.web.authentication.logout.SecurityContextLogoutHandler;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
public class CreatePasswordController {

    private final CreatePasswordService
            createPasswordService;

    private final AuthenticatedEmailResolver
            authenticatedEmailResolver;

    public CreatePasswordController(
            CreatePasswordService createPasswordService,
            AuthenticatedEmailResolver authenticatedEmailResolver
    ) {
        this.createPasswordService =
                createPasswordService;

        this.authenticatedEmailResolver =
                authenticatedEmailResolver;
    }

    @PostMapping("/profile/password/create")
    public String createPassword(
            @RequestParam("newPassword")
            String newPassword,

            @RequestParam("confirmPassword")
            String confirmPassword,

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

            createPasswordService.createPassword(
                    email,
                    newPassword,
                    confirmPassword
            );

            new SecurityContextLogoutHandler()
                    .logout(
                            request,
                            response,
                            authentication
                    );

            return "redirect:/login?passwordCreated";

        } catch (IllegalArgumentException
                 | IllegalStateException exception) {

            redirectAttributes.addFlashAttribute(
                    "createPasswordErrorMessage",
                    exception.getMessage()
            );

            redirectAttributes.addFlashAttribute(
                    "openCreatePasswordModal",
                    true
            );

            return "redirect:/profile";
        }
    }
}