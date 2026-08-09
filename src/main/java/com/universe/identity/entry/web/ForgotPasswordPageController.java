package com.universe.identity.entry.web;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import com.universe.identity.application.password.ForgotPasswordService;
import com.universe.identity.domain.exceptions.InvalidEmailException;

@Controller
public class ForgotPasswordPageController {

    private final ForgotPasswordService forgotPasswordService;

    public ForgotPasswordPageController(
            ForgotPasswordService forgotPasswordService
    ) {
        this.forgotPasswordService = forgotPasswordService;
    }

    @GetMapping("/forgot-password")
    public String showForgotPasswordPage() {
        return "identity/forgot-password";
    }

    @PostMapping("/forgot-password")
    public String handleForgotPassword(
            @RequestParam("email") String email,
            HttpServletRequest request,
            RedirectAttributes redirectAttributes
    ) {
        String normalizedEmail = email == null
                ? ""
                : email.trim().toLowerCase();

        if (normalizedEmail.isBlank()) {
            redirectAttributes.addFlashAttribute(
                    "errorMessage",
                    "Vui lòng nhập địa chỉ email."
            );

            return "redirect:/forgot-password";
        }

        try {
            forgotPasswordService.requestPasswordReset(
                    normalizedEmail,
                    request.getRemoteAddr(),
                    request.getHeader("User-Agent")
            );

        } catch (InvalidEmailException exception) {
            redirectAttributes.addFlashAttribute(
                    "errorMessage",
                    exception.getMessage()
            );

            return "redirect:/forgot-password";
        }

        redirectAttributes.addFlashAttribute(
                "successMessage",
                "Nếu email tồn tại trong hệ thống, "
                + "liên kết đặt lại mật khẩu sẽ được gửi."
        );

        return "redirect:/forgot-password";
    }
}