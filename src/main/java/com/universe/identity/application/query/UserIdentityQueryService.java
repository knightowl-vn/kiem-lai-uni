package com.universe.identity.application.query;

import com.universe.identity.application.ports.UserPublicProfileQueryPort;
import com.universe.identity.application.ports.UserRepositoryPort;
import com.universe.identity.contracts.dto.UserDTO;
import com.universe.identity.contracts.dto.UserPublicProfileDTO;
import com.universe.identity.contracts.interfaces.UserIdentityContract;
import com.universe.identity.domain.Email;
import com.universe.identity.domain.User;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Service
@Transactional(readOnly = true)
public class UserIdentityQueryService
        implements UserIdentityContract {

    private final UserRepositoryPort userRepository;
    private final UserPublicProfileQueryPort userPublicProfileQueryPort;

    public UserIdentityQueryService(
            UserRepositoryPort userRepository,
            UserPublicProfileQueryPort userPublicProfileQueryPort
    ) {
        this.userRepository = Objects.requireNonNull(userRepository, "UserRepositoryPort cannot be null");
        this.userPublicProfileQueryPort = Objects.requireNonNull(
                userPublicProfileQueryPort, "UserPublicProfileQueryPort cannot be null"
        );
    }

    @Override
    public Optional<UserDTO> findById(
            UUID userId
    ) {
        if (userId == null) {
            return Optional.empty();
        }

        return userRepository
                .findById(userId)
                .map(this::toDto);
    }

    @Override
    public Optional<UserDTO> findByEmail(
            String email
    ) {
        if (email == null || email.isBlank()) {
            return Optional.empty();
        }

        return userRepository
                .findByEmail(new Email(email))
                .map(this::toDto);
    }

    @Override
    public boolean existsById(
            UUID userId
    ) {
        return findById(userId).isPresent();
    }

    @Override
    public Map<UUID, UserPublicProfileDTO> findPublicProfilesByIds(
            Set<UUID> userIds
    ) {
        if (userIds == null || userIds.isEmpty()) {
            return Map.of();
        }

        return userPublicProfileQueryPort.findPublicProfilesByIds(userIds);
    }

    private UserDTO toDto(
            User user
    ) {
        return new UserDTO(
                user.getId(),
                user.getEmail().value(),
                user.getDisplayName(),
                user.getAvatarUrl(),
                user.getStatus().name(),
                user.getRole().name(),
                user.getCreatedAt()
        );
    }
}