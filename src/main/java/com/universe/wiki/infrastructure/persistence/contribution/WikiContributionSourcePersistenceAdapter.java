package com.universe.wiki.infrastructure.persistence.contribution;

import com.universe.wiki.application.ports.WikiContributionSourceRepositoryPort;
import com.universe.wiki.domain.contribution.WikiContributionSource;
import com.universe.wiki.domain.contribution.WikiContributionSourceType;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Adapter persistence thực thi WikiContributionSourceRepositoryPort.
 *
 * Chuyển đổi giữa domain entity WikiContributionSource và JPA Entity WikiContributionSourceJpaEntity.
 */
@Component
public class WikiContributionSourcePersistenceAdapter implements WikiContributionSourceRepositoryPort {

    private final SpringDataWikiContributionSourceJpaRepository repository;

    public WikiContributionSourcePersistenceAdapter(SpringDataWikiContributionSourceJpaRepository repository) {
        this.repository = Objects.requireNonNull(repository, "SpringDataWikiContributionSourceJpaRepository không được để trống.");
    }

    @Override
    @Transactional
    public WikiContributionSource save(WikiContributionSource source) {
        Objects.requireNonNull(source, "WikiContributionSource không được để trống.");
        WikiContributionSourceJpaEntity entity = toEntity(source);
        WikiContributionSourceJpaEntity saved = repository.saveAndFlush(entity);
        return toDomain(saved);
    }

    @Override
    @Transactional
    public List<WikiContributionSource> saveAll(List<WikiContributionSource> sources) {
        if (sources == null || sources.isEmpty()) {
            return Collections.emptyList();
        }
        List<WikiContributionSourceJpaEntity> entities = sources.stream()
                .map(this::toEntity)
                .toList();
        List<WikiContributionSourceJpaEntity> savedEntities = repository.saveAllAndFlush(entities);
        return savedEntities.stream()
                .map(this::toDomain)
                .toList();
    }

    @Override
    public List<WikiContributionSource> findByContributionId(UUID contributionId) {
        if (contributionId == null) {
            return Collections.emptyList();
        }
        return repository.findByContributionIdOrderBySourceOrderAsc(contributionId.toString())
                .stream()
                .map(this::toDomain)
                .toList();
    }

    private WikiContributionSourceJpaEntity toEntity(WikiContributionSource source) {
        return new WikiContributionSourceJpaEntity(
                source.getId().toString(),
                source.getContributionId().toString(),
                source.getSourceOrder(),
                source.getSourceType().name(),
                source.getUrl(),
                source.getCreatedAt()
        );
    }

    private WikiContributionSource toDomain(WikiContributionSourceJpaEntity entity) {
        return WikiContributionSource.reconstitute(
                UUID.fromString(entity.getId()),
                UUID.fromString(entity.getContributionId()),
                entity.getSourceOrder(),
                WikiContributionSourceType.valueOf(entity.getSourceType()),
                entity.getUrl(),
                entity.getCreatedAt()
        );
    }
}
