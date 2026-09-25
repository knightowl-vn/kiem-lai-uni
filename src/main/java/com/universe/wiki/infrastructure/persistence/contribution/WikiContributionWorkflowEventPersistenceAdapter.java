package com.universe.wiki.infrastructure.persistence.contribution;

import com.universe.wiki.application.ports.WikiContributionWorkflowEventRepositoryPort;
import com.universe.wiki.domain.contribution.WikiContributionEventType;
import com.universe.wiki.domain.contribution.WikiContributionResolutionOutcome;
import com.universe.wiki.domain.contribution.WikiContributionStatus;
import com.universe.wiki.domain.contribution.WikiContributionWorkflowEvent;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

@Component
public class WikiContributionWorkflowEventPersistenceAdapter
        implements WikiContributionWorkflowEventRepositoryPort {

    private final SpringDataWikiContributionWorkflowEventJpaRepository repository;

    public WikiContributionWorkflowEventPersistenceAdapter(
            SpringDataWikiContributionWorkflowEventJpaRepository repository
    ) {
        this.repository = Objects.requireNonNull(repository, "Repository không được để trống.");
    }

    @Override
    public void save(WikiContributionWorkflowEvent event) {
        Objects.requireNonNull(event, "Sự kiện quy trình không được để trống.");
        repository.save(toEntity(event));
    }

    @Override
    public List<WikiContributionWorkflowEvent> findByContributionId(UUID contributionId) {
        if (contributionId == null) {
            return List.of();
        }
        return repository
                .findByContributionIdOrderByCreatedAtAscIdAsc(contributionId.toString())
                .stream()
                .map(this::toDomain)
                .toList();
    }

    private WikiContributionWorkflowEventJpaEntity toEntity(WikiContributionWorkflowEvent event) {
        WikiContributionWorkflowEventJpaEntity entity = new WikiContributionWorkflowEventJpaEntity();
        entity.setId(event.id().toString());
        entity.setContributionId(event.contributionId().toString());
        entity.setEventType(event.eventType().name());
        entity.setActorUserId(event.actorUserId().toString());
        entity.setTargetUserId(event.targetUserId() != null ? event.targetUserId().toString() : null);
        entity.setFromStatus(event.fromStatus() != null ? event.fromStatus().name() : null);
        entity.setToStatus(event.toStatus() != null ? event.toStatus().name() : null);
        entity.setArticleContentVersion(event.articleContentVersion());
        entity.setResolutionOutcome(event.resolutionOutcome() != null ? event.resolutionOutcome().name() : null);
        entity.setNote(event.note());
        entity.setCreatedAt(event.createdAt());
        return entity;
    }

    private WikiContributionWorkflowEvent toDomain(WikiContributionWorkflowEventJpaEntity entity) {
        return new WikiContributionWorkflowEvent(
                UUID.fromString(entity.getId()),
                UUID.fromString(entity.getContributionId()),
                WikiContributionEventType.valueOf(entity.getEventType()),
                UUID.fromString(entity.getActorUserId()),
                entity.getTargetUserId() != null ? UUID.fromString(entity.getTargetUserId()) : null,
                entity.getFromStatus() != null ? WikiContributionStatus.valueOf(entity.getFromStatus()) : null,
                entity.getToStatus() != null ? WikiContributionStatus.valueOf(entity.getToStatus()) : null,
                entity.getArticleContentVersion(),
                entity.getResolutionOutcome() != null ? WikiContributionResolutionOutcome.valueOf(entity.getResolutionOutcome()) : null,
                entity.getNote(),
                entity.getCreatedAt()
        );
    }
}
