package com.universe.identity.infrastructure.persistence;

import com.universe.identity.application.ports.UserPublicProfileQueryPort;
import com.universe.identity.contracts.dto.UserPublicProfileDTO;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Persistence adapter executing lightweight batch lookups of public user profiles.
 */
@Component
public class UserPublicProfileQueryAdapter implements UserPublicProfileQueryPort {

    private final SpringDataUserJpaRepository userRepository;

    public UserPublicProfileQueryAdapter(SpringDataUserJpaRepository userRepository) {
        this.userRepository = Objects.requireNonNull(userRepository, "SpringDataUserJpaRepository cannot be null");
    }

    @Override
    public Map<UUID, UserPublicProfileDTO> findPublicProfilesByIds(Set<UUID> userIds) {
        if (userIds == null || userIds.isEmpty()) {
            return Map.of();
        }

        Set<String> stringIds = userIds.stream()
                .filter(Objects::nonNull)
                .map(UUID::toString)
                .collect(Collectors.toSet());

        if (stringIds.isEmpty()) {
            return Map.of();
        }

        List<UserPublicProfileProjection> projections = userRepository.findPublicProfilesByIdIn(stringIds);
        if (projections == null || projections.isEmpty()) {
            return Map.of();
        }

        Map<UUID, UserPublicProfileDTO> resultMap = new HashMap<>();
        for (UserPublicProfileProjection proj : projections) {
            if (proj == null || proj.getUserId() == null) {
                continue;
            }
            try {
                UUID uid = UUID.fromString(proj.getUserId());
                String rawName = proj.getDisplayName();
                resultMap.put(uid, new UserPublicProfileDTO(uid, rawName, proj.getAvatarUrl()));
            } catch (IllegalArgumentException ignored) {
                // Ignore invalid UUID strings if any corrupted data exists
            }
        }
        return Collections.unmodifiableMap(resultMap);
    }
}
