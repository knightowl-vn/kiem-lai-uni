package com.universe.community.entry.web;

import com.universe.community.application.command.EditCommunityPostCaptionCommand;
import com.universe.community.application.mapper.CommunityPostDTOMapper;
import com.universe.community.application.usecase.CreateCommunityPostWithImageUseCase;
import com.universe.community.application.usecase.DeleteCommunityPostUseCase;
import com.universe.community.application.usecase.EditCommunityPostCaptionUseCase;
import com.universe.community.application.usecase.GetCommunityFeaturedFeedUseCase;
import com.universe.community.application.usecase.GetCommunityNewestFeedUseCase;
import com.universe.community.application.usecase.GetCommunityPostRevisionsUseCase;
import com.universe.community.contracts.dto.CommunityFeaturedFeedResponseDTO;
import com.universe.community.contracts.dto.CommunityNewestFeedResponseDTO;
import com.universe.community.contracts.dto.CommunityPostPublicDTO;
import com.universe.community.contracts.dto.CommunityPostRevisionPublicDTO;
import com.universe.community.contracts.dto.EditCommunityPostCaptionRequestDTO;
import com.universe.community.domain.CommunityPost;
import com.universe.community.domain.exception.CommunityPostNotFoundException;
import com.universe.community.domain.exception.CommunityPostPendingReportConflictException;
import com.universe.community.domain.exception.CommunityPostUnauthorizedException;
import com.universe.community.domain.exception.CommunityPostValidationException;
import com.universe.identity.application.security.AuthenticatedRequestIdentity;
import com.universe.identity.infrastructure.security.AuthenticatedRequestIdentityAccessor;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Public REST controller for managing and reading Community Posts.
 */
@RestController
@RequestMapping("/api/community/posts")
public class CommunityPostController {

    private final CreateCommunityPostWithImageUseCase createCommunityPostWithImageUseCase;
    private final DeleteCommunityPostUseCase deleteCommunityPostUseCase;
    private final EditCommunityPostCaptionUseCase editCommunityPostCaptionUseCase;
    private final GetCommunityNewestFeedUseCase getCommunityNewestFeedUseCase;
    private final GetCommunityFeaturedFeedUseCase getCommunityFeaturedFeedUseCase;
    private final GetCommunityPostRevisionsUseCase getCommunityPostRevisionsUseCase;

    public CommunityPostController(
            CreateCommunityPostWithImageUseCase createCommunityPostWithImageUseCase,
            DeleteCommunityPostUseCase deleteCommunityPostUseCase,
            EditCommunityPostCaptionUseCase editCommunityPostCaptionUseCase,
            GetCommunityNewestFeedUseCase getCommunityNewestFeedUseCase,
            GetCommunityFeaturedFeedUseCase getCommunityFeaturedFeedUseCase,
            GetCommunityPostRevisionsUseCase getCommunityPostRevisionsUseCase
    ) {
        this.createCommunityPostWithImageUseCase = Objects.requireNonNull(
                createCommunityPostWithImageUseCase,
                "CreateCommunityPostWithImageUseCase cannot be null."
        );
        this.deleteCommunityPostUseCase = Objects.requireNonNull(
                deleteCommunityPostUseCase,
                "DeleteCommunityPostUseCase cannot be null."
        );
        this.editCommunityPostCaptionUseCase = Objects.requireNonNull(
                editCommunityPostCaptionUseCase,
                "EditCommunityPostCaptionUseCase cannot be null."
        );
        this.getCommunityNewestFeedUseCase = Objects.requireNonNull(
                getCommunityNewestFeedUseCase,
                "GetCommunityNewestFeedUseCase cannot be null."
        );
        this.getCommunityFeaturedFeedUseCase = Objects.requireNonNull(
                getCommunityFeaturedFeedUseCase,
                "GetCommunityFeaturedFeedUseCase cannot be null."
        );
        this.getCommunityPostRevisionsUseCase = Objects.requireNonNull(
                getCommunityPostRevisionsUseCase,
                "GetCommunityPostRevisionsUseCase cannot be null."
        );
    }

    /**
     * GET /api/community/posts
     * Publicly retrieves a paginated slice of Community posts for either NEWEST (keyset) or FEATURED (page-based) feed.
     */
    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> getFeed(
            @RequestParam(value = "feed", required = false) String feed,
            @RequestParam(value = "cursor", required = false) String cursor,
            @RequestParam(value = "page", required = false) Integer page,
            @RequestParam(value = "size", required = false) Integer size,
            HttpServletRequest request
    ) {
        String normalizedFeed = (feed == null || feed.isBlank()) ? "NEWEST" : feed.trim();

        UUID viewerUserId = AuthenticatedRequestIdentityAccessor.find(request)
                .map(AuthenticatedRequestIdentity::userId)
                .orElse(null);

        if ("NEWEST".equalsIgnoreCase(normalizedFeed)) {
            if (page != null) {
                throw new CommunityPostValidationException("Page pagination is only supported for FEATURED feed.");
            }
            int requestedSize = (size != null) ? size : 20;
            CommunityNewestFeedResponseDTO response = (viewerUserId != null)
                    ? getCommunityNewestFeedUseCase.execute(cursor, requestedSize, viewerUserId)
                    : getCommunityNewestFeedUseCase.execute(cursor, requestedSize);
            return ResponseEntity.ok(response);
        } else if ("FEATURED".equalsIgnoreCase(normalizedFeed)) {
            if (cursor != null && !cursor.isBlank()) {
                throw new CommunityPostValidationException("Cursor pagination is only supported for NEWEST feed.");
            }
            int requestedPage = (page != null) ? page : 0;
            int requestedSize = (size != null) ? size : 20;
            CommunityFeaturedFeedResponseDTO response = (viewerUserId != null)
                    ? getCommunityFeaturedFeedUseCase.execute(requestedPage, requestedSize, viewerUserId)
                    : getCommunityFeaturedFeedUseCase.execute(requestedPage, requestedSize);
            return ResponseEntity.ok(response);
        } else {
            throw new CommunityPostValidationException("Unsupported feed selector: " + feed);
        }
    }

