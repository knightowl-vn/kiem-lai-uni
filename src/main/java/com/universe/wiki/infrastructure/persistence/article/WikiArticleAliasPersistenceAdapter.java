package com.universe.wiki.infrastructure.persistence.article;

import com.universe.wiki.application.ports.WikiArticleAliasRepositoryPort;
import com.universe.wiki.contracts.dto.WikiArticleAliasDTO;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

@Component
public class WikiArticleAliasPersistenceAdapter implements WikiArticleAliasRepositoryPort {

    private final SpringDataWikiArticleAliasJpaRepository aliasJpaRepository;

    public WikiArticleAliasPersistenceAdapter(SpringDataWikiArticleAliasJpaRepository aliasJpaRepository) {
        this.aliasJpaRepository = aliasJpaRepository;
    }

    @Override
    public List<WikiArticleAliasDTO> listByArticleId(UUID articleId) {
        Objects.requireNonNull(articleId, "Article ID không được để trống.");
        return aliasJpaRepository.findAllByArticleIdOrderByCreatedAtAsc(articleId.toString())
                .stream()
                .map(this::toDTO)
                .toList();
    }

    @Override
    public Optional<WikiArticleAliasDTO> findByArticleIdAndNormalizedAlias(UUID articleId, String normalizedAlias) {
        Objects.requireNonNull(articleId, "Article ID không được để trống.");
        Objects.requireNonNull(normalizedAlias, "Normalized alias không được để trống.");
        return aliasJpaRepository.findByArticleIdAndNormalizedAlias(articleId.toString(), normalizedAlias)
                .map(this::toDTO);
    }

    @Override
    public void save(UUID id, UUID articleId, String alias, String normalizedAlias, Instant createdAt) {
        Objects.requireNonNull(id, "Alias ID không được để trống.");
        Objects.requireNonNull(articleId, "Article ID không được để trống.");
        Objects.requireNonNull(alias, "Alias không được để trống.");
        Objects.requireNonNull(normalizedAlias, "Normalized alias không được để trống.");
        Objects.requireNonNull(createdAt, "CreatedAt không được để trống.");

        WikiArticleAliasJpaEntity entity = new WikiArticleAliasJpaEntity(
                id.toString(),
                articleId.toString(),
                alias,
                normalizedAlias,
                createdAt
        );
        aliasJpaRepository.save(entity);
    }

    @Override
    public void deleteByArticleIdAndNormalizedAlias(UUID articleId, String normalizedAlias) {
        Objects.requireNonNull(articleId, "Article ID không được để trống.");
        Objects.requireNonNull(normalizedAlias, "Normalized alias không được để trống.");
        aliasJpaRepository.deleteByArticleIdAndNormalizedAlias(articleId.toString(), normalizedAlias);
    }

    @Override
    public boolean existsByArticleIdAndNormalizedAlias(UUID articleId, String normalizedAlias) {
        Objects.requireNonNull(articleId, "Article ID không được để trống.");
        Objects.requireNonNull(normalizedAlias, "Normalized alias không được để trống.");
        return aliasJpaRepository.existsByArticleIdAndNormalizedAlias(articleId.toString(), normalizedAlias);
    }

    private WikiArticleAliasDTO toDTO(WikiArticleAliasJpaEntity entity) {
        return new WikiArticleAliasDTO(
                UUID.fromString(entity.getId()),
                UUID.fromString(entity.getArticleId()),
                entity.getAlias(),
                entity.getNormalizedAlias(),
                entity.getCreatedAt()
        );
    }
}