package com.universe.interaction.infrastructure.persistence;

import com.universe.interaction.application.ports.CommentRepositoryPort;
import com.universe.interaction.application.ports.CommentSlice;
import com.universe.interaction.domain.Comment;
import com.universe.interaction.domain.CommentTarget;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
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
}
