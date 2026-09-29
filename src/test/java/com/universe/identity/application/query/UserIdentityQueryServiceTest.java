package com.universe.identity.application.query;

import com.universe.identity.application.ports.UserPublicProfileQueryPort;
import com.universe.identity.application.ports.UserRepositoryPort;
import com.universe.identity.contracts.dto.UserDTO;
import com.universe.identity.contracts.dto.UserPublicProfileDTO;
import com.universe.identity.domain.Email;
import com.universe.identity.domain.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("UserIdentityQueryService Unit Tests")
class UserIdentityQueryServiceTest {

    @Mock
    private UserRepositoryPort userRepositoryPort;

    @Mock
    private UserPublicProfileQueryPort userPublicProfileQueryPort;

    private UserIdentityQueryService queryService;

    @BeforeEach
    void setUp() {
        queryService = new UserIdentityQueryService(userRepositoryPort, userPublicProfileQueryPort);
    }

    @Test
    @DisplayName("Should return empty map immediately when userIds is null or empty")
    void shouldReturnEmptyMapWhenUserIdsNullOrEmpty() {
        assertThat(queryService.findPublicProfilesByIds(null)).isEmpty();
        assertThat(queryService.findPublicProfilesByIds(Collections.emptySet())).isEmpty();

        verify(userPublicProfileQueryPort, never()).findPublicProfilesByIds(Collections.emptySet());
    }

    @Test
    @DisplayName("Should delegate to userPublicProfileQueryPort when userIds is non-empty")
    void shouldDelegateToQueryPortWhenUserIdsNonEmpty() {
        UUID userId = UUID.randomUUID();
        UserPublicProfileDTO profile = new UserPublicProfileDTO(userId, "Người Dùng", "https://img.com/avatar.jpg", "nguoi_dung");

        when(userPublicProfileQueryPort.findPublicProfilesByIds(Set.of(userId)))
                .thenReturn(Map.of(userId, profile));

        Map<UUID, UserPublicProfileDTO> result = queryService.findPublicProfilesByIds(Set.of(userId));

        assertThat(result).containsEntry(userId, profile);
        verify(userPublicProfileQueryPort).findPublicProfilesByIds(Set.of(userId));
    }

    @Test
    @DisplayName("Should find UserDTO by ID")
    void shouldFindById() {
        UUID userId = UUID.randomUUID();
        Instant now = Instant.now();
        User user = User.createLocal(
                userId,
                new Email("test@example.com"),
                "$2a$10$hash",
                "Athena Goddess",
                "athena_goddess",
                now
        );

        when(userRepositoryPort.findById(userId)).thenReturn(Optional.of(user));

        Optional<UserDTO> result = queryService.findById(userId);

        assertThat(result).isPresent();
        assertThat(result.get().id()).isEqualTo(userId);
        assertThat(result.get().displayName()).isEqualTo("Athena Goddess");
        assertThat(result.get().publicHandle()).isEqualTo("athena_goddess");
    }

    @Test
    @DisplayName("Should find UserDTO by email")
    void shouldFindByEmail() {
        UUID userId = UUID.randomUUID();
        Instant now = Instant.now();
        User user = User.createLocal(
                userId,
                new Email("test@example.com"),
                "$2a$10$hash",
                "Athena Goddess",
                "athena_goddess",
                now
        );

        when(userRepositoryPort.findByEmail(new Email("test@example.com"))).thenReturn(Optional.of(user));

        Optional<UserDTO> result = queryService.findByEmail("test@example.com");

        assertThat(result).isPresent();
        assertThat(result.get().id()).isEqualTo(userId);
        assertThat(result.get().displayName()).isEqualTo("Athena Goddess");
        assertThat(result.get().publicHandle()).isEqualTo("athena_goddess");
    }

    @Test
    @DisplayName("Should delegate findPublicProfileByHandle to query port")
    void shouldDelegateFindPublicProfileByHandle() {
        UUID userId = UUID.randomUUID();
        UserPublicProfileDTO profile = new UserPublicProfileDTO(userId, "Athena Goddess", "https://img.com/avatar.png", "athena_goddess");

        when(userPublicProfileQueryPort.findPublicProfileByHandle("athena_goddess"))
                .thenReturn(Optional.of(profile));

        Optional<UserPublicProfileDTO> result = queryService.findPublicProfileByHandle("athena_goddess");

        assertThat(result).contains(profile);
        verify(userPublicProfileQueryPort).findPublicProfileByHandle("athena_goddess");
    }

    @Test
    @DisplayName("Should delegate searchPublicUsers to query port")
    void shouldDelegateSearchPublicUsers() {
        UUID userId = UUID.randomUUID();
        UserPublicProfileDTO profile = new UserPublicProfileDTO(userId, "Athena Goddess", "https://img.com/avatar.png", "athena_goddess");

        when(userPublicProfileQueryPort.searchPublicUsers("athena", 10))
                .thenReturn(List.of(profile));

        List<UserPublicProfileDTO> result = queryService.searchPublicUsers("athena", 10);

        assertThat(result).containsExactly(profile);
        verify(userPublicProfileQueryPort).searchPublicUsers("athena", 10);
    }
}
