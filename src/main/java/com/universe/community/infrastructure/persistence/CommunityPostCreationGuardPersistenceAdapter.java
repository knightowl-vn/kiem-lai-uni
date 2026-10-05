package com.universe.community.infrastructure.persistence;

import com.universe.community.application.port.out.CommunityPostCreationGuardRepositoryPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Persistence adapter implementing {@link CommunityPostCreationGuardRepositoryPort}.
 * Bridges domain/application creation guard operations to MySQL InnoDB tables.
 */
@Component
public class CommunityPostCreationGuardPersistenceAdapter implements CommunityPostCreationGuardRepositoryPort {

    private final SpringDataCommunityPostCreationGuardJpaRepository guardRepository;
    private final SpringDataCommunityPostCreationEventJpaRepository eventRepository;
    private final JdbcTemplate jdbcTemplate;

    public CommunityPostCreationGuardPersistenceAdapter(
            SpringDataCommunityPostCreationGuardJpaRepository guardRepository,
            SpringDataCommunityPostCreationEventJpaRepository eventRepository,
            JdbcTemplate jdbcTemplate
    ) {
        this.guardRepository = Objects.requireNonNull(guardRepository, "SpringDataCommunityPostCreationGuardJpaRepository cannot be null.");
        this.eventRepository = Objects.requireNonNull(eventRepository, "SpringDataCommunityPostCreationEventJpaRepository cannot be null.");
        this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "JdbcTemplate cannot be null.");
    }

    @Override
    public void acquireAuthorLock(UUID authorUserId) {
        Objects.requireNonNull(authorUserId, "Author user ID cannot be null.");
        String authorIdStr = authorUserId.toString();

        // Ensure guard row exists for this author
        jdbcTemplate.update(
                "INSERT INTO community_post_creation_guard (author_user_id) VALUES (?) " +
                "ON DUPLICATE KEY UPDATE author_user_id = author_user_id",
                authorIdStr
        );

        // Lock author's row with SELECT ... FOR UPDATE
        guardRepository.findByAuthorUserIdForUpdate(authorIdStr);
    }

    @Override
    public Optional<Instant> findLatestCreationTimestamp(UUID authorUserId) {
        Objects.requireNonNull(authorUserId, "Author user ID cannot be null.");
        return eventRepository.findLatestCreationTimestamp(authorUserId.toString());
    }

    @Override
    public long countCreationsAfter(UUID authorUserId, Instant cutoff) {
        Objects.requireNonNull(authorUserId, "Author user ID cannot be null.");
        Objects.requireNonNull(cutoff, "Cutoff timestamp cannot be null.");
        return eventRepository.countCreationsAfter(authorUserId.toString(), cutoff);
    }

    @Override
    public Optional<Instant> findOldestCreationTimestampAfter(UUID authorUserId, Instant cutoff) {
        Objects.requireNonNull(authorUserId, "Author user ID cannot be null.");
        Objects.requireNonNull(cutoff, "Cutoff timestamp cannot be null.");
        return eventRepository.findOldestCreationTimestampAfter(authorUserId.toString(), cutoff);
    }

    @Override
    public Optional<Instant> findLatestMatchingCaptionCreation(UUID authorUserId, String normalizedCaptionHash, Instant cutoff) {
        Objects.requireNonNull(authorUserId, "Author user ID cannot be null.");
        Objects.requireNonNull(normalizedCaptionHash, "Normalized caption hash cannot be null.");
        Objects.requireNonNull(cutoff, "Cutoff timestamp cannot be null.");
        return eventRepository.findLatestMatchingCaptionCreation(authorUserId.toString(), normalizedCaptionHash, cutoff);
    }

    @Override
    public void appendCreationEvent(
            UUID eventId,
            UUID authorUserId,
            UUID postId,
            String normalizedCaptionHash,
            Instant createdAt
    ) {
        Objects.requireNonNull(eventId, "Event ID cannot be null.");
        Objects.requireNonNull(authorUserId, "Author user ID cannot be null.");
        Objects.requireNonNull(postId, "Post ID cannot be null.");
        Objects.requireNonNull(normalizedCaptionHash, "Normalized caption hash cannot be null.");
        Objects.requireNonNull(createdAt, "CreatedAt timestamp cannot be null.");

        CommunityPostCreationEventJpaEntity entity = new CommunityPostCreationEventJpaEntity(
                eventId.toString(),
                authorUserId.toString(),
                postId.toString(),
                normalizedCaptionHash,
                createdAt
        );
        eventRepository.save(entity);
    }
}
