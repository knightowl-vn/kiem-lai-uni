package com.universe.identity.infrastructure.persistence;

import com.universe.identity.application.ports.UserPublicProfileQueryPort;
import com.universe.identity.contracts.dto.UserPublicProfileDTO;
import com.universe.identity.contracts.dto.UserPublicProfileDetailsDTO;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Persistence adapter executing lightweight batch lookups and searches of public user profiles.
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
                resultMap.put(uid, new UserPublicProfileDTO(uid, rawName, proj.getAvatarUrl(), proj.getPublicHandle()));
            } catch (IllegalArgumentException ignored) {
                // Ignore invalid UUID strings if any corrupted data exists
            }
        }
        return Collections.unmodifiableMap(resultMap);
    }

    @Override
    public Optional<UserPublicProfileDTO> findPublicProfileByHandle(String publicHandle) {
        if (publicHandle == null || publicHandle.isBlank()) {
            return Optional.empty();
        }
        String normalized = publicHandle.trim();
        while (normalized.startsWith("@")) {
            normalized = normalized.substring(1).trim();
        }
        if (normalized.isEmpty()) {
            return Optional.empty();
        }
        String canonicalHandle = normalized.toLowerCase(Locale.ROOT);
        return userRepository.findActivePublicProfileByHandle(canonicalHandle)
                .filter(p -> p != null && p.getUserId() != null)
                .map(p -> {
                    try {
                        UUID uid = UUID.fromString(p.getUserId());
                        return new UserPublicProfileDTO(uid, p.getDisplayName(), p.getAvatarUrl(), p.getPublicHandle());
                    } catch (IllegalArgumentException e) {
                        return null;
                    }
                })
                .filter(Objects::nonNull);
    }

    @Override
    public Optional<UserPublicProfileDetailsDTO> findPublicProfileDetailsByHandle(String publicHandle) {
        if (publicHandle == null || publicHandle.isBlank()) {
            return Optional.empty();
        }
        String normalized = publicHandle.trim();
        while (normalized.startsWith("@")) {
            normalized = normalized.substring(1).trim();
        }
        if (normalized.isEmpty()) {
            return Optional.empty();
        }
        String canonicalHandle = normalized.toLowerCase(Locale.ROOT);
        return userRepository.findActivePublicProfileDetailsByHandle(canonicalHandle)
                .filter(p -> p != null && p.getUserId() != null && p.getDisplayName() != null && p.getPublicHandle() != null)
                .map(p -> {
                    try {
                        UUID uid = UUID.fromString(p.getUserId());
                        return new UserPublicProfileDetailsDTO(
                                uid,
                                p.getDisplayName(),
                                p.getAvatarUrl(),
                                p.getPublicHandle(),
                                p.getBio()
                        );
                    } catch (IllegalArgumentException e) {
                        return null;
                    }
                })
                .filter(Objects::nonNull);
    }

    @Override
    public List<UserPublicProfileDTO> searchPublicUsers(String query, int limit) {
        if (query == null || query.isBlank() || limit <= 0) {
            return List.of();
        }
        int maxResults = Math.min(limit, 50);
        String cleanQuery = query.trim();
        while (cleanQuery.startsWith("@")) {
            cleanQuery = cleanQuery.substring(1).trim();
        }
        if (cleanQuery.isEmpty()) {
            return List.of();
        }

        String lowerQuery = cleanQuery.toLowerCase(Locale.ROOT);
        String escapedQuery = escapeLike(lowerQuery);

        List<UserPublicProfileProjection> projections = userRepository.searchActivePublicUsers(
                lowerQuery,
                escapedQuery,
                lowerQuery,
                escapedQuery,
                escapedQuery,
                PageRequest.of(0, maxResults)
        );
        if (projections == null || projections.isEmpty()) {
            return List.of();
        }

        return projections.stream()
                .filter(p -> p != null && p.getUserId() != null && p.getDisplayName() != null && p.getPublicHandle() != null)
                .map(p -> {
                    try {
                        UUID uid = UUID.fromString(p.getUserId());
                        return new UserPublicProfileDTO(uid, p.getDisplayName(), p.getAvatarUrl(), p.getPublicHandle());
                    } catch (IllegalArgumentException e) {
                        return null;
                    }
                })
                .filter(Objects::nonNull)
                .toList();
    }

    private String escapeLike(String input) {
        if (input == null) {
            return "";
        }
        return input
                .replace("\\", "\\\\")
                .replace("%", "\\%")
                .replace("_", "\\_");
    }
}
