package com.universe.community.infrastructure.persistence;

import com.universe.community.contracts.dto.CommunityPostPublicDTO;
import com.universe.community.contracts.dto.CommunityPostRankingCandidateDTO;
import com.universe.community.contracts.dto.CommunityPostRevisionPublicDTO;
import com.universe.community.contracts.port.CommunityPostQueryPort;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Public read/query infrastructure adapter implementing {@link CommunityPostQueryPort}.
 */
@Component
@Transactional(readOnly = true)
public class CommunityPostQueryAdapter implements CommunityPostQueryPort {

    private final SpringDataCommunityPostJpaRepository postRepository;
    private final SpringDataCommunityPostRevisionJpaRepository revisionRepository;
    private final CommunityPostPersistenceMapper postMapper;
    private final CommunityPostRevisionPersistenceMapper revisionMapper;

    public CommunityPostQueryAdapter(
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
        return revisionRepository.findByPostIdOrderByRevisionNumberDesc(postId.toString()).stream()
                .map(revisionMapper::toPublicDTO)
                .toList();
    }

    @Override
    public List<CommunityPostPublicDTO> findNewestPostsKeyset(
            Instant cursorCreatedAt,
            UUID cursorPostId,
            int limit
    ) {
        if (limit <= 0) {
            throw new IllegalArgumentException("Limit must be greater than zero: " + limit);
        }

        Pageable pageable = PageRequest.of(0, limit);
        List<CommunityPostJpaEntity> entities;

        if (cursorCreatedAt == null && cursorPostId == null) {
            entities = postRepository.findNewestPostsFirstPage(pageable);
        } else if (cursorCreatedAt != null && cursorPostId != null) {
            entities = postRepository.findNewestPostsAfterCursor(
                    cursorCreatedAt,
                    cursorPostId.toString(),
                    pageable
            );
        } else {
            throw new IllegalArgumentException("Cursor requires both cursorCreatedAt and cursorPostId, or both to be null.");
        }

        return entities.stream()
                .map(postMapper::toPublicDTO)
                .toList();
    }

    @Override
    public List<CommunityPostPublicDTO> findAuthoredPostsKeyset(
            UUID authorUserId,
            Instant cursorCreatedAt,
            UUID cursorPostId,
            int limit
    ) {
        if (authorUserId == null) {
            throw new IllegalArgumentException("Author user ID cannot be null.");
        }
        if (limit <= 0) {
            throw new IllegalArgumentException("Limit must be greater than zero: " + limit);
        }

        Pageable pageable = PageRequest.of(0, limit);
        List<CommunityPostJpaEntity> entities;

        if (cursorCreatedAt == null && cursorPostId == null) {
            entities = postRepository.findAuthoredPostsFirstPage(authorUserId.toString(), pageable);
        } else if (cursorCreatedAt != null && cursorPostId != null) {
            entities = postRepository.findAuthoredPostsAfterCursor(
                    authorUserId.toString(),
                    cursorCreatedAt,
                    cursorPostId.toString(),
                    pageable
            );
        } else {
            throw new IllegalArgumentException("Cursor requires both cursorCreatedAt and cursorPostId, or both to be null.");
        }

        return entities.stream()
                .map(postMapper::toPublicDTO)
                .toList();
    }


    @Override
    public List<CommunityPostRankingCandidateDTO> findAllRankingCandidates() {
        return postRepository.findAllRankingCandidates().stream()
                .map(proj -> new CommunityPostRankingCandidateDTO(
                        UUID.fromString(proj.getId()),
                        proj.getCreatedAt()
                ))
                .toList();
    }

    @Override
    public List<CommunityPostPublicDTO> findPublicPostsByIds(Collection<UUID> postIds) {
        if (postIds == null || postIds.isEmpty()) {
            return List.of();
        }
        List<String> idStrings = postIds.stream().map(UUID::toString).toList();
        return postRepository.findByIdIn(idStrings).stream()
                .map(postMapper::toPublicDTO)
                .toList();
    }
}
