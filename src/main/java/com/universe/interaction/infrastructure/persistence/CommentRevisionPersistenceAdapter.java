package com.universe.interaction.infrastructure.persistence;

import com.universe.interaction.application.ports.CommentRevisionRepositoryPort;
import com.universe.interaction.application.ports.CommentRevisionSlice;
import com.universe.interaction.domain.CommentRevision;
import jakarta.persistence.EntityManager;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.orm.jpa.EntityManagerFactoryUtils;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Persistence adapter implementing {@link CommentRevisionRepositoryPort} using Spring Data JPA and explicit EntityManager.
 *
 * <p>Preserves clean architecture boundaries and strict transactional invariants:
 * <ul>
 *   <li>Translates between domain {@link CommentRevision} entities and {@link CommentRevisionJpaEntity};</li>
 *   <li>Enforces strictly INSERT-ONLY persistence semantics: {@link #save(CommentRevision)} uses explicit
 *       {@link EntityManager#persist(Object)} and {@link EntityManager#flush()} instead of merge semantics,
 *       guaranteeing that existing historical revisions can never be overwritten;</li>
 *   <li>Keeps Spring Data {@link Pageable} and {@link Slice} strictly inside infrastructure;</li>
 *   <li>Exposes immutable framework-free {@link CommentRevisionSlice} to application boundaries;</li>
 *   <li>Uses {@link Propagation#MANDATORY} on mutation and sequence operations ({@link #save},
 *       {@link #getNextRevisionNumber}, {@link #deleteAllByCommentId}) to enforce that callers participate
 *       in the outer atomic mutation transaction;</li>
 *   <li>Provides read-only zero-based slice pagination for public history inspection.</li>
 * </ul>
 */
@Repository
@Transactional(readOnly = true)
public class CommentRevisionPersistenceAdapter implements CommentRevisionRepositoryPort {

    private final SpringDataCommentRevisionRepository repository;
    private final CommentRevisionPersistenceMapper mapper;
    private final EntityManager entityManager;

    public CommentRevisionPersistenceAdapter(
            SpringDataCommentRevisionRepository repository,
            CommentRevisionPersistenceMapper mapper,
            EntityManager entityManager
    ) {
        this.repository = Objects.requireNonNull(repository, "SpringDataCommentRevisionRepository cannot be null.");
        this.mapper = Objects.requireNonNull(mapper, "CommentRevisionPersistenceMapper cannot be null.");
        this.entityManager = Objects.requireNonNull(entityManager, "EntityManager cannot be null.");
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public CommentRevision save(CommentRevision revision) {
        if (revision == null) {
            throw new IllegalArgumentException("CommentRevision cannot be null.");
        }
        CommentRevisionJpaEntity entity = mapper.toJpaEntity(revision);
        try {
            entityManager.persist(entity);
            entityManager.flush();
        } catch (org.hibernate.exception.ConstraintViolationException ex) {
            throw new DataIntegrityViolationException(ex.getMessage(), ex);
        } catch (jakarta.persistence.EntityExistsException ex) {
            throw new DataIntegrityViolationException(ex.getMessage(), ex);
        } catch (RuntimeException ex) {
            if (ex.getCause() instanceof org.hibernate.exception.ConstraintViolationException) {
                throw new DataIntegrityViolationException(ex.getMessage(), ex);
            }
            DataAccessException translated = EntityManagerFactoryUtils.convertJpaAccessExceptionIfPossible(ex);
            if (translated != null) {
                if (translated.getCause() instanceof org.hibernate.exception.ConstraintViolationException) {
                    throw new DataIntegrityViolationException(translated.getMessage(), translated);
                }
                throw translated;
            }
            throw ex;
        }
        return mapper.toDomain(entity);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public int getNextRevisionNumber(UUID commentId) {
        if (commentId == null) {
            throw new IllegalArgumentException("Comment ID cannot be null.");
        }
        return repository.findMaxRevisionNumberByCommentId(commentId.toString()) + 1;
    }

    @Override
    public CommentRevisionSlice findSliceByCommentId(UUID commentId, int page, int size) {
        if (commentId == null) {
            throw new IllegalArgumentException("Comment ID cannot be null.");
        }
        if (page < 0) {
            throw new IllegalArgumentException("Page index cannot be negative: " + page);
        }
        if (size <= 0) {
            throw new IllegalArgumentException("Page size must be greater than zero: " + size);
        }

        Pageable pageable = PageRequest.of(page, size);
        Slice<CommentRevisionJpaEntity> slice = repository.findSliceByCommentId(commentId.toString(), pageable);

        List<CommentRevision> items = slice.getContent().stream()
                .map(mapper::toDomain)
                .toList();

        return new CommentRevisionSlice(items, page, size, slice.hasNext());
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void deleteAllByCommentId(UUID commentId) {
        if (commentId == null) {
            throw new IllegalArgumentException("Comment ID cannot be null.");
        }
        repository.deleteAllByCommentId(commentId.toString());
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void deleteAllByCommentIds(java.util.Collection<UUID> commentIds) {
        if (commentIds == null || commentIds.isEmpty()) {
            return;
        }
        List<String> ids = commentIds.stream().filter(Objects::nonNull).map(UUID::toString).toList();
        if (!ids.isEmpty()) {
            repository.deleteAllByCommentIdIn(ids);
        }
    }

    @Override
    public long countByCommentIds(java.util.Collection<UUID> commentIds) {
        if (commentIds == null || commentIds.isEmpty()) {
            return 0L;
        }
        List<String> ids = commentIds.stream().filter(Objects::nonNull).map(UUID::toString).toList();
        if (ids.isEmpty()) {
            return 0L;
        }
        return repository.countByCommentIdIn(ids);
    }
}
