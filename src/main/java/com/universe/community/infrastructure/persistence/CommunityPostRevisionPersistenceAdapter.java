package com.universe.community.infrastructure.persistence;

import com.universe.community.application.port.out.CommunityPostRevisionRepositoryPort;
import com.universe.community.domain.CommunityPostRevision;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Persistence adapter implementing {@link CommunityPostRevisionRepositoryPort} using Spring Data JPA.
 */
@Component
@Transactional(readOnly = true)
public class CommunityPostRevisionPersistenceAdapter implements CommunityPostRevisionRepositoryPort {

    private final SpringDataCommunityPostRevisionJpaRepository repository;
    private final CommunityPostRevisionPersistenceMapper mapper;

    public CommunityPostRevisionPersistenceAdapter(
            SpringDataCommunityPostRevisionJpaRepository repository,
            CommunityPostRevisionPersistenceMapper mapper
    ) {
        this.repository = Objects.requireNonNull(repository, "SpringDataCommunityPostRevisionJpaRepository cannot be null.");
        this.mapper = Objects.requireNonNull(mapper, "CommunityPostRevisionPersistenceMapper cannot be null.");
    }

    @Override
    @Transactional
    public CommunityPostRevision save(CommunityPostRevision revision) {
        if (revision == null) {
            throw new IllegalArgumentException("CommunityPostRevision cannot be null.");
        }
        CommunityPostRevisionJpaEntity entity = mapper.toJpaEntity(revision);
        CommunityPostRevisionJpaEntity savedEntity = repository.saveAndFlush(entity);
        return mapper.toDomain(savedEntity);
    }

    @Override
    public List<CommunityPostRevision> findByPostIdOrderByRevisionNumberAsc(UUID postId) {
        if (postId == null) {
            throw new IllegalArgumentException("Post ID cannot be null.");
        }
        return repository.findByPostIdOrderByRevisionNumberAsc(postId.toString()).stream()
                .map(mapper::toDomain)
                .toList();
    }
}
