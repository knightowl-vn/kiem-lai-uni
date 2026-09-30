package com.universe.interaction.infrastructure.persistence;

import com.universe.interaction.application.ports.CommentRepositoryPort;
import com.universe.interaction.application.ports.CommentSlice;
import com.universe.interaction.application.query.CommentTargetMetrics;
import com.universe.interaction.domain.Comment;
import com.universe.interaction.domain.CommentTarget;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Persistence adapter implementing {@link CommentRepositoryPort} using Spring Data JPA.
 *
 * <p>Preserves clean architecture boundaries:
 * <ul>
 *   <li>Translates between domain {@link Comment} aggregates and {@link CommentJpaEntity};</li>
 *   <li>Keeps Spring Data {@link Pageable} and {@link Slice} strictly inside infrastructure;</li>
 *   <li>Exposes immutable framework-free {@link CommentSlice} to application boundaries;</li>
 *   <li>Enforces deterministic ordering for both root slices (DESC) and thread replies (ASC).</li>
 * </ul>
 */
@Component
@Transactional(readOnly = true)
public class CommentPersistenceAdapter implements CommentRepositoryPort {

    private final SpringDataCommentRepository repository;
    private final CommentPersistenceMapper mapper;

    public CommentPersistenceAdapter(
            SpringDataCommentRepository repository,
            CommentPersistenceMapper mapper
    ) {
        this.repository = Objects.requireNonNull(repository, "SpringDataCommentRepository cannot be null.");
        this.mapper = Objects.requireNonNull(mapper, "CommentPersistenceMapper cannot be null.");
    }

    @Override
    @Transactional
    public Comment save(Comment comment) {
        if (comment == null) {
            throw new IllegalArgumentException("Comment cannot be null.");
        }
        CommentJpaEntity entity = mapper.toJpaEntity(comment);
        CommentJpaEntity savedEntity = repository.saveAndFlush(entity);
        return mapper.toDomain(savedEntity);
    }