    /**
     * POST /api/community/posts (multipart/form-data)
     * Creates a new Community Post. Supports caption-only and single image attachment (up to 10 MB).
     * Enforces maximum of one image attachment.
     */
    @PostMapping(
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE
    )
    public ResponseEntity<?> createPost(
            @RequestParam(value = "caption", required = false) String caption,
            @RequestParam(value = "image", required = false) List<MultipartFile> imageFiles,
            HttpServletRequest request
    ) {
        Optional<AuthenticatedRequestIdentity> identityOpt = AuthenticatedRequestIdentityAccessor.find(request);
        if (identityOpt.isEmpty()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("message", "User must be authenticated to create a community post."));
        }

        if (imageFiles != null && imageFiles.size() > 1) {
            throw new CommunityPostValidationException("A community post can have at most one image attachment.");
        }

        MultipartFile imageFile = (imageFiles != null && !imageFiles.isEmpty()) ? imageFiles.get(0) : null;

        UUID actorUserId = identityOpt.get().userId();

        CommunityPost post;
        if (imageFile != null) {
            try (InputStream inputStream = imageFile.getInputStream()) {
                post = createCommunityPostWithImageUseCase.execute(
                        actorUserId,
                        caption,
                        inputStream,
                        imageFile.getSize(),
                        imageFile.getContentType(),
                        imageFile.getOriginalFilename()
                );
            } catch (IOException e) {
                return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                        .body(Map.of("message", "Failed to read uploaded image stream."));
            }
        } else {
            post = createCommunityPostWithImageUseCase.execute(
                    actorUserId,
                    caption,
                    null,
                    0,
                    null,
                    null
            );
        }

        CommunityPostPublicDTO responseDto = CommunityPostDTOMapper.toPublicDTO(post);
        return ResponseEntity.status(HttpStatus.CREATED).body(responseDto);
    }

    /**
     * DELETE /api/community/posts/{postId}
     * Hard-deletes a Community Post owned by the authenticated actor, cleaning up interactions
     * and transitioning attached media to DELETED.
     */
    @DeleteMapping(value = "/{postId}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Void> deletePost(
            @PathVariable("postId") UUID postId,
            HttpServletRequest request
    ) {
        Optional<AuthenticatedRequestIdentity> identityOpt = AuthenticatedRequestIdentityAccessor.find(request);
        if (identityOpt.isEmpty()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        UUID actorUserId = identityOpt.get().userId();
        deleteCommunityPostUseCase.execute(actorUserId, postId);

        return ResponseEntity.noContent().build();
    }

    /**
     * PATCH /api/community/posts/{postId}
     * Edits the caption of an existing Community Post owned by the authenticated actor.
     */
    @PatchMapping(
            value = "/{postId}",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE
    )
    public ResponseEntity<CommunityPostPublicDTO> editCaption(
            @PathVariable("postId") UUID postId,
            @RequestBody(required = false) EditCommunityPostCaptionRequestDTO requestDto,
            HttpServletRequest request
    ) {
        Optional<AuthenticatedRequestIdentity> identityOpt = AuthenticatedRequestIdentityAccessor.find(request);
        if (identityOpt.isEmpty()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        if (requestDto == null || requestDto.caption() == null) {
            throw new CommunityPostValidationException("Post caption cannot be null.");
        }

        UUID actorUserId = identityOpt.get().userId();
        EditCommunityPostCaptionCommand command = new EditCommunityPostCaptionCommand(
                postId,
                actorUserId,
                requestDto.caption()
        );

        CommunityPost post = editCommunityPostCaptionUseCase.execute(command);
        return ResponseEntity.ok(CommunityPostDTOMapper.toPublicDTO(post));
    }

    /**
     * GET /api/community/posts/{postId}/revisions
     * Publicly retrieves the historical edit revisions for a Community post, ordered newest-first.
     */
    @GetMapping(value = "/{postId}/revisions", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<List<CommunityPostRevisionPublicDTO>> getPostRevisions(
            @PathVariable("postId") UUID postId
    ) {
        List<CommunityPostRevisionPublicDTO> revisions = getCommunityPostRevisionsUseCase.execute(postId);
        return ResponseEntity.ok(revisions);
    }

    @ExceptionHandler(org.springframework.http.converter.HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, String>> handleHttpMessageNotReadableException(org.springframework.http.converter.HttpMessageNotReadableException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(Map.of("message", "Malformed or missing request body"));
    }

    @ExceptionHandler(CommunityPostNotFoundException.class)
    public ResponseEntity<Map<String, String>> handleNotFoundException(CommunityPostNotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(Map.of("message", ex.getMessage() != null ? ex.getMessage() : "Resource not found"));
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

    @ExceptionHandler(CommunityPostUnauthorizedException.class)
    public ResponseEntity<Map<String, String>> handleUnauthorizedException(CommunityPostUnauthorizedException ex) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(Map.of("message", ex.getMessage() != null ? ex.getMessage() : "Unauthorized"));
    }

    @ExceptionHandler(CommunityPostPendingReportConflictException.class)
    public ResponseEntity<Map<String, String>> handlePendingReportConflictException(CommunityPostPendingReportConflictException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(Map.of("message", ex.getMessage() != null ? ex.getMessage() : "Post has pending reports"));
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, String>> handleIllegalStateException(IllegalStateException ex) {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("message", ex.getMessage() != null ? ex.getMessage() : "Internal server error"));
    }
}
