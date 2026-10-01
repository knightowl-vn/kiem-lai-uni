package com.universe.community.entry.web;

import com.universe.community.application.usecase.GetCommunityPublicProfileUseCase;
import com.universe.community.contracts.dto.CommunityAuthorProfileDTO;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.server.ResponseStatusException;

import java.util.Objects;
import java.util.Optional;

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
            Model model
    ) {
        Optional<CommunityAuthorProfileDTO> profileOpt = getCommunityPublicProfileUseCase.execute(publicHandle);
        if (profileOpt.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Community author profile not found.");
        }

        model.addAttribute("profile", profileOpt.get());
        model.addAttribute("activeNav", "community");
        return "community/profile";
    }
}
