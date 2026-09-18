package com.universe.novel.infrastructure.persistence.anchor;

import com.universe.novel.application.ports.ChapterCommentAnchorRepositoryPort;
import com.universe.novel.domain.anchor.ChapterCommentAnchor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Infrastructure persistence adapter implementing {@link ChapterCommentAnchorRepositoryPort}.
 *
 * <p>Preserves clean architecture contracts:
 * <ul>
 *   <li>Translates between domain {@link ChapterCommentAnchor} and {@link ChapterCommentAnchorJpaEntity};</li>
 *   <li>Enforces append-only immutable semantics for root thread anchors;</li>
 *   <li>Zero dependency on Interaction bounded context.</li>
 * </ul>
 */
@Component
public class ChapterCommentAnchorPersistenceAdapter implements ChapterCommentAnchorRepositoryPort {

    private final SpringDataChapterCommentAnchorJpaRepository repository;
    private final ChapterCommentAnchorPersistenceMapper mapper;

    public ChapterCommentAnchorPersistenceAdapter(
            SpringDataChapterCommentAnchorJpaRepository repository,
            ChapterCommentAnchorPersistenceMapper mapper
    ) {
        this.repository = Objects.requireNonNull(repository, "SpringDataChapterCommentAnchorJpaRepository cannot be null.");
        this.mapper = Objects.requireNonNull(mapper, "ChapterCommentAnchorPersistenceMapper cannot be null.");
    }

    @Override
    @Transactional
    public ChapterCommentAnchor save(ChapterCommentAnchor anchor) {
        if (anchor == null) {
            throw new IllegalArgumentException("ChapterCommentAnchor cannot be null.");
        }
        ChapterCommentAnchorJpaEntity entity = mapper.toJpaEntity(anchor);
        ChapterCommentAnchorJpaEntity savedEntity = repository.saveAndFlush(entity);
        return mapper.toDomain(savedEntity);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<ChapterCommentAnchor> findByRootCommentId(UUID rootCommentId) {
        if (rootCommentId == null) {
            throw new IllegalArgumentException("Root comment ID cannot be null.");
        }
        return repository.findById(rootCommentId.toString())
                .map(mapper::toDomain);
    }

    @Override
    @Transactional(readOnly = true)
    public List<ChapterCommentAnchor> findByChapterId(UUID chapterId) {
        if (chapterId == null) {
            throw new IllegalArgumentException("Chapter ID cannot be null.");
        }
        return repository.findByChapterId(chapterId.toString()).stream()
                .map(mapper::toDomain)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<ChapterCommentAnchor> findByRootCommentIds(Collection<UUID> rootCommentIds) {
        if (rootCommentIds == null || rootCommentIds.isEmpty()) {
            return List.of();
        }
        List<String> idStrings = rootCommentIds.stream()
                .filter(Objects::nonNull)
                .map(UUID::toString)
                .distinct()
                .toList();
        if (idStrings.isEmpty()) {
            return List.of();
        }
        return repository.findByRootCommentIdIn(idStrings).stream()
                .map(mapper::toDomain)
                .toList();
    }
}
