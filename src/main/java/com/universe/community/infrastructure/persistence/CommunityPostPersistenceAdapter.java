package com.universe.community.infrastructure.persistence;

import com.universe.community.application.port.out.CommunityPostRepositoryPort;
import com.universe.community.contracts.dto.CommunityPostPublicDTO;
import com.universe.community.contracts.dto.CommunityPostRevisionPublicDTO;
import com.universe.community.contracts.port.CommunityPostInteractionMutationPort;
import com.universe.community.contracts.port.CommunityPostQueryPort;
import com.universe.community.domain.CommunityPost;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Persistence adapter implementing {@link CommunityPostRepositoryPort}, {@link CommunityPostQueryPort},
 * and {@link CommunityPostInteractionMutationPort} using Spring Data JPA.
 */
@Component
@Transactional(readOnly = true)
public class CommunityPostPersistenceAdapter implements CommunityPostRepositoryPort, CommunityPostQueryPort, CommunityPostInteractionMutationPort {

    private final SpringDataCommunityPostJpaRepository postRepository;
    private final SpringDataCommunityPostRevisionJpaRepository revisionRepository;
    private final CommunityPostPersistenceMapper postMapper;
    private final CommunityPostRevisionPersistenceMapper revisionMapper;

    public CommunityPostPersistenceAdapter(
            SpringDataCommunityPostJpaRepository postRepository,
            SpringDataCommunityPostRevisionJpaRepository revisionRepository,
            CommunityPostPersistenceMapper postMapper,
            CommunityPostRevisionPersistenceMapper revisionMapper
    ) {
        this.postRepository = Objects.requireNonNull(postRepository, "SpringDataCommunityPostJpaRepository cannot be null.");
        this.revisionRepository = Objects.requireNonNull(revisionRepository, "SpringDataCommunityPostRevisionJpaRepository cannot be null.");
        this.postMapper = Objects.requireNonNull(postMapper, "CommunityPostPersistenceMapper cannot be null.");
        this.revisionMapper = Objects.requireNonNull(revisionMapper, "CommunityPostRevisionPersistenceMapper cannot be null.");
    }

    @Override
    @Transactional
    public CommunityPost save(CommunityPost post) {
        if (post == null) {
            throw new IllegalArgumentException("CommunityPost cannot be null.");
        }
        CommunityPostJpaEntity entity = postMapper.toJpaEntity(post);
        CommunityPostJpaEntity savedEntity = postRepository.saveAndFlush(entity);
        return postMapper.toDomain(savedEntity);
    }

    @Override
    public Optional<CommunityPost> findById(UUID postId) {
        if (postId == null) {
            throw new IllegalArgumentException("Post ID cannot be null.");
        }
        return postRepository.findById(postId.toString()).map(postMapper::toDomain);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<CommunityPost> findByIdForUpdate(UUID postId) {
        if (postId == null) {
            throw new IllegalArgumentException("Post ID cannot be null.");
        }
        return postRepository.findByIdForUpdate(postId.toString()).map(postMapper::toDomain);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<CommunityPostLockedView> lockExistingPostForInteraction(UUID postId) {
        if (postId == null) {
            throw new IllegalArgumentException("Post ID cannot be null.");
        }
        return postRepository.findByIdForUpdate(postId.toString())
                .map(entity -> new CommunityPostLockedView(
                        UUID.fromString(entity.getId()),
                        UUID.fromString(entity.getAuthorUserId()),
                        entity.getCaption()
                ));
    }

    @Override
    public boolean existsById(UUID postId) {
        if (postId == null) {
            throw new IllegalArgumentException("Post ID cannot be null.");
        }
        return postRepository.existsById(postId.toString());
    }

    @Override
    @Transactional
    public void deleteById(UUID postId) {
        if (postId == null) {
            throw new IllegalArgumentException("Post ID cannot be null.");
        }
        postRepository.deleteById(postId.toString());
    }

    @Override
    public Optional<CommunityPostPublicDTO> findPublicPostById(UUID postId) {
        if (postId == null) {
            throw new IllegalArgumentException("Post ID cannot be null.");
        }
        return postRepository.findById(postId.toString()).map(postMapper::toPublicDTO);
    }

    @Override
    public List<CommunityPostRevisionPublicDTO> findPublicRevisionHistory(UUID postId) {
        if (postId == null) {
            throw new IllegalArgumentException("Post ID cannot be null.");
        }
        return revisionRepository.findByPostIdOrderByRevisionNumberAsc(postId.toString()).stream()
                .map(revisionMapper::toPublicDTO)
                .toList();
    }
}
