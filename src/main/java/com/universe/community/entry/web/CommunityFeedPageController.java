package com.universe.community.entry.web;

import com.universe.community.application.usecase.GetCommunityFeaturedFeedUseCase;
import com.universe.community.application.usecase.GetCommunityNewestFeedUseCase;
import com.universe.community.contracts.dto.CommunityFeaturedFeedResponseDTO;
import com.universe.community.contracts.dto.CommunityNewestFeedResponseDTO;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;

import java.util.Objects;

/**
 * SSR Page controller for rendering the main Community feed page at {@code /community}.
 */
@Controller
public class CommunityFeedPageController {

    private final GetCommunityNewestFeedUseCase getCommunityNewestFeedUseCase;
    private final GetCommunityFeaturedFeedUseCase getCommunityFeaturedFeedUseCase;

    public CommunityFeedPageController(
            GetCommunityNewestFeedUseCase getCommunityNewestFeedUseCase,
            GetCommunityFeaturedFeedUseCase getCommunityFeaturedFeedUseCase
    ) {
        this.getCommunityNewestFeedUseCase = Objects.requireNonNull(
                getCommunityNewestFeedUseCase,
                "GetCommunityNewestFeedUseCase cannot be null."
        );
        this.getCommunityFeaturedFeedUseCase = Objects.requireNonNull(
                getCommunityFeaturedFeedUseCase,
                "GetCommunityFeaturedFeedUseCase cannot be null."
        );
    }

    @GetMapping("/community")
    public String getCommunityFeedPage(
            @RequestParam(value = "feed", required = false) String feed,
            @RequestParam(value = "cursor", required = false) String cursor,
            @RequestParam(value = "page", required = false) Integer page,
            @RequestParam(value = "size", required = false) Integer size,
            Model model
    ) {
        String normalizedFeed = (feed == null || feed.isBlank()) ? "NEWEST" : feed.trim();

        if ("NEWEST".equalsIgnoreCase(normalizedFeed)) {
            if (page != null) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Page pagination is only supported for FEATURED feed.");
            }
            int requestedSize = (size != null) ? size : 20;
            CommunityNewestFeedResponseDTO response = getCommunityNewestFeedUseCase.execute(cursor, requestedSize);

            model.addAttribute("selectedFeed", "NEWEST");
            model.addAttribute("items", response.items());
            model.addAttribute("hasNext", response.hasNext());
            model.addAttribute("nextCursor", response.nextCursor());
            model.addAttribute("nextPage", null);
            model.addAttribute("activeNav", "community");
            return "community/index";

        } else if ("FEATURED".equalsIgnoreCase(normalizedFeed)) {
            if (cursor != null && !cursor.isBlank()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Cursor pagination is only supported for NEWEST feed.");
            }
            int requestedPage = (page != null) ? page : 0;
            int requestedSize = (size != null) ? size : 20;
            CommunityFeaturedFeedResponseDTO response = getCommunityFeaturedFeedUseCase.execute(requestedPage, requestedSize);

            model.addAttribute("selectedFeed", "FEATURED");
            model.addAttribute("items", response.items());
            model.addAttribute("hasNext", response.hasNext());
            model.addAttribute("nextCursor", null);
            model.addAttribute("nextPage", response.hasNext() ? response.page() + 1 : null);
            model.addAttribute("activeNav", "community");
            return "community/index";

        } else {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unsupported feed selector: " + feed);
        }
    }
}
