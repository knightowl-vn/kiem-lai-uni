package com.universe.interaction.entry.community;

import com.universe.identity.application.security.AuthenticatedRequestIdentity;
import com.universe.identity.infrastructure.security.AuthenticatedRequestIdentityAccessor;
import com.universe.interaction.application.exceptions.CommentMutationForbiddenException;
import com.universe.interaction.application.exceptions.CommentTargetNotEligibleException;
import com.universe.interaction.application.exceptions.DuplicatePendingReportException;
import com.universe.interaction.application.exceptions.SelfReportNotAllowedException;
import com.universe.interaction.application.mutation.SubmitInteractionReportCommand;
import com.universe.interaction.application.mutation.SubmitInteractionReportUseCase;
import com.universe.interaction.domain.report.InteractionReport;
import com.universe.interaction.domain.report.ReportTargetType;
import com.universe.interaction.entry.community.dto.CommunityPostReportResponseDTO;
import com.universe.interaction.entry.community.dto.SubmitCommunityPostReportRequest;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * REST controller for reporting Community posts for abuse and moderation.
 *
 * <p>Preserves strict security and architectural invariants:
 * <ul>
 *   <li>Actor identity is derived exclusively from {@link AuthenticatedRequestIdentityAccessor};</li>
 *   <li>Self-reporting is strictly forbidden with 403 Forbidden;</li>
 *   <li>Duplicate pending reports are rejected with 409 Conflict;</li>
 *   <li>Missing or non-existent posts fail closed with 404 Not Found;</li>
 *   <li>Returns minimal DTOs to protect privacy and internal persistence structures.</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/community/posts/{postId}/reports")
public class CommunityPostReportController {

    private static final Logger log = LoggerFactory.getLogger(CommunityPostReportController.class);

    private final SubmitInteractionReportUseCase submitInteractionReportUseCase;

    public CommunityPostReportController(SubmitInteractionReportUseCase submitInteractionReportUseCase) {
        this.submitInteractionReportUseCase = Objects.requireNonNull(
                submitInteractionReportUseCase,
                "SubmitInteractionReportUseCase cannot be null."
        );
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<CommunityPostReportResponseDTO> submitReport(
            @PathVariable("postId") UUID postId,
            @RequestBody(required = false) SubmitCommunityPostReportRequest requestBody,
            HttpServletRequest request
    ) {
        if (postId == null || requestBody == null || requestBody.reason() == null) {
            return ResponseEntity.badRequest().build();
        }

        UUID actorUserId = resolveAuthenticatedActor(request);

        SubmitInteractionReportCommand command = new SubmitInteractionReportCommand(
                ReportTargetType.COMMUNITY_POST,
                postId,
                actorUserId,
                requestBody.reason(),
                requestBody.description()
        );

        InteractionReport report = submitInteractionReportUseCase.execute(command);

        return ResponseEntity.status(HttpStatus.CREATED)
                .body(CommunityPostReportResponseDTO.from(report));
    }

    private UUID resolveAuthenticatedActor(HttpServletRequest request) {
        return AuthenticatedRequestIdentityAccessor.find(request)
                .map(AuthenticatedRequestIdentity::userId)
                .orElseThrow(() -> new CommentMutationForbiddenException("Authentication is required to report community posts."));
    }

    @ExceptionHandler({
            IllegalArgumentException.class,
            MethodArgumentTypeMismatchException.class,
            HttpMessageNotReadableException.class
    })
    public ResponseEntity<Map<String, String>> handleBadRequest(Exception ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(Map.of("message", ex.getMessage() != null ? ex.getMessage() : "Bad request"));
    }

    @ExceptionHandler(CommentMutationForbiddenException.class)
    public ResponseEntity<Map<String, String>> handleForbidden(CommentMutationForbiddenException ex) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(Map.of("message", ex.getMessage() != null ? ex.getMessage() : "Forbidden"));
    }

    @ExceptionHandler(SelfReportNotAllowedException.class)
    public ResponseEntity<Map<String, String>> handleSelfReport(SelfReportNotAllowedException ex) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(Map.of("message", ex.getMessage() != null ? ex.getMessage() : "Self-reporting is not allowed"));
    }

    @ExceptionHandler(CommentTargetNotEligibleException.class)
    public ResponseEntity<Map<String, String>> handleTargetNotEligible(CommentTargetNotEligibleException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(Map.of("message", ex.getMessage() != null ? ex.getMessage() : "Post not found or not eligible"));
    }

    @ExceptionHandler(DuplicatePendingReportException.class)
    public ResponseEntity<Map<String, String>> handleDuplicatePendingReport(DuplicatePendingReportException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(Map.of("message", ex.getMessage() != null ? ex.getMessage() : "A pending report already exists"));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, String>> handleGenericException(Exception ex) {
        log.error("Unexpected error in CommunityPostReportController", ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("message", "Internal server error"));
    }
}
