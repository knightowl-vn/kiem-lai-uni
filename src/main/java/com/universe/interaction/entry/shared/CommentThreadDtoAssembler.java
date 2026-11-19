package com.universe.interaction.entry.shared;

import com.universe.identity.contracts.dto.UserPublicProfileDTO;
import com.universe.interaction.application.query.CommentReadItem;
import com.universe.interaction.application.query.CommentThreadView;
import com.universe.interaction.entry.dto.CommentAuthorDTO;
import com.universe.interaction.entry.dto.CommentOriginDTO;
import com.universe.interaction.entry.dto.CommentReadDTO;
import com.universe.interaction.entry.dto.CommentThreadResponseDTO;
import com.universe.interaction.entry.dto.ReactionSummaryResponseDTO;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;

/**
 * Shared stateless assembler for mapping enriched {@link CommentThreadView} snapshots into
 * immutable {@link CommentThreadResponseDTO} and {@link CommentReadDTO} API contracts.
 *
 * <p>Preserves canonical author fallback, tombstone redaction, reply ordering, reaction attachment,
 * and optional module-specific origin metadata resolution.
 */
public final class CommentThreadDtoAssembler {

    private CommentThreadDtoAssembler() {
    }

    /**
     * Maps ordered surviving thread views into immutable {@link CommentThreadResponseDTO} instances.
     */
    public static List<CommentThreadResponseDTO> toThreadDtos(
            List<UUID> survivingRootIds,
            Map<UUID, CommentThreadView> threadViewsByRootId,
            Map<UUID, UserPublicProfileDTO> authorsMap,
            UUID viewerUserId,
            Map<UUID, ReactionSummaryResponseDTO> reactionSummariesByCommentId,
            Function<CommentReadItem, CommentOriginDTO> originResolver
    ) {
        if (survivingRootIds == null || survivingRootIds.isEmpty()
                || threadViewsByRootId == null || threadViewsByRootId.isEmpty()) {
            return List.of();
        }

        List<CommentThreadResponseDTO> threads = new ArrayList<>(survivingRootIds.size());
        for (UUID rootId : survivingRootIds) {
            if (rootId == null) {
                continue;
            }
            CommentThreadView threadView = threadViewsByRootId.get(rootId);
            if (threadView == null || threadView.root() == null) {
                continue;
            }
            threads.add(toThreadDto(
                    threadView,
                    authorsMap,
                    viewerUserId,
                    reactionSummariesByCommentId,
                    originResolver
            ));
        }
        return List.copyOf(threads);
    }

    /**
     * Maps a single {@link CommentThreadView} into an immutable {@link CommentThreadResponseDTO}.
     */
    public static CommentThreadResponseDTO toThreadDto(
            CommentThreadView threadView,
            Map<UUID, UserPublicProfileDTO> authorsMap,
            UUID viewerUserId,
            Map<UUID, ReactionSummaryResponseDTO> reactionSummariesByCommentId,
            Function<CommentReadItem, CommentOriginDTO> originResolver
    ) {
        Objects.requireNonNull(threadView, "CommentThreadView cannot be null.");

        CommentReadDTO rootDto = toCommentReadDto(
                threadView.root(),
                authorsMap,
                viewerUserId,
                reactionSummariesByCommentId,
                originResolver
        );

        List<CommentReadDTO> replyDtos = new ArrayList<>();
        if (threadView.replies() != null) {
            for (CommentReadItem replyItem : threadView.replies()) {
                if (replyItem == null) {
                    continue;
                }
                replyDtos.add(toCommentReadDto(
                        replyItem,
                        authorsMap,
                        viewerUserId,
                        reactionSummariesByCommentId,
                        originResolver
                ));
            }
        }

        return new CommentThreadResponseDTO(rootDto, replyDtos);
    }

    /**
     * Maps a single {@link CommentReadItem} (active or tombstone) into a {@link CommentReadDTO}.
     */
    public static CommentReadDTO toCommentReadDto(
            CommentReadItem item,
            Map<UUID, UserPublicProfileDTO> authorsMap,
            UUID viewerUserId,
            Map<UUID, ReactionSummaryResponseDTO> reactionSummariesByCommentId,
            Function<CommentReadItem, CommentOriginDTO> originResolver
    ) {
        Objects.requireNonNull(item, "CommentReadItem cannot be null.");
        if (item.tombstone()) {
            return CommentReadDTO.from(item, viewerUserId);
        }

        CommentAuthorDTO authorDto = resolveAuthorDto(item.authorUserId(), authorsMap);
        ReactionSummaryResponseDTO reactionSummary = (reactionSummariesByCommentId != null)
                ? reactionSummariesByCommentId.get(item.id())
                : null;
        CommentOriginDTO originDto = (originResolver != null) ? originResolver.apply(item) : null;

        return CommentReadDTO.from(item, authorDto, viewerUserId, reactionSummary, originDto);
    }

    /**
     * Resolves public author presentation DTO with {@link CommentAuthorDTO#fallback(UUID)} when missing.
     */
    public static CommentAuthorDTO resolveAuthorDto(
            UUID authorUserId,
            Map<UUID, UserPublicProfileDTO> authorsMap
    ) {
        UserPublicProfileDTO profile = (authorsMap != null && authorUserId != null)
                ? authorsMap.get(authorUserId)
                : null;
        return (profile != null)
                ? new CommentAuthorDTO(authorUserId, profile.displayName(), profile.avatarUrl(), profile.publicHandle())
                : CommentAuthorDTO.fallback(authorUserId);
    }
}
