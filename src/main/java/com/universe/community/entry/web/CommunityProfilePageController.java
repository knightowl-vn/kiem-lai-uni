package com.universe.community.entry.web;

import com.universe.community.application.usecase.GetCommunityPublicProfileUseCase;
import com.universe.community.contracts.dto.CommunityAuthorProfileDTO;
import com.universe.identity.application.security.AuthenticatedRequestIdentity;
import com.universe.identity.infrastructure.security.AuthenticatedRequestIdentityAccessor;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.server.ResponseStatusException;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * SSR Page controller for rendering the public Community author profile page at {@code /community/@{publicHandle}}.
 */
@Controller
public class CommunityProfilePageController {

    private final GetCommunityPublicProfileUseCase getCommunityPublicProfileUseCase;

    public CommunityProfilePageController(GetCommunityPublicProfileUseCase getCommunityPublicProfileUseCase) {
        this.getCommunityPublicProfileUseCase = Objects.requireNonNull(
                getCommunityPublicProfileUseCase,
                "GetCommunityPublicProfileUseCase cannot be null."
        );
    }

    @GetMapping("/community/@{publicHandle}")
    public String getProfilePage(
            @PathVariable("publicHandle") String publicHandle,
            HttpServletRequest request,
            Model model
    ) {
        UUID viewerUserId = AuthenticatedRequestIdentityAccessor.find(request)
                .map(AuthenticatedRequestIdentity::userId)
                .orElse(null);

        Optional<CommunityAuthorProfileDTO> profileOpt = (viewerUserId != null)
                ? getCommunityPublicProfileUseCase.execute(publicHandle, viewerUserId)
                : getCommunityPublicProfileUseCase.execute(publicHandle);
        if (profileOpt.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Community author profile not found.");
        }

        model.addAttribute("profile", profileOpt.get());
        model.addAttribute("activeNav", "community");
        model.addAttribute("returnTo", "/community/@" + publicHandle);
        return "community/profile";
    }
}
