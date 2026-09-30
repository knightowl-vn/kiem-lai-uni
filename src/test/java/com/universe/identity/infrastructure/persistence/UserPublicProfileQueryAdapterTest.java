package com.universe.identity.infrastructure.persistence;

import com.universe.identity.contracts.dto.UserPublicProfileDTO;
import com.universe.identity.contracts.dto.UserPublicProfileDetailsDTO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
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
        UserPublicProfileProjection proj1 = createProjection(USER_1_ID.toString(), "Tu Tiên Giả", "https://example.com/avatar1.jpg", "tu_tien_gia");
        UserPublicProfileProjection proj2 = createProjection(USER_2_ID.toString(), "   ", null, "user_2");

        when(userRepository.findPublicProfilesByIdIn(Set.of(USER_1_ID.toString(), USER_2_ID.toString())))
                .thenReturn(List.of(proj1, proj2));

        Map<UUID, UserPublicProfileDTO> result = adapter.findPublicProfilesByIds(Set.of(USER_1_ID, USER_2_ID));

        assertThat(result).hasSize(2);

        UserPublicProfileDTO dto1 = result.get(USER_1_ID);
        assertThat(dto1).isNotNull();
        assertThat(dto1.userId()).isEqualTo(USER_1_ID);
        assertThat(dto1.displayName()).isEqualTo("Tu Tiên Giả");
        assertThat(dto1.avatarUrl()).isEqualTo("https://example.com/avatar1.jpg");
        assertThat(dto1.publicHandle()).isEqualTo("tu_tien_gia");

        UserPublicProfileDTO dto2 = result.get(USER_2_ID);
        assertThat(dto2).isNotNull();
        assertThat(dto2.userId()).isEqualTo(USER_2_ID);
        assertThat(dto2.displayName()).isEqualTo("   ");
        assertThat(dto2.avatarUrl()).isNull();
        assertThat(dto2.publicHandle()).isEqualTo("user_2");
    }

    @Test
    @DisplayName("findPublicProfileByHandle trả về rỗng khi input null hoặc blank")
    void shouldReturnEmptyWhenHandleNullOrBlank() {
        assertThat(adapter.findPublicProfileByHandle(null)).isEmpty();
        assertThat(adapter.findPublicProfileByHandle("  ")).isEmpty();
    }

    @Test
    @DisplayName("findPublicProfileByHandle trả về UserPublicProfileDTO khi tìm thấy")
    void shouldFindPublicProfileByHandle() {
        UserPublicProfileProjection proj = createProjection(USER_1_ID.toString(), "Athena", "https://img.com/a.png", "athena");
        when(userRepository.findActivePublicProfileByHandle("athena")).thenReturn(Optional.of(proj));

        Optional<UserPublicProfileDTO> result = adapter.findPublicProfileByHandle("athena");

        assertThat(result).isPresent();
        assertThat(result.get().userId()).isEqualTo(USER_1_ID);
        assertThat(result.get().displayName()).isEqualTo("Athena");
        assertThat(result.get().publicHandle()).isEqualTo("athena");
        assertThat(result.get().avatarUrl()).isEqualTo("https://img.com/a.png");
    }

    @Test
    @DisplayName("searchPublicUsers map đúng dữ liệu và truyền tham số tìm kiếm kèm escape ký tự đặc biệt LIKE")
    void shouldSearchPublicUsersAndMapProjections() {
        UUID id1 = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UserPublicProfileProjection proj1 = createProjection(id1.toString(), "Alex", null, "alex");

        when(userRepository.searchActivePublicUsers(
                eq("alex"),
                eq("alex"),
                eq("alex"),
                eq("alex"),
                eq("alex"),
                eq(org.springframework.data.domain.PageRequest.of(0, 10))
        )).thenReturn(List.of(proj1));

        List<UserPublicProfileDTO> results = adapter.searchPublicUsers("@alex", 10);

        assertThat(results).hasSize(1);
        assertThat(results.get(0).userId()).isEqualTo(id1);
        assertThat(results.get(0).displayName()).isEqualTo("Alex");
        assertThat(results.get(0).publicHandle()).isEqualTo("alex");
    }

    @Test
    @DisplayName("searchPublicUsers escape các ký tự đặc biệt LIKE (%, _, \\)")
    void shouldEscapeLikeMetacharactersInSearch() {
        when(userRepository.searchActivePublicUsers(
                eq("100%_user\\test"),
                eq("100\\%\\_user\\\\test"),
                eq("100%_user\\test"),
                eq("100\\%\\_user\\\\test"),
                eq("100\\%\\_user\\\\test"),
                eq(org.springframework.data.domain.PageRequest.of(0, 5))
        )).thenReturn(List.of());

        List<UserPublicProfileDTO> results = adapter.searchPublicUsers("100%_user\\test", 5);

        assertThat(results).isEmpty();
        verify(userRepository).searchActivePublicUsers(
                eq("100%_user\\test"),
                eq("100\\%\\_user\\\\test"),
                eq("100%_user\\test"),
                eq("100\\%\\_user\\\\test"),
                eq("100\\%\\_user\\\\test"),
                any()
        );
    }

    @Test
    @DisplayName("findPublicProfileByHandle loại bỏ tiền tố @ trong truy vấn")
    void shouldStripAtPrefixInFindPublicProfileByHandle() {
        UserPublicProfileProjection proj = createProjection(USER_1_ID.toString(), "Athena", "https://img.com/a.png", "athena");
        when(userRepository.findActivePublicProfileByHandle("athena")).thenReturn(Optional.of(proj));

        Optional<UserPublicProfileDTO> result = adapter.findPublicProfileByHandle("@athena");

        assertThat(result).isPresent();
        assertThat(result.get().publicHandle()).isEqualTo("athena");
        verify(userRepository).findActivePublicProfileByHandle("athena");
    }

    @Test
    @DisplayName("searchPublicUsers trả về danh sách rỗng khi query rỗng hoặc limit <= 0")
    void shouldReturnEmptyWhenSearchQueryBlankOrLimitZero() {
        assertThat(adapter.searchPublicUsers(null, 10)).isEmpty();
        assertThat(adapter.searchPublicUsers("   ", 10)).isEmpty();
        assertThat(adapter.searchPublicUsers("alex", 0)).isEmpty();
        assertThat(adapter.searchPublicUsers("alex", -5)).isEmpty();
    }

    @Test
    @DisplayName("findPublicProfileDetailsByHandle trả về rỗng khi input null hoặc blank")
    void shouldReturnEmptyWhenDetailsHandleNullOrBlank() {
        assertThat(adapter.findPublicProfileDetailsByHandle(null)).isEmpty();
        assertThat(adapter.findPublicProfileDetailsByHandle("  ")).isEmpty();
        assertThat(adapter.findPublicProfileDetailsByHandle("@@@")).isEmpty();
    }

    @Test
    @DisplayName("findPublicProfileDetailsByHandle trả về UserPublicProfileDetailsDTO khi tìm thấy kèm bio và avatar")
    void shouldFindPublicProfileDetailsByHandleWithBioAndAvatar() {
        UserPublicProfileDetailsProjection proj = createDetailsProjection(
                USER_1_ID.toString(),
                "Athena",
                "https://img.com/a.png",
                "athena",
                "Nữ thần trí tuệ và chiến tranh chính nghĩa."
        );
        when(userRepository.findActivePublicProfileDetailsByHandle("athena")).thenReturn(Optional.of(proj));

        Optional<UserPublicProfileDetailsDTO> result = adapter.findPublicProfileDetailsByHandle("athena");

        assertThat(result).isPresent();
        assertThat(result.get().userId()).isEqualTo(USER_1_ID);
        assertThat(result.get().displayName()).isEqualTo("Athena");
        assertThat(result.get().publicHandle()).isEqualTo("athena");
        assertThat(result.get().avatarUrl()).isEqualTo("https://img.com/a.png");
        assertThat(result.get().bio()).isEqualTo("Nữ thần trí tuệ và chiến tranh chính nghĩa.");
    }

    @Test
    @DisplayName("findPublicProfileDetailsByHandle hỗ trợ null bio và null avatar")
    void shouldFindPublicProfileDetailsByHandleWithNullBioAndNullAvatar() {
        UserPublicProfileDetailsProjection proj = createDetailsProjection(
                USER_2_ID.toString(),
                "Ẩn Danh",
                null,
                "an_danh",
                null
        );
        when(userRepository.findActivePublicProfileDetailsByHandle("an_danh")).thenReturn(Optional.of(proj));

        Optional<UserPublicProfileDetailsDTO> result = adapter.findPublicProfileDetailsByHandle("an_danh");

        assertThat(result).isPresent();
        assertThat(result.get().userId()).isEqualTo(USER_2_ID);
        assertThat(result.get().displayName()).isEqualTo("Ẩn Danh");
        assertThat(result.get().publicHandle()).isEqualTo("an_danh");
        assertThat(result.get().avatarUrl()).isNull();
        assertThat(result.get().bio()).isNull();
    }

    @Test
    @DisplayName("findPublicProfileDetailsByHandle chuẩn hóa tiền tố @ và chuyển chữ thường")
    void shouldStripAtPrefixAndCanonicalizeCaseInFindPublicProfileDetailsByHandle() {
        UserPublicProfileDetailsProjection proj = createDetailsProjection(
                USER_1_ID.toString(),
                "Athena",
                "https://img.com/a.png",
                "athena",
                "Bio text"
        );
        when(userRepository.findActivePublicProfileDetailsByHandle("athena")).thenReturn(Optional.of(proj));

        Optional<UserPublicProfileDetailsDTO> result = adapter.findPublicProfileDetailsByHandle("@Athena");

        assertThat(result).isPresent();
        assertThat(result.get().publicHandle()).isEqualTo("athena");
        verify(userRepository).findActivePublicProfileDetailsByHandle("athena");
    }

    private UserPublicProfileProjection createProjection(String userId, String displayName, String avatarUrl, String publicHandle) {
        return new UserPublicProfileProjection() {
            @Override public String getUserId() { return userId; }
            @Override public String getDisplayName() { return displayName; }
            @Override public String getAvatarUrl() { return avatarUrl; }
            @Override public String getPublicHandle() { return publicHandle; }
        };
    }

    private UserPublicProfileDetailsProjection createDetailsProjection(
            String userId,
            String displayName,
            String avatarUrl,
            String publicHandle,
            String bio
    ) {
        return new UserPublicProfileDetailsProjection() {
            @Override public String getUserId() { return userId; }
            @Override public String getDisplayName() { return displayName; }
            @Override public String getAvatarUrl() { return avatarUrl; }
            @Override public String getPublicHandle() { return publicHandle; }
            @Override public String getBio() { return bio; }
        };
    }
}
