package com.universe.interaction.infrastructure.persistence;

import com.universe.interaction.application.ports.UserAuthoredCommentsQueryPort;
import com.universe.interaction.contracts.dto.authored.AuthoredCommentItemDTO;
import com.universe.interaction.contracts.dto.authored.AuthoredCommentPageDTO;
import com.universe.interaction.domain.CommentTargetType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Persistence adapter implementing {@link UserAuthoredCommentsQueryPort}.
 *
 * <p>Uses {@link SpringDataCommentRepository#findAuthoredComments(String, Collection, Pageable)}
 * to perform index-backed, author-centric comment retrieval filtering strictly on ACTIVE comments.
 */
@Component
public class UserAuthoredCommentsQueryPersistenceAdapter implements UserAuthoredCommentsQueryPort {

    private final SpringDataCommentRepository repository;

    public UserAuthoredCommentsQueryPersistenceAdapter(SpringDataCommentRepository repository) {
        this.repository = Objects.requireNonNull(repository, "SpringDataCommentRepository cannot be null.");
    }

    @Override
    public AuthoredCommentPageDTO findAuthoredComments(
            UUID authorUserId,
            Set<CommentTargetType> targetTypes,
            int page,
            int size
    ) {
        if (authorUserId == null || targetTypes == null || targetTypes.isEmpty()) {
            return AuthoredCommentPageDTO.empty(page, size);
        }

        int normalizedPage = Math.max(0, page);
        int normalizedSize = size > 0 ? size : 20;

        List<String> targetTypeNames = targetTypes.stream()
                .map(Enum::name)
                .toList();

        Pageable pageable = PageRequest.of(normalizedPage, normalizedSize);
        Page<CommentJpaEntity> entityPage = repository.findAuthoredComments(
                authorUserId.toString(),
                targetTypeNames,
                pageable
        );

        List<AuthoredCommentItemDTO> items = entityPage.getContent().stream()
                .map(this::toDTO)
                .toList();

        return new AuthoredCommentPageDTO(
                items,
                entityPage.getNumber(),
                entityPage.getSize(),
                entityPage.getTotalElements(),
                entityPage.getTotalPages(),
                entityPage.isFirst(),
                entityPage.isLast()
        );
    }

    private AuthoredCommentItemDTO toDTO(CommentJpaEntity entity) {
        return new AuthoredCommentItemDTO(
                UUID.fromString(entity.getId()),
                CommentTargetType.valueOf(entity.getTargetType()),
                UUID.fromString(entity.getTargetId()),
                entity.getBody(),
                entity.getCreatedAt(),
                entity.getUpdatedAt(),
                entity.getParentCommentId() != null ? UUID.fromString(entity.getParentCommentId()) : null,
                entity.getThreadRootCommentId() != null ? UUID.fromString(entity.getThreadRootCommentId()) : null
        );
    }
}
