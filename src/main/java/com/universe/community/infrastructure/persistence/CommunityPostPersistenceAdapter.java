package com.universe.community.infrastructure.persistence;

import com.universe.community.application.port.out.CommunityPostRepositoryPort;
import com.universe.community.contracts.port.CommunityPostInteractionMutationPort;
import com.universe.community.domain.CommunityPost;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Persistence adapter implementing {@link CommunityPostRepositoryPort} and
 * {@link CommunityPostInteractionMutationPort} using Spring Data JPA.
 */
@Component
@Transactional(readOnly = true)
public class CommunityPostPersistenceAdapter implements CommunityPostRepositoryPort, CommunityPostInteractionMutationPort {

    private final SpringDataCommunityPostJpaRepository postRepository;
    private final CommunityPostPersistenceMapper postMapper;

    public CommunityPostPersistenceAdapter(
            SpringDataCommunityPostJpaRepository postRepository,
            CommunityPostPersistenceMapper postMapper
    ) {
        this.postRepository = Objects.requireNonNull(postRepository, "SpringDataCommunityPostJpaRepository cannot be null.");
        this.postMapper = Objects.requireNonNull(postMapper, "CommunityPostPersistenceMapper cannot be null.");
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
                .filter(entity -> "PUBLISHED".equals(entity.getStatus()))
                .map(entity -> new CommunityPostLockedView(
                        UUID.fromString(entity.getId()),
                        UUID.fromString(entity.getAuthorUserId()),
                        entity.getCaption(),
                        entity.getImageMediaAssetId() != null ? UUID.fromString(entity.getImageMediaAssetId()) : null
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
    public List<CommunityPost> findByIdIn(Collection<UUID> postIds) {
        if (postIds == null || postIds.isEmpty()) {
            return List.of();
        }
        List<String> stringIds = postIds.stream().filter(Objects::nonNull).map(UUID::toString).toList();
        if (stringIds.isEmpty()) {
            return List.of();
        }
        return postRepository.findByIdIn(stringIds).stream()
                .map(postMapper::toDomain)
                .toList();
    }

    @Override
    public CommunityPostPage findPendingReviewPosts(int page, int size) {
        PageRequest pageRequest = PageRequest.of(page, size);
        org.springframework.data.domain.Page<CommunityPostJpaEntity> entityPage =
                postRepository.findPendingReviewPosts(pageRequest);
        List<CommunityPost> items = entityPage.getContent().stream()
                .map(postMapper::toDomain)
                .toList();
        return new CommunityPostPage(
                items,
                entityPage.getNumber(),
                entityPage.getSize(),
                entityPage.getTotalElements()
        );
    }

    @Override
    public CommunityPostPage findHiddenPosts(int page, int size) {
        PageRequest pageRequest = PageRequest.of(page, size);
        org.springframework.data.domain.Page<CommunityPostJpaEntity> entityPage =
                postRepository.findHiddenPosts(pageRequest);
        List<CommunityPost> items = entityPage.getContent().stream()
                .map(postMapper::toDomain)
                .toList();
        return new CommunityPostPage(
                items,
                entityPage.getNumber(),
                entityPage.getSize(),
                entityPage.getTotalElements()
        );
    }
}
