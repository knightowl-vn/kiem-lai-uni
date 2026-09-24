package com.universe.wiki.infrastructure.persistence.contribution;

import com.universe.wiki.application.exceptions.WikiContributionStaleMutationException;
import com.universe.wiki.application.ports.WikiContributionRepositoryPort;
import com.universe.wiki.domain.contribution.WikiContribution;
import com.universe.wiki.domain.contribution.WikiContributionContextType;
import com.universe.wiki.domain.contribution.WikiContributionStatus;
import com.universe.wiki.domain.contribution.WikiContributionType;
import jakarta.persistence.OptimisticLockException;
import org.springframework.data.domain.PageRequest;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Adapter persistence thực thi WikiContributionRepositoryPort.
 *
 * Chuyển đổi giữa domain aggregate WikiContribution và JPA Entity WikiContributionJpaEntity.
 */
@Component
public class WikiContributionPersistenceAdapter implements WikiContributionRepositoryPort {

    private final SpringDataWikiContributionJpaRepository repository;

    public WikiContributionPersistenceAdapter(SpringDataWikiContributionJpaRepository repository) {
        this.repository = Objects.requireNonNull(repository, "SpringDataWikiContributionJpaRepository không được để trống.");
    }

    @Override
    @Transactional
    public WikiContribution save(WikiContribution contribution) {
        Objects.requireNonNull(contribution, "WikiContribution không được để trống.");

        String idStr = contribution.getId().toString();
        Optional<WikiContributionJpaEntity> existingOpt = repository.findById(idStr);

        WikiContributionJpaEntity entity;
        if (existingOpt.isPresent()) {
            entity = existingOpt.get();
            long currentVersion = entity.getVersion() != null ? entity.getVersion() : 0L;
            long expectedVersion = contribution.getVersion();
            if (expectedVersion != currentVersion) {
                throw new WikiContributionStaleMutationException(
                        contribution.getId(),
                        expectedVersion,
                        currentVersion
                );
            }
            mapToEntity(contribution, entity);
        } else {
            entity = new WikiContributionJpaEntity();
            entity.setId(idStr);
            mapToEntity(contribution, entity);
        }

        try {
            WikiContributionJpaEntity savedEntity = repository.saveAndFlush(entity);
            return toDomain(savedEntity);
        } catch (ObjectOptimisticLockingFailureException | OptimisticLockException ex) {
            throw new WikiContributionStaleMutationException(contribution.getId(), ex);
        }
    }

    @Override
    public Optional<WikiContribution> findById(UUID id) {
        if (id == null) {
            return Optional.empty();
        }
        return repository.findById(id.toString()).map(this::toDomain);
    }

    @Override
    public List<WikiContribution> findRecentCandidates(
            UUID submittedByUserId,
            UUID articleId,
            Instant cutoff,
            int limit
    ) {
        if (submittedByUserId == null || articleId == null || cutoff == null || limit <= 0) {
            return List.of();
        }
        return repository.findRecentByUserIdAndArticleId(
                submittedByUserId.toString(),
                articleId.toString(),
                cutoff,
                PageRequest.of(0, limit)
        ).stream().map(this::toDomain).toList();
    }

    private void mapToEntity(WikiContribution source, WikiContributionJpaEntity target) {
        target.setArticleId(source.getArticleId().toString());
        target.setArticleTypeSnapshot(source.getArticleTypeSnapshot());
        target.setArticleTitleSnapshot(source.getArticleTitleSnapshot());
        target.setArticleSlugSnapshot(source.getArticleSlugSnapshot());
        target.setArticleContentVersion(source.getArticleContentVersion());
        target.setSubmittedByUserId(source.getSubmittedByUserId().toString());
        target.setContextType(source.getContextType().name());
        target.setContributionType(source.getContributionType().name());
        target.setMessage(source.getMessage());
        target.setSelectedText(source.getSelectedText());
        target.setSelectedPrefix(source.getSelectedPrefix());
        target.setSelectedSuffix(source.getSelectedSuffix());
        target.setSelectedHeadingAnchor(source.getSelectedHeadingAnchor());
        target.setStatus(source.getStatus().name());
        target.setCreatedAt(source.getCreatedAt());
        target.setUpdatedAt(source.getUpdatedAt());
    }

    private WikiContribution toDomain(WikiContributionJpaEntity entity) {
        return WikiContribution.reconstitute(
                UUID.fromString(entity.getId()),
                UUID.fromString(entity.getArticleId()),
                entity.getArticleTypeSnapshot(),
                entity.getArticleTitleSnapshot(),
                entity.getArticleSlugSnapshot(),
                entity.getArticleContentVersion(),
                UUID.fromString(entity.getSubmittedByUserId()),
                WikiContributionContextType.valueOf(entity.getContextType()),
                WikiContributionType.valueOf(entity.getContributionType()),
                entity.getMessage(),
                entity.getSelectedText(),
                entity.getSelectedPrefix(),
                entity.getSelectedSuffix(),
                entity.getSelectedHeadingAnchor(),
                WikiContributionStatus.valueOf(entity.getStatus()),
                entity.getVersion() != null ? entity.getVersion() : 0L,
                entity.getCreatedAt(),
                entity.getUpdatedAt()
        );
    }
}
