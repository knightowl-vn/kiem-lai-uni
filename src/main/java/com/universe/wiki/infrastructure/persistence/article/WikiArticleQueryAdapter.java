package com.universe.wiki.infrastructure.persistence.article;

import com.universe.wiki.application.ports.WikiArticleQueryPort;
import com.universe.wiki.contracts.dto.WikiArticleDTO;
import com.universe.wiki.contracts.dto.WikiArticleListItemDTO;
import com.universe.wiki.contracts.dto.WikiArticlePageDTO;
import com.universe.wiki.contracts.dto.PublishedWikiArticleDTO;
import com.universe.wiki.contracts.dto.PublishedWikiArticleListItemDTO;
import com.universe.wiki.contracts.dto.PublishedWikiArticlePageDTO;
import com.universe.wiki.domain.article.Slug;
import com.universe.wiki.domain.article.ArticleStatus;
import com.universe.wiki.domain.article.ArticleType;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Component
public class WikiArticleQueryAdapter
        implements WikiArticleQueryPort {

    private final SpringDataWikiArticleJpaRepository
            repository;

    public WikiArticleQueryAdapter(
            SpringDataWikiArticleJpaRepository repository
    ) {
        this.repository =
                repository;
    }

    @Override
    public Optional<WikiArticleDTO> findDetailById(
            UUID articleId
    ) {
        if (articleId == null) {
            return Optional.empty();
        }

        return repository
                .findById(
                        articleId.toString()
                )
                .map(this::toDTO);
    }
    @Override
    public Optional<PublishedWikiArticleDTO>
            findPublishedByArticleTypeAndSlug(
                    ArticleType articleType,
                    Slug slug
            ) {

        if (articleType == null
                || slug == null) {

            return Optional.empty();
        }

        return repository
                .findByArticleTypeAndSlugAndStatus(
                        articleType.name(),
                        slug.value(),
                        ArticleStatus.PUBLISHED.name()
                )
                .map(this::toPublishedDTO);
    }
    
    @Override
    public PublishedWikiArticlePageDTO findPublishedPage(
            String keyword,
            ArticleType articleType,
            int page,
            int size
    ) {
        Pageable pageable =
                PageRequest.of(
                        page,
                        size,
                        Sort.by(
                                Sort.Direction.DESC,
                                "updatedAt"
                        ).and(
                                Sort.by(
                                        Sort.Direction.DESC,
                                        "publishedAt"
                                )
                        )
                );

        String articleTypeValue =
                articleType == null
                        ? null
                        : articleType.name();

        Page<WikiArticleJpaEntity> entityPage =
                repository.findPublishedPage(
                        keyword,
                        articleTypeValue,
                        ArticleStatus.PUBLISHED.name(),
                        pageable
                );

        List<PublishedWikiArticleListItemDTO> items =
                entityPage
                        .getContent()
                        .stream()
                        .map(this::toPublishedListItemDTO)
                        .toList();

        return new PublishedWikiArticlePageDTO(
                items,
                entityPage.getNumber(),
                entityPage.getSize(),
                entityPage.getTotalElements(),
                entityPage.getTotalPages(),
                entityPage.isFirst(),
                entityPage.isLast()
        );
    }

    @Override
    public WikiArticlePageDTO findPage(
            String keyword,
            ArticleType articleType,
            ArticleStatus status,
            int page,
            int size
    ) {
        Pageable pageable =
                PageRequest.of(
                        page,
                        size,
                        Sort.by(
                                Sort.Direction.DESC,
                                "updatedAt"
                        ).and(
                                Sort.by(
                                        Sort.Direction.DESC,
                                        "createdAt"
                                )
                        )
                );

        String articleTypeValue =
                articleType == null
                        ? null
                        : articleType.name();

        String statusValue =
                status == null
                        ? null
                        : status.name();

        Page<WikiArticleJpaEntity> entityPage =
                repository.findPage(
                        keyword,
                        articleTypeValue,
                        statusValue,
                        pageable
                );

        List<WikiArticleListItemDTO> items =
                entityPage
                        .getContent()
                        .stream()
                        .map(this::toListItemDTO)
                        .toList();

        return new WikiArticlePageDTO(
                items,
                entityPage.getNumber(),
                entityPage.getSize(),
                entityPage.getTotalElements(),
                entityPage.getTotalPages(),
                entityPage.isFirst(),
                entityPage.isLast()
        );
    }

    @Override
    public List<PublishedWikiArticleListItemDTO> findPublishedContextualMatches(
            String query,
            int maxResults
    ) {
        if (query == null || query.isBlank() || maxResults <= 0) {
            return List.of();
        }

        String rawQuery = query.trim();
        String escapedQuery = escapeLikeWildcards(rawQuery);

        Pageable pageable = PageRequest.of(0, maxResults);
        List<WikiArticleJpaEntity> entities = repository.findPublishedContextualMatches(
                rawQuery,
                escapedQuery,
                pageable
        );

        return entities.stream()
                .map(this::toPublishedListItemDTO)
                .toList();
    }

    @Override
    public List<PublishedWikiArticleListItemDTO> findPublishedArticlesByNormalizedAlias(
            String normalizedAlias,
            int maxResults
    ) {
        if (normalizedAlias == null || normalizedAlias.isBlank() || maxResults <= 0) {
            return List.of();
        }

        Pageable pageable = PageRequest.of(0, maxResults);
        List<WikiArticleJpaEntity> entities = repository.findPublishedArticlesByNormalizedAlias(
                normalizedAlias.trim(),
                pageable
        );

        return entities.stream()
                .map(this::toPublishedListItemDTO)
                .toList();
    }

    private String escapeLikeWildcards(String input) {
        if (input == null) {
            return "";
        }
        return input
                .replace("\\", "\\\\")
                .replace("%", "\\%")
                .replace("_", "\\_");
    }
    private PublishedWikiArticleDTO toPublishedDTO(
            WikiArticleJpaEntity entity
    ) {
        return new PublishedWikiArticleDTO(
                UUID.fromString(
                        entity.getId()
                ),
                entity.getTitle(),
                entity.getSlug(),
                entity.getArticleType(),
                entity.getSummary(),
                entity.getContent(),
                entity.getPublishedAt(),
                entity.getUpdatedAt()
        );
    }
    private PublishedWikiArticleListItemDTO
    toPublishedListItemDTO(
            WikiArticleJpaEntity entity
    ) {

return new PublishedWikiArticleListItemDTO(
        UUID.fromString(
                entity.getId()
        ),
        entity.getTitle(),
        entity.getSlug(),
        entity.getArticleType(),
        entity.getSummary(),
        entity.getPublishedAt(),
        entity.getUpdatedAt()
);
}

    private WikiArticleDTO toDTO(
            WikiArticleJpaEntity entity
    ) {
        return new WikiArticleDTO(
                UUID.fromString(
                        entity.getId()
                ),
                entity.getTitle(),
                entity.getSlug(),
                entity.getArticleType(),
                entity.getSummary(),
                entity.getContent(),
                entity.getStatus(),
                UUID.fromString(
                        entity.getCreatedBy()
                ),
                toNullableUuid(
                        entity.getUpdatedBy()
                ),
                toNullableUuid(
                        entity.getPublishedBy()
                ),
                toNullableUuid(
                        entity.getArchivedBy()
                ),
                entity.getCreatedAt(),
                entity.getUpdatedAt(),
                entity.getPublishedAt(),
                entity.getArchivedAt(),
                entity.getAggregateVersion(),
                entity.getContentVersion()
        );
    }

    private WikiArticleListItemDTO toListItemDTO(
            WikiArticleJpaEntity entity
    ) {
        return new WikiArticleListItemDTO(
                UUID.fromString(
                        entity.getId()
                ),
                entity.getTitle(),
                entity.getSlug(),
                entity.getArticleType(),
                entity.getStatus(),
                toNullableUuid(
                        entity.getUpdatedBy()
                ),
                entity.getCreatedAt(),
                entity.getUpdatedAt(),
                entity.getContentVersion()
        );
    }

    private UUID toNullableUuid(
            String value
    ) {
        if (value == null
                || value.isBlank()) {

            return null;
        }

        return UUID.fromString(value);
    }
}