package com.universe.community.infrastructure.persistence;

import com.universe.community.application.port.out.CommunityPostModerationEventRepositoryPort;
import com.universe.community.domain.moderation.CommunityPostModerationEvent;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Persistence adapter implementing {@link CommunityPostModerationEventRepositoryPort} using Spring Data JPA.
 */
@Component
@Transactional(readOnly = true)
public class CommunityPostModerationEventPersistenceAdapter implements CommunityPostModerationEventRepositoryPort {

    private final SpringDataCommunityPostModerationEventJpaRepository eventRepository;
    private final CommunityPostModerationEventPersistenceMapper eventMapper;

    public CommunityPostModerationEventPersistenceAdapter(
            SpringDataCommunityPostModerationEventJpaRepository eventRepository,
            CommunityPostModerationEventPersistenceMapper eventMapper
    ) {
        this.eventRepository = Objects.requireNonNull(eventRepository, "SpringDataCommunityPostModerationEventJpaRepository cannot be null.");
        this.eventMapper = Objects.requireNonNull(eventMapper, "CommunityPostModerationEventPersistenceMapper cannot be null.");
    }

    @Override
    @Transactional
    public CommunityPostModerationEvent save(CommunityPostModerationEvent event) {
        if (event == null) {
            throw new IllegalArgumentException("CommunityPostModerationEvent cannot be null.");
        }
        CommunityPostModerationEventJpaEntity entity = eventMapper.toJpaEntity(event);
        CommunityPostModerationEventJpaEntity saved = eventRepository.saveAndFlush(entity);
        return eventMapper.toDomain(saved);
    }

    @Override
    public List<CommunityPostModerationEvent> findByPostIdOrderByCreatedAtAsc(UUID postId) {
        if (postId == null) {
            throw new IllegalArgumentException("Post ID cannot be null.");
        }
        return eventRepository.findByPostIdOrderByCreatedAtAsc(postId.toString()).stream()
                .map(eventMapper::toDomain)
                .toList();
    }
}
