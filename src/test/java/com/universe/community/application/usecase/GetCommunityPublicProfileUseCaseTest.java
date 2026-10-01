package com.universe.community.application.usecase;

import com.universe.community.application.port.out.CommunityAuthorProfileDetails;
import com.universe.community.application.port.out.CommunityAuthorProfilePort;
import com.universe.community.contracts.dto.CommunityAuthorProfileDTO;
import com.universe.community.contracts.dto.CommunityNewestFeedResponseDTO;
import com.universe.community.contracts.dto.CommunityPostFeedItemDTO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("GetCommunityPublicProfileUseCase Unit Tests")
class GetCommunityPublicProfileUseCaseTest {

    @Mock
    private CommunityAuthorProfilePort authorProfilePort;

    @Mock
    private GetCommunityAuthorPostsUseCase getCommunityAuthorPostsUseCase;

    private GetCommunityPublicProfileUseCase useCase;

    @BeforeEach
    void setUp() {
        useCase = new GetCommunityPublicProfileUseCase(authorProfilePort, getCommunityAuthorPostsUseCase);
    }

    @Test
    @DisplayName("Should throw NullPointerException when constructor dependencies are null")
    void shouldThrowWhenConstructorArgsAreNull() {
        assertThatThrownBy(() -> new GetCommunityPublicProfileUseCase(null, getCommunityAuthorPostsUseCase))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("CommunityAuthorProfilePort cannot be null.");

        assertThatThrownBy(() -> new GetCommunityPublicProfileUseCase(authorProfilePort, null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("GetCommunityAuthorPostsUseCase cannot be null.");
    }

    @Test
    @DisplayName("Should return composed author profile with first page of posts (cursor=null, size=20)")
    void shouldReturnAuthorProfileWithFirstPageOfPostsSuccessfully() {
        UUID authorId = UUID.randomUUID();
        String handle = "linh_dao";
        CommunityAuthorProfileDetails author = new CommunityAuthorProfileDetails(
                authorId,
                handle,
                "Linh Đạo",
                "https://cdn.example.com/avatar.jpg",
                "Tiểu thuyết gia Kiếm Lai"
        );

        UUID p1Id = UUID.randomUUID();
        Instant now = Instant.parse("2026-09-30T12:00:00Z");
        CommunityPostFeedItemDTO postItem = new CommunityPostFeedItemDTO(
                p1Id,
                authorId,
                "Bài viết đầu tiên",
                null,
                null,
                0,
                10L,
                2L,
                12L,
                now,
                now
        );
        CommunityNewestFeedResponseDTO feedResponse = new CommunityNewestFeedResponseDTO(
                List.of(postItem),
                "cursor-xyz",
                20,
                true
        );

        when(authorProfilePort.findAuthorProfileByHandle(handle)).thenReturn(Optional.of(author));
        when(getCommunityAuthorPostsUseCase.execute(eq(authorId), isNull(), eq(GetCommunityAuthorPostsUseCase.DEFAULT_SIZE)))
                .thenReturn(feedResponse);

        Optional<CommunityAuthorProfileDTO> profileOpt = useCase.execute(handle);

        assertThat(profileOpt).isPresent();
        CommunityAuthorProfileDTO profile = profileOpt.get();
        assertThat(profile.publicHandle()).isEqualTo("linh_dao");
        assertThat(profile.displayName()).isEqualTo("Linh Đạo");
        assertThat(profile.avatarUrl()).isEqualTo("https://cdn.example.com/avatar.jpg");
        assertThat(profile.bio()).isEqualTo("Tiểu thuyết gia Kiếm Lai");
        assertThat(profile.posts()).isSameAs(feedResponse);
        assertThat(profile.posts().items()).hasSize(1);
        assertThat(profile.posts().items().get(0).caption()).isEqualTo("Bài viết đầu tiên");

        verify(authorProfilePort).findAuthorProfileByHandle(handle);
        verify(getCommunityAuthorPostsUseCase).execute(authorId, null, 20);
    }

    @Test
    @DisplayName("Should return composed author profile with empty feed when author has zero posts")
    void shouldReturnProfileWithEmptyFeedWhenAuthorHasNoPosts() {
        UUID authorId = UUID.randomUUID();
        String handle = "new_author";
        CommunityAuthorProfileDetails author = new CommunityAuthorProfileDetails(
                authorId,
                handle,
                "New Author",
                null,
                null
        );

        CommunityNewestFeedResponseDTO emptyFeed = new CommunityNewestFeedResponseDTO(
                List.of(),
                null,
                20,
                false
        );

        when(authorProfilePort.findAuthorProfileByHandle(handle)).thenReturn(Optional.of(author));
        when(getCommunityAuthorPostsUseCase.execute(eq(authorId), isNull(), eq(20)))
                .thenReturn(emptyFeed);

        Optional<CommunityAuthorProfileDTO> profileOpt = useCase.execute(handle);

        assertThat(profileOpt).isPresent();
        CommunityAuthorProfileDTO profile = profileOpt.get();
        assertThat(profile.publicHandle()).isEqualTo("new_author");
        assertThat(profile.displayName()).isEqualTo("New Author");
        assertThat(profile.avatarUrl()).isNull();
        assertThat(profile.bio()).isNull();
        assertThat(profile.posts().items()).isEmpty();
        assertThat(profile.posts().hasNext()).isFalse();
    }

    @Test
    @DisplayName("Should return Optional.empty() when author profile is not found in port")
    void shouldReturnEmptyWhenAuthorProfileNotFoundInPort() {
        when(authorProfilePort.findAuthorProfileByHandle("ghost_user")).thenReturn(Optional.empty());

        Optional<CommunityAuthorProfileDTO> profileOpt = useCase.execute("ghost_user");

        assertThat(profileOpt).isEmpty();
        verify(authorProfilePort).findAuthorProfileByHandle("ghost_user");
        verifyNoInteractions(getCommunityAuthorPostsUseCase);
    }

    @Test
    @DisplayName("Should return Optional.empty() when handle is null")
    void shouldReturnEmptyWhenHandleIsNull() {
        Optional<CommunityAuthorProfileDTO> profileOpt = useCase.execute(null);

        assertThat(profileOpt).isEmpty();
        verifyNoInteractions(authorProfilePort);
        verifyNoInteractions(getCommunityAuthorPostsUseCase);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   ", "\t", "\n"})
    @DisplayName("Should return Optional.empty() when handle is empty or whitespace-only")
    void shouldReturnEmptyWhenHandleIsBlank(String blankHandle) {
        Optional<CommunityAuthorProfileDTO> profileOpt = useCase.execute(blankHandle);

        assertThat(profileOpt).isEmpty();
        verifyNoInteractions(authorProfilePort);
        verifyNoInteractions(getCommunityAuthorPostsUseCase);
    }
}
