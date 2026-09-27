package com.universe.identity.application.query;

import com.universe.identity.application.ports.UserPublicProfileQueryPort;
import com.universe.identity.application.ports.UserRepositoryPort;
import com.universe.identity.contracts.dto.UserPublicProfileDTO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Collections;
import java.util.Map;
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
        UserPublicProfileDTO profile = new UserPublicProfileDTO(userId, "Người Dùng", "https://img.com/avatar.jpg");

        when(userPublicProfileQueryPort.findPublicProfilesByIds(Set.of(userId)))
                .thenReturn(Map.of(userId, profile));

        Map<UUID, UserPublicProfileDTO> result = queryService.findPublicProfilesByIds(Set.of(userId));

        assertThat(result).containsEntry(userId, profile);
        verify(userPublicProfileQueryPort).findPublicProfilesByIds(Set.of(userId));
    }
}
