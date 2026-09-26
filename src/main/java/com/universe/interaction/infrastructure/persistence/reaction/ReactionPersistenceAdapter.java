package com.universe.interaction.infrastructure.persistence.reaction;

import com.universe.interaction.application.exceptions.DuplicateReactionException;
import com.universe.interaction.application.ports.ReactionRepositoryPort;
import com.universe.interaction.domain.reaction.Reaction;
import com.universe.interaction.domain.reaction.ReactionTarget;
import com.universe.interaction.domain.reaction.ReactionTargetType;
import com.universe.interaction.domain.reaction.ReactionType;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Persistence adapter implementing {@link ReactionRepositoryPort} using Spring Data JPA.
 */
@Component
@Transactional(readOnly = true)
public class ReactionPersistenceAdapter implements ReactionRepositoryPort {

    private static final String TARGET_UNIQUE_CONSTRAINT = "uq_interaction_reactions_user_target";

    private final SpringDataReactionRepository repository;
    private final ReactionPersistenceMapper mapper;

    public ReactionPersistenceAdapter(
            SpringDataReactionRepository repository,
            ReactionPersistenceMapper mapper
    ) {
        this.repository = Objects.requireNonNull(repository, "SpringDataReactionRepository cannot be null.");
        this.mapper = Objects.requireNonNull(mapper, "ReactionPersistenceMapper cannot be null.");
    }

    @Override
    @Transactional
    public Reaction save(Reaction reaction) {
        if (reaction == null) {
            throw new IllegalArgumentException("Reaction cannot be null.");
        }

        ReactionJpaEntity entity = mapper.toJpaEntity(reaction);
        try {
            ReactionJpaEntity saved = repository.saveAndFlush(entity);
            return mapper.toDomain(saved);
        } catch (DataIntegrityViolationException ex) {
            if (isDuplicateConstraintViolation(ex)) {
                throw new DuplicateReactionException(
                        reaction.getUserId(),
                        reaction.getTarget(),
                        ex
                );
            }
            throw ex;
        }
    }

    @Override
    public Optional<Reaction> findById(UUID reactionId) {
        if (reactionId == null) {
            throw new IllegalArgumentException("Reaction ID cannot be null.");
        }
        return repository.findById(reactionId.toString()).map(mapper::toDomain);
    }

    @Override
    public Optional<Reaction> findByUserAndTarget(UUID userId, ReactionTarget target) {
        if (userId == null || target == null) {
            return Optional.empty();
        }
        return repository.findByUserIdAndTargetTypeAndTargetId(
                userId.toString(),
                target.type().name(),
                target.targetId().toString()
        ).map(mapper::toDomain);
    }

    @Override
    public Optional<ReactionType> findUserReactionType(UUID userId, ReactionTarget target) {
        if (userId == null || target == null) {
            return Optional.empty();
        }
        return repository.findByUserIdAndTargetTypeAndTargetId(
                userId.toString(),
                target.type().name(),
                target.targetId().toString()
        ).map(entity -> ReactionType.valueOf(entity.getReactionType()));
    }

    @Override
    @Transactional
    public void delete(Reaction reaction) {
        if (reaction == null) {
            throw new IllegalArgumentException("Reaction cannot be null.");
        }
        repository.deleteById(reaction.getId().toString());
        repository.flush();
    }

    @Override
    @Transactional
    public boolean deleteByUserAndTarget(UUID userId, ReactionTarget target) {
        if (userId == null || target == null) {
            return false;
        }
        return repository.deleteByUserIdAndTargetTypeAndTargetId(
                userId.toString(),
                target.type().name(),
                target.targetId().toString()
        ) > 0;
    }

