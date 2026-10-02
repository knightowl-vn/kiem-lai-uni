package com.universe.community.entry.web;

import com.universe.community.application.usecase.GetCommunityPostDetailUseCase;
import com.universe.community.contracts.dto.CommunityPostFeedItemDTO;
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
 * SSR Page controller for rendering a single public Community post permalink page at {@code /community/posts/{postId}}.
 */
@Controller
public class CommunityPostPermalinkPageController {

    private final GetCommunityPostDetailUseCase getCommunityPostDetailUseCase;

    public CommunityPostPermalinkPageController(GetCommunityPostDetailUseCase getCommunityPostDetailUseCase) {
        this.getCommunityPostDetailUseCase = Objects.requireNonNull(
                getCommunityPostDetailUseCase,
                "GetCommunityPostDetailUseCase cannot be null."
        );
    }

    @GetMapping("/community/posts/{postId}")
    public String getPostPermalink(
            @PathVariable("postId") UUID postId,
            HttpServletRequest request,
            Model model
    ) {
        UUID viewerUserId = AuthenticatedRequestIdentityAccessor.find(request)
                .map(AuthenticatedRequestIdentity::userId)
                .orElse(null);

        Optional<CommunityPostFeedItemDTO> postOpt = (viewerUserId != null)
                ? getCommunityPostDetailUseCase.execute(postId, viewerUserId)
                : getCommunityPostDetailUseCase.execute(postId);

        if (postOpt.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Community post not found.");
        }

        model.addAttribute("item", postOpt.get());
        model.addAttribute("activeNav", "community");
        model.addAttribute("returnTo", "/community/posts/" + postId);

        return "community/post";
    }
}
