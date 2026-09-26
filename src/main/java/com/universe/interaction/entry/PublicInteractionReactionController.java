package com.universe.interaction.entry;

import com.universe.identity.application.security.AuthenticatedRequestIdentity;
import com.universe.identity.infrastructure.security.AuthenticatedRequestIdentityAccessor;
import com.universe.interaction.application.exceptions.ReactionTargetNotEligibleException;
import com.universe.interaction.application.mutation.RemoveReactionCommand;
import com.universe.interaction.application.mutation.RemoveReactionUseCase;
import com.universe.interaction.application.mutation.SetReactionCommand;
import com.universe.interaction.application.mutation.SetReactionUseCase;
import com.universe.interaction.application.query.GetReactionSummaryUseCase;
import com.universe.interaction.application.query.ReactionSummary;
import com.universe.interaction.domain.reaction.ReactionTarget;
import com.universe.interaction.domain.reaction.ReactionTargetType;
import com.universe.interaction.domain.reaction.ReactionType;
import com.universe.interaction.entry.dto.ReactionSummaryResponseDTO;
import com.universe.interaction.entry.dto.SetReactionRequest;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Public REST controller for Content Reactions.
 *
 * <p>Security and architecture invariants:
 * <ul>
 *   <li>GET /api/interaction/reactions is publicly accessible (permitAll).
 *       If authenticated, returns the user's active reaction; if anonymous, returns currentUserReaction = null.</li>
 *   <li>PUT /api/interaction/reactions requires authentication and CSRF token.
 *       Actor identity is derived exclusively from {@link AuthenticatedRequestIdentityAccessor}.</li>
 *   <li>If target is missing, unpublished, or ineligible, returns 404 Not Found without disclosing internal details.</li>
 *   <li>Malformed parameters, unknown reaction/target types, or unparseable bodies return 400 Bad Request.</li>
 *   <li>Successful mutations return 200 OK with the latest authoritative aggregate {@link ReactionSummaryResponseDTO}.</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/interaction/reactions")
public class PublicInteractionReactionController {

    private final GetReactionSummaryUseCase getReactionSummaryUseCase;
    private final SetReactionUseCase setReactionUseCase;
    private final RemoveReactionUseCase removeReactionUseCase;

    public PublicInteractionReactionController(
            GetReactionSummaryUseCase getReactionSummaryUseCase,
            SetReactionUseCase setReactionUseCase,
            RemoveReactionUseCase removeReactionUseCase
    ) {
        this.getReactionSummaryUseCase = Objects.requireNonNull(
                getReactionSummaryUseCase,
                "GetReactionSummaryUseCase cannot be null."
        );
        this.setReactionUseCase = Objects.requireNonNull(
                setReactionUseCase,
                "SetReactionUseCase cannot be null."
        );
        this.removeReactionUseCase = Objects.requireNonNull(
                removeReactionUseCase,
                "RemoveReactionUseCase cannot be null."
        );
    }

    /**
     * GET /api/interaction/reactions?targetType=NOVEL_CHAPTER&targetId=UUID
     * Returns aggregate reaction summary and current viewer reaction state.
     */
    @GetMapping
    public ResponseEntity<ReactionSummaryResponseDTO> getReactionSummary(
            @RequestParam(name = "targetType", required = false) String targetTypeStr,
            @RequestParam(name = "targetId", required = false) UUID targetId,
            HttpServletRequest request
    ) {
        if (targetTypeStr == null || targetTypeStr.isBlank() || targetId == null) {
            return ResponseEntity.badRequest().build();
        }

        ReactionTargetType targetType;
        try {
            targetType = ReactionTargetType.valueOf(targetTypeStr.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().build();
        }

        UUID viewerUserId = AuthenticatedRequestIdentityAccessor.find(request)
                .map(AuthenticatedRequestIdentity::userId)
                .orElse(null);

        ReactionTarget target = new ReactionTarget(targetType, targetId);
        ReactionSummary summary = getReactionSummaryUseCase.execute(target, viewerUserId);

        return ResponseEntity.ok(ReactionSummaryResponseDTO.from(summary));
    }

    /**
     * PUT /api/interaction/reactions
     * Sets or removes the authenticated user's reaction on an eligible target.
     */
    @PutMapping
    public ResponseEntity<ReactionSummaryResponseDTO> setReaction(
            @RequestBody(required = false) SetReactionRequest body,
            HttpServletRequest request
    ) {
        if (body == null || body.targetType() == null || body.targetType().isBlank() || body.targetId() == null) {
            return ResponseEntity.badRequest().build();
        }

        ReactionTargetType targetType;
        try {
            targetType = ReactionTargetType.valueOf(body.targetType().trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().build();
        }

        Optional<AuthenticatedRequestIdentity> identityOptional = AuthenticatedRequestIdentityAccessor.find(request);
        if (identityOptional.isEmpty()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        UUID actorUserId = identityOptional.get().userId();
        ReactionTarget target = new ReactionTarget(targetType, body.targetId());

        if (body.reactionType() == null || body.reactionType().trim().isEmpty()) {
            removeReactionUseCase.execute(new RemoveReactionCommand(actorUserId, target));
        } else {
            ReactionType reactionType;
            try {
                reactionType = ReactionType.valueOf(body.reactionType().trim().toUpperCase());
            } catch (IllegalArgumentException ex) {
                return ResponseEntity.badRequest().build();
            }

            setReactionUseCase.execute(new SetReactionCommand(actorUserId, target, reactionType));
        }

        ReactionSummary updatedSummary = getReactionSummaryUseCase.execute(target, actorUserId);
        return ResponseEntity.ok(ReactionSummaryResponseDTO.from(updatedSummary));
    }

    @ExceptionHandler(ReactionTargetNotEligibleException.class)
    public ResponseEntity<Void> handleTargetNotEligible(ReactionTargetNotEligibleException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
    }

    @ExceptionHandler({
            MethodArgumentTypeMismatchException.class,
            HttpMessageNotReadableException.class
    })
    public ResponseEntity<Void> handleBadRequest(Exception ex) {
        return ResponseEntity.badRequest().build();
    }
}