    @Override
    public Map<ReactionType, Long> countReactionsByTargetGroupedByType(ReactionTarget target) {
        if (target == null) {
            throw new IllegalArgumentException("ReactionTarget cannot be null.");
        }

        List<Object[]> rows = repository.countReactionsByTargetGroupedByType(
                target.type().name(),
                target.targetId().toString()
        );

        Map<ReactionType, Long> counts = new EnumMap<>(ReactionType.class);
        for (ReactionType type : ReactionType.values()) {
            counts.put(type, 0L);
        }

        if (rows != null) {
            for (Object[] row : rows) {
                if (row != null && row.length >= 2 && row[0] != null && row[1] != null) {
                    ReactionType type = ReactionType.valueOf((String) row[0]);
                    long count = ((Number) row[1]).longValue();
                    counts.put(type, count);
                }
            }
        }

        return counts;
    }

    @Override
    public Map<UUID, Map<ReactionType, Long>> countReactionsByTargetIdsGroupedByType(
            ReactionTargetType targetType,
            Collection<UUID> targetIds
    ) {
        if (targetType == null) {
            throw new IllegalArgumentException("ReactionTargetType cannot be null.");
        }
        if (targetIds == null || targetIds.isEmpty()) {
            return Map.of();
        }

        List<String> idStrings = targetIds.stream()
                .filter(Objects::nonNull)
                .map(UUID::toString)
                .toList();
        if (idStrings.isEmpty()) {
            return Map.of();
        }

        List<Object[]> rows = repository.countReactionsByTargetIdsGroupedByType(
                targetType.name(),
                idStrings
        );

        Map<UUID, Map<ReactionType, Long>> result = new HashMap<>();
        for (UUID targetId : targetIds) {
            if (targetId != null) {
                Map<ReactionType, Long> defaultMap = new EnumMap<>(ReactionType.class);
                for (ReactionType type : ReactionType.values()) {
                    defaultMap.put(type, 0L);
                }
                result.put(targetId, defaultMap);
            }
        }

        if (rows != null) {
            for (Object[] row : rows) {
                if (row != null && row.length >= 3 && row[0] != null && row[1] != null && row[2] != null) {
                    UUID targetId = UUID.fromString((String) row[0]);
                    ReactionType reactionType = ReactionType.valueOf((String) row[1]);
                    long count = ((Number) row[2]).longValue();
                    Map<ReactionType, Long> map = result.get(targetId);
                    if (map != null) {
                        map.put(reactionType, count);
                    }
                }
            }
        }

        return result;
    }

    @Override
    public Map<UUID, ReactionType> findUserReactionsForTargetIds(
            UUID userId,
            ReactionTargetType targetType,
            Collection<UUID> targetIds
    ) {
        if (userId == null || targetType == null || targetIds == null || targetIds.isEmpty()) {
            return Map.of();
        }

        List<String> idStrings = targetIds.stream()
                .filter(Objects::nonNull)
                .map(UUID::toString)
                .toList();
        if (idStrings.isEmpty()) {
            return Map.of();
        }

        List<ReactionJpaEntity> entities = repository.findByUserIdAndTargetTypeAndTargetIdIn(
                userId.toString(),
                targetType.name(),
                idStrings
        );

        Map<UUID, ReactionType> result = new HashMap<>();
        for (ReactionJpaEntity entity : entities) {
            result.put(
                    UUID.fromString(entity.getTargetId()),
                    ReactionType.valueOf(entity.getReactionType())
            );
        }
        return result;
    }

    @Override
    public long countTotalReactionsByTarget(ReactionTarget target) {
        if (target == null) {
            throw new IllegalArgumentException("ReactionTarget cannot be null.");
        }
        return repository.countTotalReactionsByTarget(
                target.type().name(),
                target.targetId().toString()
        );
    }

    private boolean isDuplicateConstraintViolation(DataIntegrityViolationException ex) {
        Throwable current = ex;
        while (current != null) {
            if (current instanceof org.hibernate.exception.ConstraintViolationException cve) {
                if (cve.getConstraintName() != null
                        && cve.getConstraintName().toLowerCase().contains(TARGET_UNIQUE_CONSTRAINT)) {
                    return true;
                }
            }
            if (current.getMessage() != null
                    && current.getMessage().toLowerCase().contains(TARGET_UNIQUE_CONSTRAINT)) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }
}
