package com.universe.wiki.application.ports;

import com.universe.wiki.contracts.dto.WikiArticleAliasDTO;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface WikiArticleAliasRepositoryPort {

    List<WikiArticleAliasDTO> listByArticleId(UUID articleId);

    Optional<WikiArticleAliasDTO> findByArticleIdAndNormalizedAlias(UUID articleId, String normalizedAlias);

    void save(UUID id, UUID articleId, String alias, String normalizedAlias, Instant createdAt);

    void deleteByArticleIdAndNormalizedAlias(UUID articleId, String normalizedAlias);

    boolean existsByArticleIdAndNormalizedAlias(UUID articleId, String normalizedAlias);
}