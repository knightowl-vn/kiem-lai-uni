package com.universe.identity.infrastructure.persistence;

import com.universe.identity.contracts.dto.UserPublicProfileDTO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("UserPublicProfileQueryAdapter Unit Tests")
class UserPublicProfileQueryAdapterTest {

    @Mock
    private SpringDataUserJpaRepository userRepository;

    private UserPublicProfileQueryAdapter adapter;

    private static final UUID USER_1_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID USER_2_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");

    @BeforeEach
    void setUp() {
        adapter = new UserPublicProfileQueryAdapter(userRepository);
    }

    @Test
    @DisplayName("Should return empty map immediately when input userIds set is null or empty")
    void shouldReturnEmptyMapWhenUserIdsNullOrEmpty() {
        assertThat(adapter.findPublicProfilesByIds(null)).isEmpty();
        assertThat(adapter.findPublicProfilesByIds(Collections.emptySet())).isEmpty();

        verify(userRepository, never()).findPublicProfilesByIdIn(anyCollection());
    }

    @Test
    @DisplayName("Should return populated map when users exist")
    void shouldReturnPopulatedMapWhenUsersExist() {
        UserPublicProfileProjection proj1 = new UserPublicProfileProjection() {
            @Override public String getUserId() { return USER_1_ID.toString(); }
            @Override public String getDisplayName() { return "Tu Tiên Giả"; }
            @Override public String getAvatarUrl() { return "https://example.com/avatar1.jpg"; }
        };

        UserPublicProfileProjection proj2 = new UserPublicProfileProjection() {
            @Override public String getUserId() { return USER_2_ID.toString(); }
            @Override public String getDisplayName() { return "   "; } // blank -> fallback
            @Override public String getAvatarUrl() { return null; }
        };

        when(userRepository.findPublicProfilesByIdIn(Set.of(USER_1_ID.toString(), USER_2_ID.toString())))
                .thenReturn(List.of(proj1, proj2));

        Map<UUID, UserPublicProfileDTO> result = adapter.findPublicProfilesByIds(Set.of(USER_1_ID, USER_2_ID));

        assertThat(result).hasSize(2);

        UserPublicProfileDTO dto1 = result.get(USER_1_ID);
        assertThat(dto1).isNotNull();
        assertThat(dto1.userId()).isEqualTo(USER_1_ID);
        assertThat(dto1.displayName()).isEqualTo("Tu Tiên Giả");
        assertThat(dto1.avatarUrl()).isEqualTo("https://example.com/avatar1.jpg");

        UserPublicProfileDTO dto2 = result.get(USER_2_ID);
        assertThat(dto2).isNotNull();
        assertThat(dto2.userId()).isEqualTo(USER_2_ID);
        assertThat(dto2.displayName()).isEqualTo("   "); // raw preserved, no presentation fallback in Identity
        assertThat(dto2.avatarUrl()).isNull();
    }
}
