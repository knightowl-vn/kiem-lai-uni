package com.universe.identity.entry.web;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import com.universe.identity.application.password.ResetPasswordService;

@Controller
public class ResetPasswordPageController {

    private final ResetPasswordService resetPasswordService;

    public ResetPasswordPageController(
            ResetPasswordService resetPasswordService
    ) {
        this.resetPasswordService = resetPasswordService;
    }

    @GetMapping("/reset-password")
    public String showResetPasswordPage(
            @RequestParam("token") String token,
            Model model
    ) {
        boolean valid =
                resetPasswordService.isTokenValid(token);

        if (!valid) {
            model.addAttribute(
                    "errorMessage",
                    "Liên kết đặt lại mật khẩu không hợp lệ hoặc đã hết hạn."
            );

            return "identity/reset-password-invalid";
        }

        model.addAttribute("token", token);

        return "identity/reset-password";
    }

    @PostMapping("/reset-password")
    public String handleResetPassword(
            @RequestParam("token") String token,
            @RequestParam("newPassword") String newPassword,
            @RequestParam("confirmPassword") String confirmPassword,
            RedirectAttributes redirectAttributes
    ) {
        String error = resetPasswordService.resetPassword(
                token,
                newPassword,
                confirmPassword
        );

        if (error != null) {
            redirectAttributes.addFlashAttribute(
                    "errorMessage",
                    error
            );

            return "redirect:/reset-password?token=" + token;
        }

        return "redirect:/login?resetSuccess";
    }
}