    @Override
    public Optional<Comment> findById(UUID commentId) {
        if (commentId == null) {
            throw new IllegalArgumentException("Comment ID cannot be null.");
        }
        return repository.findById(commentId.toString()).map(mapper::toDomain);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<Comment> findByIdForUpdate(UUID commentId) {
        if (commentId == null) {
            throw new IllegalArgumentException("Comment ID cannot be null.");
        }
        return repository.findByIdForUpdate(commentId.toString()).map(mapper::toDomain);
    }

    @Override
    public CommentSlice findActiveRoots(CommentTarget target, int page, int size) {
        if (target == null) {
            throw new IllegalArgumentException("CommentTarget cannot be null.");
        }
        if (page < 0) {
            throw new IllegalArgumentException("Page index cannot be negative: " + page);
        }
        if (size <= 0) {
            throw new IllegalArgumentException("Page size must be greater than zero: " + size);
        }

        Pageable pageable = PageRequest.of(page, size);
        Slice<CommentJpaEntity> slice = repository.findActiveRoots(
                target.type().name(),
                target.targetId().toString(),
                pageable
        );

        List<Comment> items = slice.getContent().stream()
                .map(mapper::toDomain)
                .toList();

        return new CommentSlice(items, page, size, slice.hasNext());
    }

    @Override
    public List<Comment> findThreadReplies(UUID threadRootCommentId) {
        if (threadRootCommentId == null) {
            throw new IllegalArgumentException("Thread root comment ID cannot be null.");
        }

        List<CommentJpaEntity> entities = repository.findThreadReplies(threadRootCommentId.toString());
        return entities.stream()
                .map(mapper::toDomain)
                .toList();
    }

    @Override
    public List<UUID> findActiveRootCommentIds(CommentTarget target) {
        if (target == null) {
            throw new IllegalArgumentException("CommentTarget cannot be null.");
        }

        return repository.findActiveRootCommentIds(
                target.type().name(),
                target.targetId().toString()
        ).stream()
                .map(UUID::fromString)
                .toList();
    }

    @Override
    public List<Comment> findActiveRootsByIds(CommentTarget target, Collection<UUID> rootCommentIds) {
        if (target == null) {
            throw new IllegalArgumentException("CommentTarget cannot be null.");
        }
        if (rootCommentIds == null || rootCommentIds.isEmpty()) {
            return List.of();
        }

        List<String> idStrings = rootCommentIds.stream()
                .filter(Objects::nonNull)
                .map(UUID::toString)
                .toList();
        if (idStrings.isEmpty()) {
            return List.of();
        }

        List<CommentJpaEntity> entities = repository.findActiveRootsByIds(
                target.type().name(),
                target.targetId().toString(),
                idStrings
        );
        return entities.stream()
                .map(mapper::toDomain)
                .toList();
    }

    @Override
    public List<Comment> findThreadRepliesByRootIds(Collection<UUID> threadRootCommentIds) {
        if (threadRootCommentIds == null || threadRootCommentIds.isEmpty()) {
            return List.of();
        }

        List<String> idStrings = threadRootCommentIds.stream()
                .filter(Objects::nonNull)
                .map(UUID::toString)
                .toList();
        if (idStrings.isEmpty()) {
            return List.of();
        }

        List<CommentJpaEntity> entities = repository.findThreadRepliesByRootIds(idStrings);
        return entities.stream()
                .map(mapper::toDomain)
                .toList();
    }

    @Override
    public Map<UUID, Long> countActiveRepliesByThreadRootIds(Collection<UUID> threadRootCommentIds) {
        if (threadRootCommentIds == null || threadRootCommentIds.isEmpty()) {
            return Map.of();
        }

        List<String> idStrings = threadRootCommentIds.stream()
                .filter(Objects::nonNull)
                .map(UUID::toString)
                .toList();
        if (idStrings.isEmpty()) {
            return Map.of();
        }

        List<Object[]> rows = repository.countActiveRepliesByThreadRootIds(idStrings);
        Map<UUID, Long> result = new HashMap<>();
        for (Object[] row : rows) {
            String rootIdStr = (String) row[0];
            Number count = (Number) row[1];
            if (rootIdStr != null && count != null) {
                result.put(UUID.fromString(rootIdStr), count.longValue());
            }
        }
        return result;
    }

    @Override
    public CommentTargetMetrics getMetricsForTarget(CommentTarget target) {
        if (target == null) {
            throw new IllegalArgumentException("CommentTarget cannot be null.");
        }

        List<Object[]> rows = repository.countTargetMetrics(
                target.type().name(),
                target.targetId().toString()
        );
        if (rows == null || rows.isEmpty() || rows.get(0) == null) {
            return CommentTargetMetrics.EMPTY;
        }

        Object[] row = rows.get(0);
        long threadCount = row[0] != null ? ((Number) row[0]).longValue() : 0L;
        long activeReplies = row[1] != null ? ((Number) row[1]).longValue() : 0L;
        return new CommentTargetMetrics(threadCount, threadCount + activeReplies);
    }

    @Override
    public boolean hasDescendants(UUID commentId) {
        if (commentId == null) {
            throw new IllegalArgumentException("Comment ID cannot be null.");
        }
        String idStr = commentId.toString();
        return repository.existsByParentCommentId(idStr) || repository.existsByThreadRootCommentId(idStr);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void deleteById(UUID commentId) {
        if (commentId == null) {
            throw new IllegalArgumentException("Comment ID cannot be null.");
        }
        repository.deleteAllByIds(List.of(commentId.toString()));
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void deleteAllByIds(Collection<UUID> commentIds) {
        if (commentIds == null || commentIds.isEmpty()) {
            return;
        }
        List<String> ids = commentIds.stream().filter(Objects::nonNull).map(UUID::toString).toList();
        if (!ids.isEmpty()) {
            repository.deleteAllByIds(ids);
        }
    }

    @Override
    public List<UUID> findAllCommentIdsByTarget(com.universe.interaction.domain.CommentTargetType targetType, UUID targetId) {
        if (targetType == null || targetId == null) {
            return List.of();
        }
        List<String> idStrings = repository.findAllCommentIdsByTarget(targetType.name(), targetId.toString());
        if (idStrings == null || idStrings.isEmpty()) {
            return List.of();
        }
        return idStrings.stream().map(UUID::fromString).toList();
    }

    @Override
    public Map<UUID, Long> countActiveCommentsByTargetIds(
            com.universe.interaction.domain.CommentTargetType targetType,
            Collection<UUID> targetIds
    ) {
        if (targetType == null || targetIds == null || targetIds.isEmpty()) {
            return Map.of();
        }

        List<String> idStrings = targetIds.stream()
                .filter(Objects::nonNull)
                .map(UUID::toString)
                .toList();
        if (idStrings.isEmpty()) {
            return Map.of();
        }

        List<Object[]> rows = repository.countActiveCommentsByTargetIds(targetType.name(), idStrings);
        Map<UUID, Long> result = new HashMap<>();
        for (UUID targetId : targetIds) {
            if (targetId != null) {
                result.put(targetId, 0L);
            }
        }

        if (rows != null) {
            for (Object[] row : rows) {
                if (row != null && row.length >= 2 && row[0] != null && row[1] != null) {
                    UUID targetId = UUID.fromString((String) row[0]);
                    long count = ((Number) row[1]).longValue();
                    result.put(targetId, count);
                }
            }
        }

        return result;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public List<Comment> lockAllCommentsByTarget(com.universe.interaction.domain.CommentTargetType targetType, UUID targetId) {
        if (targetType == null || targetId == null) {
            return List.of();
        }
        List<CommentJpaEntity> entities = repository.findAllByTargetForUpdate(targetType.name(), targetId.toString());
        return entities.stream().map(mapper::toDomain).toList();
    }
}
