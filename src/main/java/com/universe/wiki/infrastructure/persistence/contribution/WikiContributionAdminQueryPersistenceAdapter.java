package com.universe.wiki.infrastructure.persistence.contribution;

import com.universe.wiki.application.contribution.query.WikiContributionAdminFilter;
import com.universe.wiki.application.contribution.query.WikiContributionAdminItem;
import com.universe.wiki.application.contribution.query.WikiContributionAdminPage;
import com.universe.wiki.application.ports.WikiContributionAdminQueryPort;
import com.universe.wiki.domain.contribution.WikiContributionContextType;
import com.universe.wiki.domain.contribution.WikiContributionStatus;
import com.universe.wiki.domain.contribution.WikiContributionType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Persistence adapter implementing WikiContributionAdminQueryPort using Spring Data JPA.
 *
 * <p>Enforces deterministic pagination (createdAt DESC, id DESC) and eliminates N+1 queries
 * for source references by resolving source counts via a single bulk count query.
 */
@Component
public class WikiContributionAdminQueryPersistenceAdapter implements WikiContributionAdminQueryPort {

    private final SpringDataWikiContributionJpaRepository contributionRepository;
    private final SpringDataWikiContributionSourceJpaRepository sourceRepository;

    public WikiContributionAdminQueryPersistenceAdapter(
            SpringDataWikiContributionJpaRepository contributionRepository,
            SpringDataWikiContributionSourceJpaRepository sourceRepository
    ) {
        this.contributionRepository = Objects.requireNonNull(contributionRepository, "contributionRepository cannot be null");
        this.sourceRepository = Objects.requireNonNull(sourceRepository, "sourceRepository cannot be null");
    }

    @Override
    @Transactional(readOnly = true)
    public WikiContributionAdminPage findAdminInboxPage(WikiContributionAdminFilter filter) {
        Objects.requireNonNull(filter, "Filter cannot be null");

        Pageable pageable = PageRequest.of(
                filter.page(),
                filter.size(),
                Sort.by(Sort.Direction.DESC, "createdAt")
                        .and(Sort.by(Sort.Direction.DESC, "id"))
        );

        String statusParam = filter.status() != null ? filter.status().name() : null;
        String typeParam = filter.contributionType() != null ? filter.contributionType().name() : null;
        String keywordParam = filter.keyword();

        Page<WikiContributionAdminInboxProjection> projectionPage = contributionRepository.findAdminInboxPage(
                statusParam,
                typeParam,
                keywordParam,
                pageable
        );

        if (projectionPage.isEmpty()) {
            return new WikiContributionAdminPage(
                    List.of(),
                    projectionPage.getNumber(),
                    projectionPage.getSize(),
                    projectionPage.getTotalElements(),
                    projectionPage.getTotalPages(),
                    projectionPage.isFirst(),
                    projectionPage.isLast()
            );
        }

        List<WikiContributionAdminInboxProjection> projections = projectionPage.getContent();
        List<String> contributionIds = projections.stream()
                .map(WikiContributionAdminInboxProjection::getId)
                .toList();

        Map<String, Integer> sourceCountMap = new HashMap<>();
        List<Object[]> sourceCountRows = sourceRepository.countSourcesByContributionIds(contributionIds);
        for (Object[] row : sourceCountRows) {
            String cId = (String) row[0];
            int count = ((Number) row[1]).intValue();
            sourceCountMap.put(cId, count);
        }

        List<WikiContributionAdminItem> items = new ArrayList<>(projections.size());
        for (WikiContributionAdminInboxProjection p : projections) {
            int sourceCount = sourceCountMap.getOrDefault(p.getId(), 0);
            boolean hasSources = sourceCount > 0;
            String preview = p.getMessagePreview() != null ? p.getMessagePreview().trim() : "";

            UUID assignedToUserId = p.getAssignedToUserId() != null ? UUID.fromString(p.getAssignedToUserId()) : null;

            WikiContributionAdminItem item = new WikiContributionAdminItem(
                    UUID.fromString(p.getId()),
                    WikiContributionStatus.valueOf(p.getStatus()),
                    WikiContributionType.valueOf(p.getContributionType()),
                    WikiContributionContextType.valueOf(p.getContextType()),
                    UUID.fromString(p.getArticleId()),
                    p.getArticleTypeSnapshot(),
                    p.getArticleTitleSnapshot(),
                    p.getArticleSlugSnapshot(),
                    p.getArticleContentVersion(),
                    UUID.fromString(p.getSubmittedByUserId()),
                    assignedToUserId,
                    preview,
                    p.getHasSelectedText(),
                    hasSources,
                    sourceCount,
                    p.getCreatedAt()
            );
            items.add(item);
        }

        return new WikiContributionAdminPage(
                items,
                projectionPage.getNumber(),
                projectionPage.getSize(),
                projectionPage.getTotalElements(),
                projectionPage.getTotalPages(),
                projectionPage.isFirst(),
                projectionPage.isLast()
        );
    }

    @Override
    @Transactional(readOnly = true)
    public long countByStatus(WikiContributionStatus status) {
        Objects.requireNonNull(status, "status cannot be null");
        return contributionRepository.countByStatus(status.name());
    }
}
