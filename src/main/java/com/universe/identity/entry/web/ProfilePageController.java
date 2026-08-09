package com.universe.identity.entry.web;

import com.universe.identity.application.ports.CurrentUserQueryPort;
import com.universe.identity.contracts.currentuser.CurrentUserView;
import com.universe.shared.security.AuthenticatedEmailResolver;

import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class ProfilePageController {

    private final CurrentUserQueryPort
            currentUserQueryPort;

    private final AuthenticatedEmailResolver
            authenticatedEmailResolver;

    public ProfilePageController(
            CurrentUserQueryPort currentUserQueryPort,
            AuthenticatedEmailResolver authenticatedEmailResolver
    ) {
        this.currentUserQueryPort =
                currentUserQueryPort;

        this.authenticatedEmailResolver =
                authenticatedEmailResolver;
    }

    @GetMapping("/profile")
    public String profilePage(
            Authentication authentication,
            Model model
    ) {
        String email =
                authenticatedEmailResolver.require(
                        authentication
                );

        CurrentUserView user =
                currentUserQueryPort
                        .findByEmail(email)
                        .orElseThrow(
                                () -> new IllegalStateException(
                                        "Không tìm thấy tài khoản đang đăng nhập."
                                )
                        );

        model.addAttribute(
                "user",
                user
        );

        model.addAttribute(
                "hasPassword",
                user.hasLocalPassword()
        );

        return "identity/profile";
    }
}