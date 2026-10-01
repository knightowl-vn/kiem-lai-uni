package com.universe.community.entry.web;

import com.universe.community.application.usecase.GetCommunityPublicProfilePostsUseCase;
import com.universe.community.contracts.dto.CommunityNewestFeedResponseDTO;
import com.universe.community.domain.exception.CommunityPostValidationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Public REST controller for paginating Community author profile posts at {@code /api/community/profiles/{publicHandle}/posts}.
 */
@RestController
@RequestMapping("/api/community/profiles")
public class CommunityProfileApiController {

    private final GetCommunityPublicProfilePostsUseCase getCommunityPublicProfilePostsUseCase;

    public CommunityProfileApiController(GetCommunityPublicProfilePostsUseCase getCommunityPublicProfilePostsUseCase) {
        this.getCommunityPublicProfilePostsUseCase = Objects.requireNonNull(
                getCommunityPublicProfilePostsUseCase,
                "GetCommunityPublicProfilePostsUseCase cannot be null."
        );
    }

    /**
     * GET /api/community/profiles/{publicHandle}/posts
     * Publicly retrieves a keyset-paginated slice of posts authored by the specified profile handle.
     */
    @GetMapping(value = "/{publicHandle}/posts", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<CommunityNewestFeedResponseDTO> getAuthorPosts(
            @PathVariable("publicHandle") String publicHandle,
            @RequestParam(value = "cursor", required = false) String cursor,
            @RequestParam(value = "size", required = false) Integer size
    ) {
        Optional<CommunityNewestFeedResponseDTO> responseOpt =
                getCommunityPublicProfilePostsUseCase.execute(publicHandle, cursor, size);

        if (responseOpt.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }

        return ResponseEntity.ok(responseOpt.get());
    }

    @ExceptionHandler(CommunityPostValidationException.class)
    public ResponseEntity<Map<String, String>> handleValidationException(CommunityPostValidationException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(Map.of("message", ex.getMessage() != null ? ex.getMessage() : "Validation error"));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> handleIllegalArgumentException(IllegalArgumentException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(Map.of("message", ex.getMessage() != null ? ex.getMessage() : "Bad request"));
    }
}
